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
const fixturePath = path.join(repositoryRoot, 'test', 'fixtures', 'harmonyos-port', 'phase0',
  'team-preview-recognition.json');

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, 'utf8'));
}

async function loadDomain() {
  const output = await build({
    entryPoints: [path.join(sourceRoot, 'ets', 'domain', 'TeamPreviewRecognition.ts')],
    bundle: true, format: 'esm', platform: 'neutral', target: 'es2021', write: false, logLevel: 'silent'
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

const domainPromise = loadDomain();

function teamFromDraft(draft, id = 'team-match') {
  return { kind: 'SavedOwnTeam', savedTeamId: id, teamName: id,
    pokemon: [...draft.own].reverse().map((slot) => ({ species: {
      entityType: 'species', canonicalId: slot.selected.canonicalId, showdownId: slot.selected.showdownId,
      displayName: slot.selected.displayName
    }, level: 50, actualStats: { hp: 150, atk: 100, def: 100, spa: 100, spd: 100, spe: 100 },
    moves: [{ move: { entityType: 'move', canonicalId: 'move.protect', showdownId: 'Protect' } }] })) };
}

test('recognition result requires six ordered slots per side and creates a twelve-slot review', async () => {
  const domain = await domainPromise;
  const fixture = await readJson(fixturePath);
  const result = domain.parseTeamPreviewRecognition(JSON.stringify(fixture));
  const draft = domain.buildTeamPreviewReview(result);
  assert.equal(draft.own.length, 6);
  assert.equal(draft.opponent.length, 6);
  assert.equal(draft.own.every((slot) => !slot.confirmed), true);
  assert.equal(draft.own[0].recognitionRisk, true);
  assert.equal(draft.own[1].recognitionRisk, false);
  assert.throws(() => domain.parseTeamPreviewRecognition(JSON.stringify({ ...fixture,
    opponentTeamCandidates: fixture.opponentTeamCandidates.slice(1) })), /six slots/);
});

test('all twelve slots need explicit confirmation and manual replacement is persisted in the session', async () => {
  const domain = await domainPromise;
  const fixture = await readJson(fixturePath);
  let draft = domain.buildTeamPreviewReview(domain.parseTeamPreviewRecognition(JSON.stringify(fixture)));
  for (const side of ['own', 'opponent']) {
    for (let slotIndex = 0; slotIndex < 6; slotIndex += 1) {
      const slot = draft[side][slotIndex];
      draft = domain.replaceTeamPreviewSlot(draft, side, slotIndex, slot.selected, true);
    }
  }
  const corrected = { entityType: 'species', canonicalId: 'species.raichu', showdownId: 'Raichu',
    displayName: '雷丘', source: 'manual' };
  draft = domain.replaceTeamPreviewSlot(draft, 'opponent', 5, corrected, true);
  assert.equal(domain.allTeamPreviewSlotsConfirmed(draft), true);
  const matching = teamFromDraft(draft);
  assert.deepEqual(domain.matchingSavedOwnTeams(draft, [matching, { ...matching, savedTeamId: 'bad', pokemon: [] }])
    .map((team) => team.savedTeamId), ['team-match']);
  const session = domain.buildBattleSession(draft, matching.savedTeamId, '2026-08-01T00:00:00Z', 7);
  assert.equal(session.sessionId, 'battle-7');
  assert.equal(session.previewCapturedAt, fixture.capturedAt);
  assert.equal(session.opponentTeam[5].showdownId, 'Raichu');
  assert.equal(session.calculationSelection.battleType, 'DOUBLE');

  let setupDraft = domain.buildTeamPreviewReview(domain.parseTeamPreviewRecognition(JSON.stringify(fixture)));
  setupDraft = domain.replaceTeamPreviewSlot(setupDraft, 'opponent', 5, corrected, true);
  const setupSession = domain.buildBattleSessionFromSetup(setupDraft, matching.savedTeamId,
    '2026-08-01T00:00:00Z', 8);
  assert.equal(setupSession.sessionId, 'battle-8');
  assert.equal(setupSession.opponentTeam[5].showdownId, 'Raichu');
});

test('formal native engine and product flow use Android V2 recognition followed by the floating setup panel', async () => {
  const [engine, cmake, bridgeTypes, service, captureCoordinator, overlayCoordinator, storage, ui, floatUi,
    overlayUi] = await Promise.all([
    readFile(path.join(sourceRoot, 'cpp', 'team_preview_engine.cpp'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'CMakeLists.txt'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'types', 'libpcbridge', 'index.d.ts'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'services', 'TeamPreviewRecognitionService.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'services', 'OwnTeamCaptureCoordinator.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'services', 'BattleOverlayCoordinator.ts'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'storage', 'AppStorageRepository.ts'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'FloatAssistant.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'BattleOverlay.ets'), 'utf8')
  ]);
  for (const marker of ['PTVFEAT2', 'COARSE_SPECIES_TOP_K = 24', 'ADAPTIVE_GRABCUT_MARGIN = 0.02',
    'cv::grabCut', 'cv::createCLAHE', 'cv::matchTemplate', 'cv::HISTCMP_CORREL', 'PerceptualHash',
    'team_preview.opponent.slot5.pokemon_icon']) assert.match(engine, new RegExp(marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.match(engine, /TARGET_ASPECT = 16\.0 \/ 9\.0/);
  assert.match(cmake, /opencv-4\.13\.0/);
  assert.match(bridgeTypes, /recognizeTeamPreview/);
  assert.match(service, /team-preview-templates-v2\.bin/);
  assert.match(captureCoordinator, /saveCurrentTeamPreview/);
  assert.match(overlayCoordinator, /buildBattleSessionFromSetup/);
  assert.match(overlayCoordinator, /showSetup/);
  assert.ok(storage.indexOf('writeUtf8Atomically(previewPath') < storage.indexOf('fileIo.unlinkSync(sessionPath)'));
  assert.match(floatUi, /float-recognize-team-preview/);
  assert.match(floatUi, /showSetup/);
  for (const id of ['battle-setup-panel', 'battle-setup-retry', 'battle-setup-confirm']) {
    assert.match(overlayUi, new RegExp(id));
  }
  assert.doesNotMatch(ui, /battle-review-team-preview/);
});

test('2772x1240 maps to the same centered 2204x1240 game viewport as Android', () => {
  const targetAspect = 16 / 9;
  const width = 2772, height = 1240;
  const viewportWidth = height * targetAspect;
  assert.equal(Math.round((width - viewportWidth) / 2), 284);
  assert.equal(Math.round(viewportWidth), 2204);
});

test('the eight team-preview album samples are intact with pinned hashes and dimensions', async () => {
  const manifest = await readJson(path.join(repositoryRoot, 'config', 'harmonyos-phase7-album-samples.json'));
  assert.equal(manifest.samples.length, 8);
  const sampleRoot = path.resolve(repositoryRoot, manifest.sourceDirectory);
  for (const sample of manifest.samples) {
    const body = await readFile(path.join(sampleRoot, sample.name));
    const metadata = await sharp(body).metadata();
    assert.equal(body.length, sample.bytes, sample.name);
    assert.equal(createHash('sha256').update(body).digest('hex'), sample.sha256, sample.name);
    assert.equal(metadata.width, manifest.expectedWidth, sample.name);
    assert.equal(metadata.height, manifest.expectedHeight, sample.name);
  }
});
