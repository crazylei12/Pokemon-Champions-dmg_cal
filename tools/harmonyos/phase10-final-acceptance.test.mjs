import assert from 'node:assert/strict';
import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');

async function loadJson(relativePath) {
  return JSON.parse(await readFile(path.join(repositoryRoot, relativePath), 'utf8'));
}

const baselinePromise = loadJson('config/harmonyos-phase0-feature-matrix.json');
const acceptancePromise = loadJson('config/harmonyos-phase10-acceptance.json');

test('final matrix covers every frozen phase-0 feature exactly once', async () => {
  const [baseline, final] = await Promise.all([baselinePromise, acceptancePromise]);
  const expected = baseline.features.map((feature) => feature.id).sort();
  const actual = final.results.map((result) => result.id).sort();
  assert.equal(new Set(actual).size, actual.length, 'duplicate final acceptance IDs');
  assert.deepEqual(actual, expected);
  assert.equal(final.summary.total, expected.length);
  assert.equal(final.summary.implementationComplete, expected.length);
});

test('every result is PASS or an actionable BLOCKED item, never untested', async () => {
  const final = await acceptancePromise;
  const counts = { PASS: 0, BLOCKED: 0 };
  for (const result of final.results) {
    assert.equal(result.implementation, 'COMPLETE', `${result.id} implementation is incomplete`);
    assert.ok(result.acceptance === 'PASS' || result.acceptance === 'BLOCKED',
      `${result.id} has invalid acceptance ${result.acceptance}`);
    counts[result.acceptance] += 1;
    assert.ok(Array.isArray(result.evidence) && result.evidence.length > 0, `${result.id} has no evidence`);
    if (result.acceptance === 'BLOCKED') {
      assert.ok(result.blocker?.trim(), `${result.id} has no blocker`);
      assert.ok(result.requiredEnvironment?.trim(), `${result.id} has no required environment`);
    }
  }
  assert.deepEqual(counts, { PASS: final.summary.pass, BLOCKED: final.summary.blocked });
  assert.equal(final.summary.untested, 0);
  assert.equal(counts.PASS + counts.BLOCKED, final.summary.total);
  assert.equal(final.summary.overall, 'BLOCKED_NO_REAL_DEVICE');
});

test('all cited evidence exists and candidate metadata keeps release gates explicit', async () => {
  const final = await acceptancePromise;
  for (const result of final.results) {
    for (const evidence of result.evidence) await access(path.join(repositoryRoot, evidence));
  }
  assert.match(final.sourceCommit, /^[0-9a-f]{40}$/);
  assert.equal(final.environment.deviceAvailable, false);
  assert.equal(final.environment.privacyDecisionAutomated, false);
  assert.deepEqual(final.candidates.map((candidate) => candidate.variant).sort(), ['replay', 'standard']);
  for (const candidate of final.candidates) {
    assert.equal(candidate.buildMode, 'release');
    assert.equal(candidate.signed, false);
    assert.ok(Number.isInteger(candidate.bytes) && candidate.bytes > 0);
    assert.match(candidate.sha256, /^[0-9a-f]{64}$/);
    assert.deepEqual(candidate.abis, ['arm64-v8a', 'x86_64']);
    assert.match(candidate.path, new RegExp(`${candidate.variant}-release-unsigned\\.hap$`));
  }
});

test('final verifier never automates privacy or media-library decisions', async () => {
  const verifier = await readFile(path.join(toolDirectory, 'verify-stage10-final.ps1'), 'utf8');
  assert.match(verifier, /verify-stage9-replay-ui\.ps1/);
  assert.match(verifier, /BLOCKED.*real HarmonyOS device/i);
  assert.doesNotMatch(verifier, /uiInput.*(?:allow|允许|保存到相册)|(?:allow|允许|保存到相册).*uiInput/i);
});
