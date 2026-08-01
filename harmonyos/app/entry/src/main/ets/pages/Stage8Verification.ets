import { common } from '@kit.AbilityKit';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import { battleOverlayCoordinator } from '../services/BattleOverlayCoordinator';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import { AppStorageRepository } from '../storage/AppStorageRepository';
import { takeStage8VerificationMode } from '../storage/Stage8VerificationLaunch';
import { StoredBattleSession, StoredEntity, StoredPokemon, StoredTeam } from '../storage/StorageContracts';

const TEAM_ID: string = 'stage8-battle-team';
const OWN: string[][] = [
  ['Pikachu', '皮卡丘'], ['Mawile', '大嘴娃'], ['Armarouge', '红莲铠骑'],
  ['Gengar', '耿鬼'], ['Whimsicott', '风妖精'], ['Tyranitar', '班基拉斯']
];
const OPPONENT: string[][] = [
  ['Charizard', '喷火龙'], ['Azumarill', '玛力露丽'], ['Steelix', '大钢蛇'],
  ['Drampa', '老翁龙'], ['Aggron', '波士可多拉'], ['Sylveon', '仙子伊布']
];

function entity(type: string, showdownId: string, displayName: string): StoredEntity {
  return { entityType: type, canonicalId: `${type}.${showdownId.toLocaleLowerCase().replace(/[^a-z0-9]+/g, '')}`,
    showdownId, displayName };
}

function pokemon(value: string[], index: number): StoredPokemon {
  const moveNames: string[][] = [['Thunderbolt', '十万伏特'], ['Play Rough', '嬉闹'],
    ['Armor Cannon', '铠农炮'], ['Shadow Ball', '暗影球'], ['Moonblast', '月亮之力'], ['Rock Slide', '岩崩']];
  const move = moveNames[index];
  return { species: entity('species', value[0], value[1]), level: 50,
    actualStats: { hp: 150 + index, atk: 110, def: 105, spa: 120, spd: 100, spe: 90 + index },
    statPoints: {}, ability: entity('ability', 'Pressure', '压迫感'),
    moves: [
      { move: entity('move', move[0], move[1]), source: 'OWN_BUILD', basePower: 90 },
      { move: entity('move', 'Protect', '守住'), source: 'OWN_BUILD', basePower: 0 }
    ] };
}

function team(): StoredTeam {
  return { schemaVersion: 1, kind: 'SavedOwnTeam', savedTeamId: TEAM_ID,
    teamName: '第八阶段验收队', teamSlotName: '第八阶段验收队',
    pokemon: OWN.map((value: string[], index: number) => pokemon(value, index)) };
}

function session(): StoredBattleSession {
  return { schemaVersion: 6, kind: 'BattleSession', sessionId: 'stage8-session',
    createdAt: '2026-08-01T08:00:00Z', previewCapturedAt: '2026-08-01T07:59:00Z',
    selectedOwnTeamId: TEAM_ID,
    opponentTeam: OPPONENT.map((value: string[]) => entity('species', value[0], value[1])),
    calculationSelection: {
      direction: 'OWN_TO_OPPONENT', ownSlot: 0, opponentSlot: 0, battleType: 'DOUBLE',
      weather: 'NONE', terrain: 'NONE', spread: true, selectedMoveId: 'Thunderbolt',
      opponentPresetIds: { '0': 'generated.default' },
      speedLine: { ownTailwind: false, opponentTailwind: false, trickRoom: false,
        ownPokemon: {}, opponentPokemon: {} },
      directHud: { ownSlots: [0, 1], opponentSlots: [0, 1], visible: true }
    }
  };
}

@Entry
@Component
struct Stage8Verification {
  @StorageLink('stage8VerificationStatus') status: string = 'RUNNING';
  @StorageLink('battleHudDamageValues') hudDamageValues: string = '';

  aboutToAppear(): void {
    this.run();
  }

  private async run(): Promise<void> {
    const context = getContext(this) as common.UIAbilityContext;
    const repository = new AppStorageRepository(context.filesDir);
    const mode = takeStage8VerificationMode();
    try {
      if (mode === 'clear') {
        await battleOverlayCoordinator.close();
        repository.clearCurrentBattleSession();
        try { repository.deleteTeam(TEAM_ID); } catch (error) {}
        this.status = 'PASS clear';
        return;
      }
      if (mode !== 'panel' && mode !== 'hud' && mode !== 'single' && mode !== 'hidden' && mode !== 'restore' &&
        mode !== 'rotation') {
        throw new Error('invalid mode');
      }
      this.hudDamageValues = '';
      repository.saveTeam(team());
      repository.saveCurrentBattleSession(session());
      const catalog = new RuntimeDataRepository(context);
      await catalog.load();
      battleOverlayCoordinator.configure(context, catalog, repository);
      ownTeamCaptureCoordinator.configure(context, catalog, repository);
      await battleOverlayCoordinator.show(mode === 'panel' ? 'panel' : 'hud');
      if (mode === 'single') await battleOverlayCoordinator.setBattleType('SINGLE');
      if (mode === 'hidden') await battleOverlayCoordinator.setHudVisible(false);
      if (mode === 'restore') {
        await battleOverlayCoordinator.setHudVisible(false);
        await battleOverlayCoordinator.setHudVisible(true);
      }
      const snapshot = battleOverlayCoordinator.snapshot();
      if (!snapshot.ready || snapshot.ownNames.length !== 6 || snapshot.opponentNames.length !== 6 ||
        snapshot.speedActions.length !== (mode === 'single' ? 2 : 4)) throw new Error('seeded battle snapshot is incomplete');
      if (mode === 'hidden' && snapshot.state.directHud.visible) throw new Error('HUD was not hidden');
      if (mode === 'restore' && !snapshot.state.directHud.visible) throw new Error('HUD was not restored');
      if (mode === 'panel' && !battleOverlayCoordinator.hasPanelWindow()) throw new Error('panel window was not created');
      if (mode !== 'panel') {
        const expectedWindows = mode === 'hidden' ? 6 : mode === 'single' ? 13 : 15;
        if (battleOverlayCoordinator.activeHudWindowCount() !== expectedWindows) {
          throw new Error(`HUD window count ${battleOverlayCoordinator.activeHudWindowCount()} != ${expectedWindows}`);
        }
      }
      this.status = `PASS ${mode} battle=${snapshot.state.battleType} visible=${snapshot.state.directHud.visible} ` +
        `windows=${battleOverlayCoordinator.activeHudWindowCount()}`;
    } catch (error) {
      this.status = `FAIL ${String(error)}`;
    }
  }

  build() {
    Column({ space: 12 }) {
      Text('Stage 8 verification').fontSize(22).fontWeight(FontWeight.Bold)
      Text(this.status).id('stage8-verification-status').fontSize(16)
      Text(this.hudDamageValues).id('stage8-hud-damage').fontSize(12)
    }.width('100%').height('100%').justifyContent(FlexAlign.Center)
  }
}
