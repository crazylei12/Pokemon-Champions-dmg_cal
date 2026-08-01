import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const fixtureRoot = path.join(repositoryRoot, 'test', 'fixtures', 'harmonyos-port', 'phase0');

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, 'utf8'));
}

async function loadStorageContracts() {
  const output = await build({
    entryPoints: [path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'ets', 'storage', 'index.ts')],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2021',
    write: false,
    logLevel: 'silent'
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

function clone(value) {
  return structuredClone(value);
}

const contractsPromise = loadStorageContracts();

test('Android full-backup golden fixture validates without changing its cross-platform contract', async () => {
  const contracts = await contractsPromise;
  const fixture = await readJson(path.join(fixtureRoot, 'app-backup.json'));
  const validated = contracts.validateAppBackupJson(JSON.stringify(fixture));
  assert.equal(validated.hasUserOpponentPresets, true);
  assert.equal(validated.envelope.data.savedTeams.length, 1);
  assert.equal(validated.envelope.data.savedTeams[0].pokemon.length, 6);
  assert.equal(validated.envelope.data.currentBattleSession.selectedOwnTeamId, 'harmony-port-phase0-team');
  assert.equal(validated.envelope.data.userOpponentPresets.presets[0].preset.profileId,
    'user.phase0-charizard');
  assert.equal(validated.envelope.data.updateChannel, 'stable');

  const rebuilt = contracts.buildAppBackupEnvelope({
    savedTeams: validated.envelope.data.savedTeams,
    currentBattleSession: validated.envelope.data.currentBattleSession,
    currentTeamPreview: validated.envelope.data.currentTeamPreview,
    pendingOwnTeam: validated.envelope.data.pendingOwnTeam,
    ownTeamImportDraft: validated.envelope.data.ownTeamImportDraft,
    userOpponentPresets: validated.envelope.data.userOpponentPresets,
    updateChannel: validated.envelope.data.updateChannel
  }, fixture.exportedAt, fixture.appVersion);
  assert.deepEqual(rebuilt, validated.envelope);
});

test('system backup extension is registered and constrained to the explicit product-data allowlist', async () => {
  const moduleProfile = JSON.parse(await readFile(path.join(repositoryRoot,
    'harmonyos/app/entry/src/main/module.json5'), 'utf8'));
  const backupProfile = JSON.parse(await readFile(path.join(repositoryRoot,
    'harmonyos/app/entry/src/main/resources/base/profile/backup_config.json'), 'utf8'));
  const repositorySource = await readFile(path.join(repositoryRoot,
    'harmonyos/app/entry/src/main/ets/storage/AppStorageRepository.ets'), 'utf8');
  const extension = moduleProfile.module.extensionAbilities.find((entry) => entry.type === 'backup');
  assert.equal(extension.name, 'EntryBackupAbility');
  assert.equal(extension.exported, false);
  assert.deepEqual(extension.metadata, [{ name: 'ohos.extension.backup', resource: '$profile:backup_config' }]);
  assert.equal(backupProfile.allowToBackupRestore, true);

  const allowlistBody = repositorySource.match(/SYSTEM_BACKUP_ALLOWLIST:[^=]+\= \[([\s\S]*?)\];/)?.[1] ?? '';
  for (const expected of [
    'saved-teams', 'battle-session', 'pending-own-team.json', 'own-team-import-draft.json',
    'USER_PRESETS_FILE', 'SETTINGS_FILE', 'HUD_LAYOUTS_FILE'
  ]) {
    assert.match(allowlistBody, new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  assert.doesNotMatch(allowlistBody, /screenshot|cache|temp|auth|token|frame|replay/i);
});

test('Android opponent-preset share merges in save order and repeated import is idempotent', async () => {
  const contracts = await contractsPromise;
  const share = await readJson(path.join(fixtureRoot, 'opponent-preset-share.json'));
  const incoming = contracts.parseOpponentPresetShareJson(JSON.stringify(share));
  incoming.presets.push({
    speciesId: 'gengar',
    preset: {
      ...clone(incoming.presets[0].preset),
      profileId: 'user.phase4-new',
      profileName: '新导入'
    }
  });
  const local = contracts.validateUserOpponentPresetRoot({
    schemaVersion: 1,
    kind: 'OpponentUserPresets',
    presets: [
      {
        speciesId: 'charizard',
        preset: { ...clone(incoming.presets[0].preset), profileName: '本机旧名称' }
      },
      {
        speciesId: 'eevee',
        preset: {
          ...clone(incoming.presets[0].preset),
          profileId: 'user.local-only',
          profileName: '只在本机'
        }
      }
    ]
  });
  const first = contracts.mergeUserOpponentPresetRoots(local, incoming);
  const second = contracts.mergeUserOpponentPresetRoots(first.root, incoming);
  assert.deepEqual(first.summary, { imported: 2, added: 1, updated: 1, unchanged: 0 });
  assert.deepEqual(second.summary, { imported: 2, added: 0, updated: 0, unchanged: 2 });
  assert.deepEqual(first.root.presets.map((entry) => entry.preset.profileName),
    ['高速输出', '只在本机', '新导入']);
});

test('legacy backup preserves local presets while an explicit empty field clears them', async () => {
  const contracts = await contractsPromise;
  const fixture = await readJson(path.join(fixtureRoot, 'app-backup.json'));
  const localPresets = clone(fixture.data.userOpponentPresets);
  const current = {
    savedTeams: [],
    userOpponentPresets: localPresets,
    updateChannel: 'preview'
  };

  const legacy = clone(fixture);
  delete legacy.data.userOpponentPresets;
  const legacyState = contracts.restoreStateFromValidatedBackup(current,
    contracts.validateAppBackupJson(JSON.stringify(legacy)));
  assert.equal(legacyState.userOpponentPresets.presets.length, 1);

  const explicit = clone(fixture);
  explicit.data.userOpponentPresets = contracts.emptyUserOpponentPresetRoot();
  const explicitState = contracts.restoreStateFromValidatedBackup(current,
    contracts.validateAppBackupJson(JSON.stringify(explicit)));
  assert.equal(explicitState.userOpponentPresets.presets.length, 0);
});

test('malformed backups are rejected before they can replace current state', async () => {
  const contracts = await contractsPromise;
  const source = await readJson(path.join(fixtureRoot, 'app-backup.json'));
  const cases = [];

  const duplicate = clone(source);
  duplicate.data.savedTeams.push(clone(duplicate.data.savedTeams[0]));
  cases.push(duplicate);

  const unsafeId = clone(source);
  unsafeId.data.savedTeams[0].savedTeamId = '../escape';
  cases.push(unsafeId);

  const fivePokemon = clone(source);
  fivePokemon.data.savedTeams[0].pokemon.pop();
  cases.push(fivePokemon);

  const missingReference = clone(source);
  missingReference.data.currentBattleSession.selectedOwnTeamId = 'missing-team';
  cases.push(missingReference);

  const fiveOpponents = clone(source);
  fiveOpponents.data.currentBattleSession.opponentTeam.pop();
  cases.push(fiveOpponents);

  const malformedPresets = clone(source);
  malformedPresets.data.userOpponentPresets = null;
  cases.push(malformedPresets);

  for (const malformed of cases) {
    assert.throws(() => contracts.validateAppBackupJson(JSON.stringify(malformed)));
  }
  assert.throws(() => contracts.validateAppBackupJson(' '.repeat(contracts.APP_BACKUP_MAX_BYTES + 1)),
    /16 MB/);
});

test('share kind, schema, preset limits, and 4 MB boundary are enforced', async () => {
  const contracts = await contractsPromise;
  const share = await readJson(path.join(fixtureRoot, 'opponent-preset-share.json'));
  const wrongKind = clone(share);
  wrongKind.kind = 'PokemonChampionsAssistantBackup';
  assert.throws(() => contracts.parseOpponentPresetShareJson(JSON.stringify(wrongKind)), /不是宝可梦配置分享文件/);

  const duplicate = clone(share);
  duplicate.userOpponentPresets.presets.push(clone(duplicate.userOpponentPresets.presets[0]));
  assert.throws(() => contracts.parseOpponentPresetShareJson(JSON.stringify(duplicate)), /ID 重复/);
  assert.throws(() => contracts.parseOpponentPresetShareJson(' '.repeat(contracts.PRESET_SHARE_MAX_BYTES + 1)),
    /4 MB/);
});

test('deleting a referenced opponent preset removes slot and manual-override references', async () => {
  const contracts = await contractsPromise;
  const cleaned = contracts.removeOpponentPresetReferences({
    selectedPresetId: 'user.deleted',
    opponentPresetIds: { '0': 'user.deleted', '1': 'user.keep' },
    opponentManualOverrides: {
      '0': { baseProfileId: 'user.deleted' },
      '1': { baseProfileId: 'user.keep' },
      '2': { baseProfileId: 'user.deleted' }
    }
  }, 'user.deleted');
  assert.equal(cleaned.selectedPresetId, undefined);
  assert.deepEqual(cleaned.opponentPresetIds, { '1': 'user.keep' });
  assert.deepEqual(cleaned.opponentManualOverrides, { '1': { baseProfileId: 'user.keep' } });
});

test('settings and HUD stores normalize channels and discard invalid placements', async () => {
  const contracts = await contractsPromise;
  assert.deepEqual(contracts.validateAppSettings({
    schemaVersion: 1,
    kind: 'AppSettings',
    updateChannel: 'nightly'
  }), { schemaVersion: 1, kind: 'AppSettings', updateChannel: 'stable' });
  const layouts = contracts.validateHudLayouts({
    schemaVersion: 1,
    kind: 'BattleDirectHudLayouts',
    landscape: {
      elements: {
        DAMAGE: { x: 0.1, y: 0.2, width: 0.4, height: 0.3 },
        INVALID: { x: 0, y: 0, width: 0, height: 0.3 }
      }
    }
  });
  assert.deepEqual(Object.keys(layouts.landscape.elements), ['DAMAGE']);
});
