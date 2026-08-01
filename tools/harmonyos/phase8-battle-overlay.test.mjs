import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const sourceRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main');

async function loadDomain() {
  const output = await build({
    entryPoints: [path.join(sourceRoot, 'ets', 'domain', 'BattleSession.ts')],
    bundle: true, format: 'esm', platform: 'neutral', target: 'es2021', write: false, logLevel: 'silent'
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

const domainPromise = loadDomain();

function entity(entityType, showdownId, displayName = showdownId) {
  return { entityType, canonicalId: `${entityType}.${showdownId.toLowerCase()}`, showdownId, displayName };
}

function move(showdownId, priority = 0) {
  return { entity: entity('move', showdownId), priority };
}

test('battle state preserves per-slot conditions and normalizes double-HUD selections', async () => {
  const domain = await domainPromise;
  const state = domain.normalizeBattleCalculation({
    ownSlot: 8, opponentSlot: -2, battleType: 'DOUBLE', spread: true,
    ownConditions: { 0: { burned: true, stages: { atk: -9, def: 1, spa: 0, spd: 0, spe: 9 } }, 8: {} },
    speedLine: { trickRoom: true, ownPokemon: { 1: { stage: 8, paralyzed: true, doubled: false } } },
    directHud: { ownSlots: [2, 2], opponentSlots: [5, 4], visible: false }
  }, 6, 6);
  assert.equal(state.ownSlot, 5);
  assert.equal(state.opponentSlot, 0);
  assert.deepEqual(state.directHud.ownSlots, [2, 0]);
  assert.deepEqual(state.directHud.opponentSlots, [5, 4]);
  assert.equal(state.directHud.visible, false);
  assert.equal(domain.battleCondition(state, 'OWN', 0).burned, true);
  assert.equal(domain.battleCondition(state, 'OWN', 0).stages.atk, -6);
  assert.equal(domain.battleCondition(state, 'OWN', 0).stages.spe, 6);
  assert.equal(domain.speedModifiers(state, 'OWN', 1).stage, 6);
});

test('single and double transitions keep Android battle defaults and distinct HUD slots', async () => {
  const domain = await domainPromise;
  const base = domain.normalizeBattleCalculation({ battleType: 'DOUBLE', helpingHand: true,
    directHud: { ownSlots: [0, 1], opponentSlots: [0, 1], visible: true } }, 6, 6);
  const single = domain.withBattleCalculationTypeDefaults(base, 'SINGLE');
  assert.equal(single.spread, false);
  assert.equal(single.helpingHand, false);
  const double = domain.withBattleCalculationTypeDefaults(single, 'DOUBLE');
  assert.equal(double.spread, true);
  assert.deepEqual(domain.replaceBattleDirectHudSlot([0, 1], 0, 1, 6), [1, 0]);
  assert.deepEqual(domain.includeBattleDirectHudSlot([0, 1], 4, 6), [4, 0]);
  assert.equal(domain.battleDirectHudSlotsPerSide('SINGLE'), 1);
  assert.equal(domain.battleDirectHudSlotsPerSide('DOUBLE'), 2);
});

test('speed line applies stages, paralysis, scarf, tailwind, priority and trick room', async () => {
  const domain = await domainPromise;
  assert.equal(domain.effectiveSpeed(100, { stage: 1, paralyzed: true, doubled: false, choiceScarf: true }, true), 225);
  const inputs = [
    { side: 'OWN', slot: 0, name: 'Fast', baseSpeed: { minimum: 120, maximum: 120 },
      modifiers: { stage: 0, paralyzed: false, doubled: false }, tailwind: false,
      knownChoiceScarf: false, priorityMoves: [move('QuickAttack', 1)], exactBaseSpeed: true },
    { side: 'OPPONENT', slot: 0, name: 'Slow', baseSpeed: { minimum: 60, maximum: 80 },
      modifiers: { stage: 0, paralyzed: false, doubled: false }, tailwind: false,
      knownChoiceScarf: false, priorityMoves: [], exactBaseSpeed: false }
  ];
  const normal = domain.buildSpeedLineActions(inputs, false);
  assert.equal(normal[0].moveName, 'QuickAttack');
  assert.equal(normal[1].pokemonName, 'Fast');
  const trickRoom = domain.buildSpeedLineActions(inputs, true);
  assert.equal(trickRoom[1].pokemonName, 'Slow');
  assert.equal(trickRoom[2].pokemonName, 'Fast');
});

test('portrait and landscape layout profiles scale and clamp inside safe bounds', async () => {
  const domain = await domainPromise;
  const landscape = { left: 20, top: 40, right: 1220, bottom: 680 };
  const placement = domain.placementFromBounds(landscape, { left: 980, top: 500, right: 1200, bottom: 660 });
  assert.equal(domain.battleDirectHudLayoutProfileKey(landscape), 'landscape');
  assert.equal(domain.battleDirectHudLayoutProfileKey({ left: 0, top: 0, right: 600, bottom: 1000 }), 'portrait');
  const resolved = domain.resolvePlacement(landscape, { ...placement, x: 2, y: 2, width: 0.4, height: 0.5 }, 320, 220);
  assert.deepEqual(resolved, { left: 740, top: 360, right: 1220, bottom: 680 });
});

test('damage request carries live conditions and all four configured moves with a stable cache key', async () => {
  const domain = await domainPromise;
  let state = domain.normalizeBattleCalculation({ battleType: 'DOUBLE', direction: 'OWN_TO_OPPONENT',
    ownSlot: 0, opponentSlot: 0, helpingHand: true, critical: true, spread: true,
    opponentReflect: true, opponentProtected: true }, 6, 6);
  state = domain.withBattleCondition(state, 'OWN', { burned: true,
    stages: { atk: 2, def: 0, spa: -1, spd: 0, spe: 0 } });
  state = domain.withBattleCondition(state, 'OPPONENT', { burned: false,
    stages: { atk: 0, def: -2, spa: 0, spd: 1, spe: 0 } });
  const own = { species: entity('species', 'Pikachu'), level: 50,
    actualStats: { hp: 120, atk: 90, def: 70, spa: 100, spd: 80, spe: 110 },
    moves: [move('Thunderbolt'), move('Protect'), move('VoltTackle'), move('FakeOut')] };
  const preset = { profileId: 'preset-1', profileName: 'Default', source: 'CANONICAL', level: 50,
    statPoints: { hp: 32, atk: 0, def: 0, spa: 0, spd: 0, spe: 32 }, moves: [move('Protect')] };
  const first = domain.buildBattleDamageRequest({ own, opponent: entity('species', 'Azumarill'), preset,
    legalMoves: preset.moves, calculation: state, allOwnMoves: true, requestId: 'one' });
  const second = domain.buildBattleDamageRequest({ own, opponent: entity('species', 'Azumarill'), preset,
    legalMoves: preset.moves, calculation: state, allOwnMoves: true, requestId: 'two' });
  const parsed = JSON.parse(first);
  assert.equal(parsed.calculationMode, 'TEMPLATE');
  assert.equal(parsed.moveSelection.mode, 'ALL_ATTACKER_MOVES');
  assert.equal(parsed.attacker.moves.length, 4);
  assert.equal(parsed.attacker.status, 'brn');
  assert.equal(parsed.attacker.statStages.atk, 2);
  assert.equal(parsed.defenderProfileSet.profiles[0].statStages.def, -2);
  assert.deepEqual(parsed.battle.attackerSideConditions, { helpingHand: true });
  assert.deepEqual(parsed.battle.defenderSideConditions,
    { reflect: true, lightScreen: false, auroraVeil: false, protected: true });
  assert.equal(domain.battleDamageCacheKey(first), domain.battleDamageCacheKey(second));
});

test('product routes expose the dark panel and Android-parity distributed HUD without debug vocabulary', async () => {
  const [page, hudElement, coordinator, index, floatAssistant, ability, pages, storage] = await Promise.all([
    readFile(path.join(sourceRoot, 'ets', 'pages', 'BattleOverlay.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'BattleHudElement.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'services', 'BattleOverlayCoordinator.ts'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'FloatAssistant.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'entryability', 'EntryAbility.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'resources', 'base', 'profile', 'main_pages.json'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'storage', 'AppStorageRepository.ts'), 'utf8')
  ]);
  for (const marker of ['battle-overlay-panel', '我方输出', '我方承伤', '战场状态', '速度线', '对手配置']) {
    assert.match(page, new RegExp(marker));
  }
  for (const marker of ['battle-hud-', '隐藏 HUD', '显示 HUD', '再战', '识别我方',
    '详细', '单打', '双打']) assert.match(hudElement, new RegExp(marker));
  assert.match(hudElement, /prepareDamage\(true\)/);
  for (const marker of ['TYPE_FLOAT', 'saveHudLayouts', 'opponentFormOverrides', 'opponentManualOverrides',
    'minimize', 'reveal', 'pages/BattleHudDamage', 'pages/BattleHudSpeed', 'pages/BattleHudFormat',
    "display\\.on\\('change'", 'DISPLAY_REFLOW_SETTLE_DELAY_MS', 'reflowOpenWindowsForCurrentDisplay']) {
    assert.match(coordinator, new RegExp(marker));
  }
  assert.match(index, /启动对局助手/);
  assert.match(index, /启动对局助手（HUD版）/);
  assert.match(floatAssistant, /显示对战 HUD/);
  assert.match(ability, /stage8Verification/);
  assert.match(pages, /pages\/BattleOverlay/);
  assert.match(pages, /pages\/BattleHudDamage/);
  assert.match(pages, /pages\/BattleHudDetail/);
  assert.match(storage, /clearCurrentBattleSession/);
  assert.doesNotMatch(page, /\b(?:ROI|OCR|Top-3|debug)\b/i);
  assert.doesNotMatch(hudElement, /\b(?:ROI|OCR|Top-3|debug)\b/i);
});
