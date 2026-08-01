import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import sharp from 'sharp';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const sourceRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main');
const fixtureRoot = path.join(repositoryRoot, 'test', 'fixtures', 'harmonyos-port', 'phase0');

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, 'utf8'));
}

async function loadRecognition() {
  const output = await build({
    entryPoints: [path.join(sourceRoot, 'ets', 'domain', 'OwnTeamRecognition.ts')],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2021',
    write: false,
    logLevel: 'silent'
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

const recognitionPromise = loadRecognition();

function clone(value) {
  return structuredClone(value);
}

function entity(type, id, displayName = id) {
  return { entityType: type, canonicalId: `${type}.${id.toLowerCase()}`, showdownId: id, displayName,
    originalText: displayName, confidence: 1, source: 'ocr' };
}

function line(text, x, y) {
  return { text, points: [{ x: x - 10, y: y - 8 }, { x: x + 10, y: y - 8 },
    { x: x + 10, y: y + 8 }, { x: x - 10, y: y + 8 }], words: [] };
}

function syntheticCards(kind) {
  return Array.from({ length: 6 }, (_unused, index) => {
    const lines = [line(`Species${index}`, 100, 25)];
    if (kind === 'MOVE_ITEM') {
      lines.push(line(`Ability${index}`, 100, 75), line(`Item${index}`, 100, 135));
      for (let move = 0; move < 4; move += 1) lines.push(line(`Move${index}${move}`, 375, 25 + move * 55));
    } else {
      const positions = [[157, 68], [157, 120], [157, 172], [393, 68], [393, 120], [393, 172]];
      positions.forEach(([x, y], stat) => lines.push(line(String(100 + index * 10 + stat), x, y)));
    }
    return { width: 500, height: 220, lines };
  });
}

const resolver = {
  resolve(text, type) {
    if (type === 'species' && text.startsWith('Species')) return entity(type, text);
    if (type === 'ability' && text.startsWith('Ability')) return entity(type, text);
    if (type === 'item' && text.startsWith('Item')) return entity(type, text);
    if (type === 'move' && text.startsWith('Move')) return entity(type, text);
    return undefined;
  }
};

test('six-card parser classifies both own-team pages and keeps all slot indexes', async () => {
  const recognition = await recognitionPromise;
  const move = recognition.parseOwnTeamCards(syntheticCards('MOVE_ITEM'), resolver, 2772, 1240,
    '2026-08-01T00:00:00Z', 123);
  const stats = recognition.parseOwnTeamCards(syntheticCards('STATS'), resolver, 2772, 1240,
    '2026-08-01T00:00:01Z', 456);
  assert.equal(move.sceneType, 'OWN_TEAM_MOVE_ITEM');
  assert.equal(stats.sceneType, 'OWN_TEAM_STATS');
  assert.deepEqual(move.slots.map((slot) => slot.slotIndex), [0, 1, 2, 3, 4, 5]);
  assert.equal(move.slots.every((slot) => slot.moves.length === 4), true);
  assert.equal(stats.slots.every((slot) => Object.keys(slot.actualStats).length === 6), true);
});

test('stat candidate and digit-geometry rules remain identical to the Android recognizer', async () => {
  const recognition = await recognitionPromise;
  assert.equal(recognition.selectStatValueCandidates([182, 183, 182, undefined]), 182);
  assert.equal(recognition.selectStatValueCandidates([182, 183]), 182);
  assert.equal(recognition.selectStatValueCandidates([18, 182]), 182);
  assert.equal(recognition.correctSixNineDigitConfusions(96, [0.289, 0.292]), 99);
  assert.equal(recognition.correctSixNineDigitConfusions(69, [0.650, 0.650]), 66);
  assert.equal(recognition.correctSixNineDigitConfusions(196, [undefined, 0.289, 0.292]), 199);
  assert.equal(recognition.normalizeStatValueDigitCount(900, 2), 90);
  assert.equal(recognition.correctTwoThreeMiddleDigitConfusion(127, 0.348), 137);
  assert.equal(recognition.correctTwoThreeMiddleDigitConfusion(137, 0.478), 127);
  assert.equal(recognition.correctTwoThreeMiddleDigitConfusion(127, 0.405), 127);
  const moveRegions = recognition.entityCropRegions('move0');
  assert.equal(moveRegions.length, 2);
  assert.ok(moveRegions[1].left > moveRegions[0].left);
  assert.deepEqual(recognition.statCropHorizontalRanges({ left: 0.24, right: 0.39, top: 0.21, bottom: 0.41 })
    .map((region) => [region.left, region.right]), [[0.24, 0.39], [0.23, 0.415]]);
  assert.deepEqual(recognition.statCropHorizontalRanges({ left: 0.71, right: 0.86, top: 0.21, bottom: 0.41 })
    .map((region) => [region.left, region.right]), [[0.71, 0.86], [0.70, 0.885]]);
});

test('page sequencing restarts on a new move page and reaches correction only after the matching stats page', async () => {
  const recognition = await recognitionPromise;
  const fixture = await readJson(path.join(fixtureRoot, 'own-team-import-draft.json'));
  const firstStats = recognition.acceptOwnTeamPage(undefined, fixture.statsPage);
  assert.equal(firstStats.nextStep, 'CAPTURE_MOVE_ITEM');
  const moveAfterStats = recognition.acceptOwnTeamPage(firstStats.draft, fixture.moveItemPage);
  assert.equal(moveAfterStats.nextStep, 'CAPTURE_STATS');
  assert.equal(moveAfterStats.restarted, true);
  assert.equal(moveAfterStats.draft.statsPage, undefined);
  const completed = recognition.acceptOwnTeamPage(moveAfterStats.draft, fixture.statsPage);
  assert.equal(completed.nextStep, 'MANUAL_CORRECTION');
  assert.equal(completed.draft.moveItemPage.image.width, 2772);
});

test('manual correction enforces six complete slots while preserving the Ditto one-move exception only', async () => {
  const recognition = await recognitionPromise;
  const fixture = await readJson(path.join(fixtureRoot, 'own-team-import-draft.json'));
  const correction = recognition.buildOwnTeamCorrectionDraft(fixture);
  assert.equal(recognition.requiredOwnTeamMoveCount(correction.slots[0].species), 1);
  assert.equal(recognition.requiredOwnTeamMoveCount(correction.slots[1].species), 4);
  assert.equal(recognition.unresolvedOwnTeamFields(correction.slots[0]).length, 0);
  assert.match(recognition.unresolvedOwnTeamFields(correction.slots[2]).join('|'), /道具/);
  correction.slots[2].itemResolved = true;
  assert.equal(recognition.ownTeamCorrectionComplete(correction.slots), true);
  const team = recognition.buildSavedOwnTeam('相册验收队', correction, 12345);
  assert.equal(team.pokemon.length, 6);
  assert.equal(team.pokemon[0].moves.length, 1);
  assert.equal(team.pokemon[1].moves.length, 4);
  assert.equal(team.pokemon[2].item, undefined);
  assert.throws(() => recognition.buildSavedOwnTeam(' ', correction), /1–30/);

  const broadened = clone(correction);
  broadened.slots[1].moves = broadened.slots[1].moves.slice(0, 1);
  assert.equal(recognition.ownTeamCorrectionComplete(broadened.slots), false);
});

test('native capture, CoreVision, floating entry, correction UI and unified release are wired into the formal product', async () => {
  const [nativeSource, cmake, moduleProfile, service, coordinator, ui, floatPage, bridgeTypes] = await Promise.all([
    readFile(path.join(sourceRoot, 'cpp', 'napi_init.cpp'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'CMakeLists.txt'), 'utf8'),
    readJson(path.join(sourceRoot, 'module.json5')),
    readFile(path.join(sourceRoot, 'ets', 'services', 'OwnTeamRecognitionService.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'services', 'OwnTeamCaptureCoordinator.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'FloatAssistant.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'types', 'libpcbridge', 'index.d.ts'), 'utf8')
  ]);
  assert.match(nativeSource, /OH_AVScreenCapture_StartScreenCapture/);
  assert.match(nativeSource, /StrategyForCanvasFollowRotation/);
  assert.match(nativeSource, /IsVisibleFrame/);
  assert.match(nativeSource, /validStreak/);
  assert.match(nativeSource, /latestFrame\.swap/);
  assert.match(nativeSource, /DetectTeamCards/);
  assert.match(cmake, /libnative_avscreen_capture\.so/);
  assert.deepEqual(moduleProfile.module.requestPermissions.map((entry) => entry.name).sort(),
    ['ohos.permission.INTERNET', 'ohos.permission.SYSTEM_FLOAT_WINDOW'].sort());
  assert.match(service, /textRecognition\.recognizeText/);
  assert.match(service, /recognizeEntityCrop/);
  assert.match(service, /thresholdForDigits/);
  assert.match(service, /correctCommonDigitConfusions/);
  assert.match(service, /pixelMap\.scale\(scale/);
  assert.match(coordinator, /saveOwnTeamImportDraft/);
  assert.match(coordinator, /minimize\(\)/);
  assert.match(coordinator, /this\.capture\.stop\(\)/);
  assert.match(ui, /['"]battle-start-assistant['"]/);
  assert.match(ui, /['"]own-team-correction-page['"]/);
  assert.match(ui, /['"]own-team-save['"]/);
  assert.match(floatPage, /录入我的队伍/);
  assert.match(bridgeTypes, /takeLatestFrame/);
});

test('the exact six album groups are intact at 2772x1240 with pinned hashes', async () => {
  const manifest = await readJson(path.join(repositoryRoot, 'config', 'harmonyos-phase6-album-samples.json'));
  assert.equal(manifest.groups.length, 6);
  const sampleRoot = path.resolve(repositoryRoot, manifest.sourceDirectory);
  const samples = manifest.groups.flatMap((group) => [group.moveItem, group.stats]);
  assert.equal(samples.length, 12);
  for (const sample of samples) {
    const body = await readFile(path.join(sampleRoot, sample.name));
    const metadata = await sharp(body).metadata();
    assert.equal(body.length, sample.bytes, sample.name);
    assert.equal(createHash('sha256').update(body).digest('hex'), sample.sha256, sample.name);
    assert.equal(metadata.width, manifest.expectedWidth, sample.name);
    assert.equal(metadata.height, manifest.expectedHeight, sample.name);
  }
});
