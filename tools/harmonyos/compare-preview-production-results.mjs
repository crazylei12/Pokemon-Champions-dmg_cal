import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const [androidPathArgument, harmonyPathArgument, outputPathArgument] = process.argv.slice(2);
if (!androidPathArgument || !harmonyPathArgument) {
  throw new Error('usage: node compare-preview-production-results.mjs ANDROID_JSON HARMONY_JSON [OUTPUT_JSON]');
}

const androidPath = path.resolve(androidPathArgument);
const harmonyPath = path.resolve(harmonyPathArgument);
const [androidBytes, harmonyBytes] = await Promise.all([readFile(androidPath), readFile(harmonyPath)]);
const android = JSON.parse(androidBytes.toString('utf8'));
const harmony = JSON.parse(harmonyBytes.toString('utf8'));
const numericTolerance = 0.000001;
const mismatches = [];
let totalSlots = 0;
let totalCandidates = 0;
let exactTop1 = 0;
let exactOrderedTop3 = 0;
let exactRankingSignals = 0;
let candidateNumbersWithinTolerance = 0;
let maximumConfidenceDelta = 0;
let maximumPublishedNumberDelta = 0;

function mismatch(sample, roiId, field, androidValue, harmonyValue) {
  mismatches.push({ sample, roiId, field, android: androidValue, harmony: harmonyValue });
}

function samePublishedNumber(left, right) {
  const delta = Math.abs(left - right);
  maximumPublishedNumberDelta = Math.max(maximumPublishedNumberDelta, delta);
  return Math.abs(Math.round(left * 1_000_000) - Math.round(right * 1_000_000)) <= 1;
}

if (android.kind !== 'AndroidTeamPreviewGoldenResults') mismatch('', '', 'root.kind', android.kind, harmony.kind);
if (harmony.kind !== 'HarmonyTeamPreviewNativeResults') mismatch('', '', 'root.kind', android.kind, harmony.kind);
if (android.results.length !== harmony.results.length) {
  mismatch('', '', 'root.results.length', android.results.length, harmony.results.length);
}

for (let sampleIndex = 0; sampleIndex < Math.min(android.results.length, harmony.results.length); sampleIndex += 1) {
  const androidEntry = android.results[sampleIndex];
  const harmonyEntry = harmony.results[sampleIndex];
  const sample = androidEntry.sample;
  if (sample !== harmonyEntry.sample) mismatch(sample, '', 'sample', sample, harmonyEntry.sample);
  const androidResult = androidEntry.result;
  const harmonyResult = harmonyEntry.result;
  for (const field of ['imageSize', 'roiMapping']) {
    if (JSON.stringify(androidResult[field]) !== JSON.stringify(harmonyResult[field])) {
      mismatch(sample, '', field, androidResult[field], harmonyResult[field]);
    }
  }
  const androidSlots = [...androidResult.ownTeamCandidates, ...androidResult.opponentTeamCandidates];
  const harmonySlots = [...harmonyResult.ownTeamCandidates, ...harmonyResult.opponentTeamCandidates];
  const androidSignals = androidResult.performance.slots;
  const harmonySignals = harmonyResult.performance.slots;
  if (androidSlots.length !== harmonySlots.length) {
    mismatch(sample, '', 'slots.length', androidSlots.length, harmonySlots.length);
    continue;
  }

  for (let slotIndex = 0; slotIndex < androidSlots.length; slotIndex += 1) {
    totalSlots += 1;
    const androidSlot = androidSlots[slotIndex];
    const harmonySlot = harmonySlots[slotIndex];
    const roiId = androidSlot.roiId;
    for (const field of ['side', 'slotIndex', 'roiId', 'requiresConfirmation']) {
      if (androidSlot[field] !== harmonySlot[field]) {
        mismatch(sample, roiId, field, androidSlot[field], harmonySlot[field]);
      }
    }
    const androidCandidates = androidSlot.candidates;
    const harmonyCandidates = harmonySlot.candidates;
    if (androidCandidates[0]?.canonicalId === harmonyCandidates[0]?.canonicalId) exactTop1 += 1;
    const identitiesMatch = androidCandidates.length === harmonyCandidates.length &&
      androidCandidates.every((candidate, rank) => {
        const peer = harmonyCandidates[rank];
        return peer && ['canonicalId', 'showdownId', 'displayName', 'source', 'visualVariant', 'isShiny']
          .every((field) => candidate[field] === peer[field]);
      });
    if (identitiesMatch) exactOrderedTop3 += 1;
    else mismatch(sample, roiId, 'orderedTop3Identity',
      androidCandidates.map((candidate) => candidate.canonicalId),
      harmonyCandidates.map((candidate) => candidate.canonicalId));

    for (let rank = 0; rank < Math.min(androidCandidates.length, harmonyCandidates.length); rank += 1) {
      totalCandidates += 1;
      const left = androidCandidates[rank];
      const right = harmonyCandidates[rank];
      const confidenceDelta = Math.abs(left.confidence - right.confidence);
      maximumConfidenceDelta = Math.max(maximumConfidenceDelta, confidenceDelta);
      const numbersMatch = confidenceDelta <= numericTolerance &&
        samePublishedNumber(left.score, right.score) && samePublishedNumber(left.scoreMargin, right.scoreMargin);
      if (numbersMatch) candidateNumbersWithinTolerance += 1;
      else mismatch(sample, roiId, `candidate.${rank}.numbers`,
        { confidence: left.confidence, score: left.score, scoreMargin: left.scoreMargin },
        { confidence: right.confidence, score: right.score, scoreMargin: right.scoreMargin });
    }

    const leftSignal = androidSignals[slotIndex];
    const rightSignal = harmonySignals[slotIndex];
    const signalsMatch = leftSignal.roiId === rightSignal.roiId &&
      leftSignal.adaptiveGrabCutFallback === rightSignal.adaptiveGrabCutFallback &&
      leftSignal.eligibleTemplates === rightSignal.eligibleTemplates &&
      leftSignal.refinedTemplates === rightSignal.refinedTemplates &&
      samePublishedNumber(leftSignal.colorMaskQuality, rightSignal.colorMaskQuality);
    if (signalsMatch) exactRankingSignals += 1;
    else mismatch(sample, roiId, 'preprocessingAndRankingSignals', {
      adaptiveGrabCutFallback: leftSignal.adaptiveGrabCutFallback,
      eligibleTemplates: leftSignal.eligibleTemplates,
      refinedTemplates: leftSignal.refinedTemplates,
      colorMaskQuality: leftSignal.colorMaskQuality
    }, {
      adaptiveGrabCutFallback: rightSignal.adaptiveGrabCutFallback,
      eligibleTemplates: rightSignal.eligibleTemplates,
      refinedTemplates: rightSignal.refinedTemplates,
      colorMaskQuality: rightSignal.colorMaskQuality
    });
  }
}

const report = {
  schemaVersion: 1,
  kind: 'TeamPreviewCrossPlatformProductionComparison',
  generatedAt: new Date().toISOString(),
  numericTolerance,
  inputs: {
    android: {
      path: androidPath,
      sha256: createHash('sha256').update(androidBytes).digest('hex'),
      backend: android.productionBackend
    },
    harmony: {
      path: harmonyPath,
      sha256: createHash('sha256').update(harmonyBytes).digest('hex'),
      backend: harmony.productionBackend
    }
  },
  summary: {
    samples: Math.min(android.results.length, harmony.results.length),
    totalSlots,
    totalCandidates,
    exactTop1,
    exactOrderedTop3,
    exactRankingSignals,
    candidateNumbersWithinTolerance,
    maximumConfidenceDelta,
    maximumPublishedNumberDelta,
    mismatchCount: mismatches.length,
    pass: mismatches.length === 0
  },
  mismatches
};

if (outputPathArgument) await writeFile(path.resolve(outputPathArgument), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
console.log(JSON.stringify(report.summary));
if (!report.summary.pass) process.exitCode = 1;
