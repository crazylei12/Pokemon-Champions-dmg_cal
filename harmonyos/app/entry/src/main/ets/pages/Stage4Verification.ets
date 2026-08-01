import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { RELEASE_VARIANT } from 'BuildProfile';
import { AppStorageRepository, USER_PRESETS_FILE } from '../storage/AppStorageRepository';
import {
  buildOpponentPresetShareEnvelope,
  HudLayoutRoot,
  StoredBattleSession,
  StoredEntity,
  StoredOpponentPresetEntry,
  StoredPokemon,
  StoredTeam,
  UserOpponentPresetRoot
} from '../storage/StorageContracts';
import { getStage4VerificationMode } from '../storage/Stage4VerificationLaunch';

const DOMAIN: number = 0x5043;
const TEAM_ID: string = 'stage4-variant-persistence';

function entity(type: string, id: string): StoredEntity {
  return {
    entityType: type,
    canonicalId: `${type}.${id.toLowerCase().replace(/[^a-z0-9]+/g, '')}`,
    showdownId: id,
    displayName: id
  };
}

function pokemon(index: number): StoredPokemon {
  return {
    species: entity('species', `Stage4-${index}`),
    level: 50,
    actualStats: { hp: 150, atk: 100, def: 100, spa: 100, spd: 100, spe: 100 },
    ability: entity('ability', 'Pressure'),
    moves: [{ move: entity('move', 'Protect'), source: 'OWN_BUILD' }]
  };
}

function team(): StoredTeam {
  const members: StoredPokemon[] = [];
  for (let index = 0; index < 6; index += 1) members.push(pokemon(index));
  return {
    schemaVersion: 1,
    kind: 'SavedOwnTeam',
    savedTeamId: TEAM_ID,
    teamName: 'Stage 4 persistence',
    teamSlotName: 'Stage 4 persistence',
    pokemon: members
  };
}

function opponentTeam(): StoredEntity[] {
  const members: StoredEntity[] = [];
  for (let index = 0; index < 6; index += 1) members.push(entity('species', `Opponent-${index}`));
  return members;
}

function session(): StoredBattleSession {
  return {
    schemaVersion: 6,
    kind: 'BattleSession',
    sessionId: 'stage4-session',
    createdAt: '2026-08-01T00:00:00Z',
    selectedOwnTeamId: TEAM_ID,
    opponentTeam: opponentTeam(),
    calculationSelection: {
      battleType: 'DOUBLE',
      selectedPresetId: 'user.stage4',
      opponentPresetIds: { '0': 'user.stage4' },
      opponentManualOverrides: { '0': { baseProfileId: 'user.stage4' } }
    }
  };
}

function presetEntry(profileId: string, profileName: string, speciesId: string): StoredOpponentPresetEntry {
  return {
    speciesId,
    preset: {
      profileId,
      profileName,
      source: 'USER_SAVED',
      level: 50,
      statPoints: { hp: 32, atk: 0, def: 0, spa: 32, spd: 0, spe: 0 },
      ability: entity('ability', 'Blaze'),
      moves: [{ move: entity('move', 'Heat Wave'), basePower: 95, type: 'Fire' }]
    }
  };
}

function initialPresets(): UserOpponentPresetRoot {
  return {
    schemaVersion: 1,
    kind: 'OpponentUserPresets',
    presets: [presetEntry('user.stage4', 'Initial', 'charizard'),
      presetEntry('user.local-only', 'Local only', 'eevee')]
  };
}

function incomingPresets(): UserOpponentPresetRoot {
  return {
    schemaVersion: 1,
    kind: 'OpponentUserPresets',
    presets: [presetEntry('user.stage4', 'Updated', 'charizard'),
      presetEntry('user.imported', 'Imported', 'gengar')]
  };
}

function hudLayouts(): HudLayoutRoot {
  return {
    schemaVersion: 1,
    kind: 'BattleDirectHudLayouts',
    landscape: { elements: { DAMAGE: { x: 0.1, y: 0.2, width: 0.4, height: 0.3 } } }
  };
}

@Entry
@Component
struct Stage4Verification {
  @State status: string = 'RUNNING';
  private started: boolean = false;

  aboutToAppear(): void {
    if (this.started) return;
    this.started = true;
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const repository = new AppStorageRepository(context.filesDir);
      const mode = getStage4VerificationMode();
      if (mode === 'seed') this.seedAndVerify(repository, context);
      else this.verifyPersisted(repository);
    } catch (error) {
      this.status = `FAIL ${String(error)}`;
      hilog.error(DOMAIN, 'PCStage4', 'STAGE4_STORAGE_FAIL %{public}s', this.status);
    }
  }

  private seedAndVerify(repository: AppStorageRepository, context: common.UIAbilityContext): void {
    repository.saveUserOpponentPresets(initialPresets());
    repository.saveTeam(team());
    repository.saveCurrentBattleSession(session());
    repository.saveUpdateChannel('preview');
    repository.saveHudLayouts(hudLayouts());

    const backup = repository.buildAppBackupJson('2026-08-01T00:00:00Z', '1.1.4');
    repository.deleteTeam(TEAM_ID);
    repository.saveUpdateChannel('stable');
    let injectedFailureObserved = false;
    try {
      repository.restoreAppBackupJsonWithInjectedFailureForVerification(backup, 1);
    } catch (error) {
      injectedFailureObserved = String(error).includes('Injected restore failure');
    }
    const rolledBack = repository.loadManagedState();
    if (!injectedFailureObserved || rolledBack.savedTeams.length !== 0 ||
      rolledBack.currentBattleSession !== undefined || rolledBack.userOpponentPresets.presets.length !== 2 ||
      rolledBack.updateChannel !== 'stable') {
      throw new Error('mid-restore failure did not roll back to the previous snapshot');
    }
    const restored = repository.restoreAppBackupJson(backup);
    if (restored.teamCount !== 1 || !restored.hasBattleSession || restored.userOpponentPresetCount !== 2 ||
      repository.loadUpdateChannel() !== 'preview') {
      throw new Error('full backup round-trip mismatch');
    }

    const share = JSON.stringify(buildOpponentPresetShareEnvelope(
      incomingPresets(), '2026-08-01T00:00:01Z', '1.1.4'));
    const first = repository.mergeOpponentPresetShareJson(share);
    const second = repository.mergeOpponentPresetShareJson(share);
    if (first.added !== 1 || first.updated !== 1 || second.unchanged !== 2) {
      throw new Error('preset merge is not idempotent');
    }
    const merged = repository.loadUserOpponentPresets().root;

    repository.writeRawForVerification(USER_PRESETS_FILE, '{');
    if (!repository.loadUserOpponentPresets().problem) throw new Error('corrupt preset storage was not detected');
    let blocked = false;
    try {
      repository.saveUserOpponentPresets(merged);
    } catch (error) {
      blocked = true;
    }
    if (!blocked || repository.readRawForVerification(USER_PRESETS_FILE) !== '{') {
      throw new Error('corrupt preset storage was overwritten');
    }
    const recovery = repository.preserveCorruptedUserPresetsAndReset(1785542400000);
    if (repository.readRawForVerification(recovery) !== '{') throw new Error('corrupt recovery copy mismatch');
    repository.saveUserOpponentPresets(merged);

    const backupDir = `${context.cacheDir}/stage4-system-backup`;
    const restoredDir = `${context.cacheDir}/stage4-system-restore`;
    const copied = repository.copySystemBackupTo(backupDir);
    const restoredFiles = new AppStorageRepository(restoredDir).restoreSystemBackupFrom(backupDir);
    const restoredState = new AppStorageRepository(restoredDir).loadManagedState();
    if (copied !== restoredFiles || restoredState.savedTeams.length !== 1 ||
      restoredState.userOpponentPresets.presets.length !== 3 || restoredState.currentBattleSession === undefined) {
      throw new Error('system backup allowlist round-trip mismatch');
    }

    this.status = `PASS seed ${copied}`;
    hilog.info(DOMAIN, 'PCStage4',
      'STAGE4_STORAGE_PASS variant=%{public}s teams=1 presets=3 files=%{public}d atomic=true rollback=true',
      RELEASE_VARIANT, copied);
  }

  private verifyPersisted(repository: AppStorageRepository): void {
    const state = repository.loadManagedState();
    const hud = repository.loadHudLayouts();
    const variant: string = RELEASE_VARIANT;
    if (variant !== 'replay' || state.savedTeams.length !== 1 ||
      state.savedTeams[0].savedTeamId !== TEAM_ID || state.currentBattleSession?.selectedOwnTeamId !== TEAM_ID ||
      state.userOpponentPresets.presets.length !== 3 || state.updateChannel !== 'preview' ||
      hud.landscape?.elements['DAMAGE'] === undefined) {
      throw new Error('standard-to-replay persisted state mismatch');
    }
    repository.buildAppBackupJson('2026-08-01T00:00:02Z', '1.1.4');
    this.status = 'PASS replay persistence';
    hilog.info(DOMAIN, 'PCStage4',
      'STAGE4_VARIANT_PASS variant=%{public}s teams=1 presets=3 session=true settings=true hud=true', RELEASE_VARIANT);
  }

  build() {
    Column({ space: 16 }) {
      Text('本地存储与迁移验收')
        .fontSize(24)
        .fontWeight(FontWeight.Bold)
      Text(this.status)
        .id('stage4-verification-status')
        .fontSize(16)
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .backgroundColor('#F4F7FB')
  }
}
