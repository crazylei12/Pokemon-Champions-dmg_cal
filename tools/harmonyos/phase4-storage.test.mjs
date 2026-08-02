import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rename, rm, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
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

async function loadStorageRepository() {
  const output = await build({
    entryPoints: [path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'ets', 'storage',
      'AppStorageRepository.ts')],
    bundle: true,
    format: 'esm',
    platform: 'node',
    target: 'es2021',
    write: false,
    logLevel: 'silent',
    plugins: [{
      name: 'harmony-file-io-node-stub',
      setup(buildApi) {
        buildApi.onResolve({ filter: /^@kit\.CoreFileKit$/ }, () => ({ path: 'core-file-kit', namespace: 'test' }));
        buildApi.onLoad({ filter: /.*/, namespace: 'test' }, () => ({
          loader: 'js',
          contents: `
            import fs from 'node:fs';
            export const fileIo = {
              OpenMode: { WRITE_ONLY: 1, CREATE: 2, TRUNC: 4, SYNC: 8 },
              accessSync(filePath) { return fs.existsSync(filePath); },
              mkdirSync(filePath, recursive) { fs.mkdirSync(filePath, { recursive }); },
              openSync(filePath) {
                if (globalThis.__harmonyFileIoSensitiveOpenFailure) {
                  throw new Error('EACCES ' + filePath + ' token=TOKEN_secret team=Pikachu,Ditto');
                }
                return { fd: fs.openSync(filePath, 'w') };
              },
              writeSync(fd, body) { return fs.writeSync(fd, body, null, 'utf8'); },
              fsyncSync(fd) { fs.fsyncSync(fd); },
              closeSync(file) { fs.closeSync(typeof file === 'number' ? file : file.fd); },
              moveFileSync(source, target) {
                const sourceIsStage = source.includes('.app-storage-stage-');
                const targetIsStage = target.includes('.app-storage-stage-');
                const transactionMove = source.includes('.app-storage-backup-') ||
                  target.includes('.app-storage-backup-') || sourceIsStage !== targetIsStage;
                if (transactionMove) {
                  globalThis.__harmonyFileIoTransactionMoveCount =
                    (globalThis.__harmonyFileIoTransactionMoveCount ?? 0) + 1;
                  if (globalThis.__harmonyFileIoFailTransactionMoveAt ===
                    globalThis.__harmonyFileIoTransactionMoveCount) throw new Error('Injected commit rename failure');
                }
                fs.renameSync(source, target);
              },
              unlinkSync(filePath) { fs.unlinkSync(filePath); },
              statSync(filePath) { return fs.statSync(filePath); },
              listFileSync(filePath) { return fs.readdirSync(filePath); },
              rmdirSync(filePath) { fs.rmdirSync(filePath); },
              readTextSync(filePath, options) { return fs.readFileSync(filePath, options?.encoding ?? 'utf8'); },
              copyFileSync(source, target) { fs.copyFileSync(source, target); }
            };
          `
        }));
      }
    }]
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

function clone(value) {
  return structuredClone(value);
}

const contractsPromise = loadStorageContracts();
const repositoryPromise = loadStorageRepository();

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

test('enriched Android team backup preserves every current semantic field exactly', async () => {
  const contracts = await contractsPromise;
  const fixture = await readJson(path.join(fixtureRoot, 'app-backup.json'));
  const team = fixture.data.savedTeams[0];
  Object.assign(team, {
    status: 'DAMAGE_READY',
    importStatus: 'DAMAGE_READY',
    importSource: 'SCREENSHOT_MANUAL_CORRECTION',
    generatedAt: '2026-08-01T00:00:00Z',
    createdAt: '2026-08-01T00:00:01Z',
    updatedAt: '2026-08-01T00:00:02Z',
    source: { kind: 'OwnTeamRecognition', recognized: 41, total: 42, manuallyCorrected: true },
    warnings: ['manual-correction-retained']
  });
  team.pokemon.forEach((pokemon, slotIndex) => {
    pokemon.slotIndex = slotIndex;
    pokemon.warnings = slotIndex === 0 ? ['ability-confirmed'] : [];
    pokemon.moves.forEach((move, moveIndex) => {
      move.basePower = moveIndex === 0 ? 40 : 0;
      move.type = moveIndex === 0 ? 'Normal' : 'Status';
      move.priority = moveIndex === 0 ? 1 : 0;
    });
  });
  team.members = structuredClone(team.pokemon);

  const validated = contracts.validateAppBackupJson(JSON.stringify(fixture));
  const rebuilt = contracts.buildAppBackupEnvelope(validated.envelope.data,
    fixture.exportedAt, fixture.appVersion);
  assert.deepEqual(rebuilt, fixture);
  assert.deepEqual(rebuilt.data.savedTeams[0].members, rebuilt.data.savedTeams[0].pokemon);
  assert.equal(rebuilt.data.savedTeams[0].pokemon[0].moves[0].priority, 1);
  assert.deepEqual(rebuilt.data.savedTeams[0].source,
    { kind: 'OwnTeamRecognition', recognized: 41, total: 42, manuallyCorrected: true });
});

test('system backup extension is registered and constrained to the explicit product-data allowlist', async () => {
  const moduleProfile = JSON.parse(await readFile(path.join(repositoryRoot,
    'harmonyos/app/entry/src/main/module.json5'), 'utf8'));
  const backupProfile = JSON.parse(await readFile(path.join(repositoryRoot,
    'harmonyos/app/entry/src/main/resources/base/profile/backup_config.json'), 'utf8'));
  const repositorySource = await readFile(path.join(repositoryRoot,
    'harmonyos/app/entry/src/main/ets/storage/AppStorageRepository.ts'), 'utf8');
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

  const duplicateMove = clone(source);
  duplicateMove.data.savedTeams[0].pokemon[0].moves[1] = clone(duplicateMove.data.savedTeams[0].pokemon[0].moves[0]);
  cases.push(duplicateMove);

  const fractionalStat = clone(source);
  fractionalStat.data.savedTeams[0].pokemon[0].actualStats.hp = 100.5;
  cases.push(fractionalStat);

  const invalidPriority = clone(source);
  invalidPriority.data.savedTeams[0].pokemon[0].moves[0].priority = 8;
  cases.push(invalidPriority);

  for (const malformed of cases) {
    assert.throws(() => contracts.validateAppBackupJson(JSON.stringify(malformed)));
  }
  assert.throws(() => contracts.validateAppBackupJson(' '.repeat(contracts.APP_BACKUP_MAX_BYTES + 1)),
    /16 MB/);
  assert.throws(() => contracts.validateAppBackupJson(`${'['.repeat(65)}${']'.repeat(65)}`), /嵌套层级/);
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

test('settings and HUD stores normalize channels, migrate the moved button and discard invalid placements', async () => {
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
        OWN_RECOGNITION: { x: 0.42, y: 0.09, width: 0.1, height: 0.05 },
        INVALID: { x: 0, y: 0, width: 0, height: 0.3 }
      }
    }
  });
  assert.equal(layouts.schemaVersion, 2);
  assert.deepEqual(Object.keys(layouts.landscape.elements), ['DAMAGE']);
  const current = contracts.validateHudLayouts({
    schemaVersion: 2,
    kind: 'BattleDirectHudLayouts',
    landscape: { elements: { OWN_RECOGNITION: { x: 0.75, y: 0.015, width: 0.1, height: 0.05 } } }
  });
  assert.deepEqual(Object.keys(current.landscape.elements), ['OWN_RECOGNITION']);
});

test('discarding an own-team import deletes both temporary files and preserves unrelated data', async () => {
  const { AppStorageRepository } = await repositoryPromise;
  const directory = await mkdtemp(path.join(tmpdir(), 'harmony-own-team-discard-'));
  try {
    const repository = new AppStorageRepository(directory.replaceAll('\\', '/'));
    repository.saveOwnTeamImportDraft({ schemaVersion: 1, kind: 'OwnTeamImportDraft' });
    await writeFile(path.join(directory, 'pending-own-team.json'), JSON.stringify({ savedTeamId: 'pending' }), 'utf8');
    await writeFile(path.join(directory, 'unrelated.json'), 'preserve-me', 'utf8');

    repository.discardOwnTeamImport();

    const state = repository.loadManagedState();
    assert.equal(state.ownTeamImportDraft, undefined);
    assert.equal(state.pendingOwnTeam, undefined);
    assert.equal(await readFile(path.join(directory, 'unrelated.json'), 'utf8'), 'preserve-me');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('full restore stages and validates every byte before commit so an injected persistent write failure preserves live data', async () => {
  const { AppStorageRepository } = await repositoryPromise;
  const fixture = await readJson(path.join(fixtureRoot, 'app-backup.json'));
  const directory = await mkdtemp(path.join(tmpdir(), 'harmony-storage-'));
  try {
    const repository = new AppStorageRepository(directory.replaceAll('\\', '/'));
    repository.restoreAppBackupJson(JSON.stringify(fixture));
    const teamPath = path.join(directory, 'saved-teams', `${fixture.data.savedTeams[0].savedTeamId}.json`);
    const sessionPath = path.join(directory, 'battle-session', 'current-battle-session.json');
    const beforeTeam = await readFile(teamPath, 'utf8');
    const beforeSession = await readFile(sessionPath, 'utf8');

    const incoming = clone(fixture);
    incoming.data.savedTeams[0].teamName = '不应部分写入';
    incoming.data.savedTeams[0].teamSlotName = '不应部分写入';
    incoming.data.currentBattleSession.sessionId = 'battle-incoming';
    assert.throws(() => repository.restoreAppBackupJsonWithInjectedFailureForVerification(
      JSON.stringify(incoming), 1), /Injected restore failure/);
    assert.equal(await readFile(teamPath, 'utf8'), beforeTeam);
    assert.equal(await readFile(sessionPath, 'utf8'), beforeSession);
    assert.equal(repository.readRawForVerification('.app-storage-transaction.json'), undefined);

    globalThis.__harmonyFileIoTransactionMoveCount = 0;
    globalThis.__harmonyFileIoFailTransactionMoveAt = 3;
    assert.throws(() => repository.restoreAppBackupJson(JSON.stringify(incoming)), /Injected commit rename failure/);
    delete globalThis.__harmonyFileIoFailTransactionMoveAt;
    assert.equal(await readFile(teamPath, 'utf8'), beforeTeam);
    assert.equal(await readFile(sessionPath, 'utf8'), beforeSession);
    assert.equal(repository.readRawForVerification('.app-storage-transaction.json'), undefined);

    const corruptPresets = '{"schemaVersion":1,"kind":"OpponentUserPresets","presets":[';
    repository.writeRawForVerification('user-opponent-presets.json', corruptPresets);
    const legacyIncoming = clone(fixture);
    delete legacyIncoming.data.userOpponentPresets;
    repository.restoreAppBackupJson(JSON.stringify(legacyIncoming));
    assert.equal(repository.readRawForVerification('user-opponent-presets.json'), corruptPresets);
    assert.throws(() => repository.saveUserOpponentPresets({
      schemaVersion: 1, kind: 'OpponentUserPresets', presets: []
    }), /停止写入.*保留原文件副本并重置/);
    assert.throws(() => repository.exportOpponentPresetShareJson('2026-08-01T00:00:00Z', '1.1.4'),
      /停止写入.*禁止导出/);
    const recoveryCopy = repository.preserveCorruptedUserPresetsAndReset(1754006400000);
    assert.equal(recoveryCopy, 'user-opponent-presets.corrupt-1754006400000.json');
    assert.equal(repository.readRawForVerification(recoveryCopy), corruptPresets);
    assert.deepEqual(repository.loadUserOpponentPresets(), {
      root: { schemaVersion: 1, kind: 'OpponentUserPresets', presets: [] }
    });
    assert.throws(() => repository.preserveCorruptedUserPresetsAndReset(1754006400000), /没有检测到损坏/);

    const summary = repository.restoreAppBackupJson(JSON.stringify(incoming));
    assert.deepEqual(summary, { teamCount: 1, hasBattleSession: true, userOpponentPresetCount: 1 });
    assert.equal(JSON.parse(await readFile(teamPath, 'utf8')).teamName, '不应部分写入');
    assert.equal(JSON.parse(await readFile(sessionPath, 'utf8')).sessionId, 'battle-incoming');

    const unrelatedTeam = clone(incoming.data.savedTeams[0]);
    unrelatedTeam.savedTeamId = 'unrelated-team';
    unrelatedTeam.teamName = '非当前队伍';
    unrelatedTeam.teamSlotName = '非当前队伍';
    repository.saveTeam(unrelatedTeam);
    repository.deleteTeam(unrelatedTeam.savedTeamId);
    assert.equal(repository.loadManagedState().currentBattleSession?.sessionId, 'battle-incoming');
    assert.deepEqual(repository.loadManagedState().savedTeams.map((team) => team.savedTeamId),
      [incoming.data.savedTeams[0].savedTeamId]);

    repository.deleteTeam(incoming.data.savedTeams[0].savedTeamId);
    const afterCurrentTeamDeletion = repository.loadManagedState();
    assert.deepEqual(afterCurrentTeamDeletion.savedTeams, []);
    assert.equal(afterCurrentTeamDeletion.currentBattleSession, undefined);
    assert.throws(() => repository.deleteTeam(incoming.data.savedTeams[0].savedTeamId), /找不到要删除的队伍/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('repository startup rolls an interrupted directory swap back to the exact previous bytes', async () => {
  const { AppStorageRepository } = await repositoryPromise;
  const directory = await mkdtemp(path.join(tmpdir(), 'harmony-storage-recovery-'));
  const root = directory.replaceAll('\\', '/');
  try {
    const fixture = await readJson(path.join(fixtureRoot, 'app-backup.json'));
    const repository = new AppStorageRepository(root);
    repository.restoreAppBackupJson(JSON.stringify(fixture));
    const relativeTeam = `saved-teams/${fixture.data.savedTeams[0].savedTeamId}.json`;
    const liveTeam = path.join(directory, relativeTeam);
    const originalBytes = await readFile(liveTeam);

    const stageName = '.app-storage-stage-123-456';
    const backupName = '.app-storage-backup-123-456';
    const stageTeamDirectory = path.join(directory, stageName, 'saved-teams');
    const backupDirectory = path.join(directory, backupName);
    await mkdir(stageTeamDirectory, { recursive: true });
    await mkdir(backupDirectory, { recursive: true });
    const incoming = structuredClone(fixture.data.savedTeams[0]);
    incoming.teamName = '不应保留的中断写入';
    await writeFile(path.join(stageTeamDirectory, `${incoming.savedTeamId}.json`), JSON.stringify(incoming), 'utf8');
    await rename(path.join(directory, 'saved-teams'), path.join(backupDirectory, 'saved-teams'));
    await rename(stageTeamDirectory, path.join(directory, 'saved-teams'));
    await writeFile(path.join(directory, '.app-storage-transaction.json'), JSON.stringify({
      schemaVersion: 1,
      kind: 'AppStorageTransaction',
      stageDirectory: stageName,
      backupDirectory: backupName,
      relativePaths: ['saved-teams'],
      previousPaths: ['saved-teams']
    }), 'utf8');

    const recovered = new AppStorageRepository(root);
    assert.deepEqual(await readFile(liveTeam), originalBytes);
    assert.equal(recovered.readRawForVerification('.app-storage-transaction.json'), undefined);
    await assert.rejects(readFile(path.join(directory, stageName)), /ENOENT|EISDIR/);
    await assert.rejects(readFile(path.join(directory, backupName)), /ENOENT|EISDIR/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('storage failures redact absolute paths, team contents and tokens before UI logging', async () => {
  const { AppStorageRepository } = await repositoryPromise;
  const directory = await mkdtemp(path.join(tmpdir(), 'harmony-storage-redaction-'));
  try {
    const repository = new AppStorageRepository(directory.replaceAll('\\', '/'));
    globalThis.__harmonyFileIoSensitiveOpenFailure = true;
    assert.throws(() => repository.saveUpdateChannel('stable'), (error) => {
      assert.equal(error.message, '存储文件写入失败');
      assert.doesNotMatch(error.message, /TOKEN_secret|Pikachu|Ditto|harmony-storage-redaction|[A-Z]:[\\/]/i);
      return true;
    });
  } finally {
    delete globalThis.__harmonyFileIoSensitiveOpenFailure;
    await rm(directory, { recursive: true, force: true });
  }
});

test('saved teams load by most-recent modification time instead of filename order', async () => {
  const { AppStorageRepository } = await repositoryPromise;
  const fixture = await readJson(path.join(fixtureRoot, 'app-backup.json'));
  const directory = await mkdtemp(path.join(tmpdir(), 'harmony-team-order-'));
  try {
    const repository = new AppStorageRepository(directory.replaceAll('\\', '/'));
    const older = clone(fixture.data.savedTeams[0]);
    older.savedTeamId = 'z-older';
    older.teamName = '旧队伍';
    older.teamSlotName = '旧队伍';
    const newer = clone(fixture.data.savedTeams[0]);
    newer.savedTeamId = 'a-newer';
    newer.teamName = '新队伍';
    newer.teamSlotName = '新队伍';
    repository.saveTeam(older);
    repository.saveTeam(newer);
    await utimes(path.join(directory, 'saved-teams', 'z-older.json'), new Date('2026-01-01'), new Date('2026-01-01'));
    await utimes(path.join(directory, 'saved-teams', 'a-newer.json'), new Date('2026-02-01'), new Date('2026-02-01'));
    assert.deepEqual(repository.loadManagedState().savedTeams.map((team) => team.savedTeamId),
      ['a-newer', 'z-older']);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
