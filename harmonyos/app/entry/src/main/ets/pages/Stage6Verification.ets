import { common } from '@kit.AbilityKit';
import { EntityType, StatValues } from '../domain/Models';
import {
  OwnTeamImportDraft,
  RecognizedEntity,
  RecognizedOwnTeamPage,
  RecognizedOwnTeamSlot
} from '../domain/OwnTeamRecognition';
import { AppStorageRepository } from '../storage/AppStorageRepository';
import { takeStage6VerificationMode } from '../storage/Stage6VerificationLaunch';
import { StoredOwnTeamImportDraft } from '../storage/StorageContracts';

interface SeedMember {
  species: RecognizedEntity;
  ability: RecognizedEntity;
  item?: RecognizedEntity;
  moves: RecognizedEntity[];
  stats: StatValues;
}

function entity(entityType: EntityType, showdownId: string, displayName: string): RecognizedEntity {
  return { entityType, canonicalId: `${entityType}.${showdownId.toLocaleLowerCase().replace(/[^a-z0-9]+/g, '')}`,
    showdownId, displayName, originalText: displayName, confidence: 0.99, source: 'ocr' };
}

function members(): SeedMember[] {
  const protect = entity('move', 'Protect', '守住');
  return [
    { species: entity('species', 'Ditto', '百变怪'), ability: entity('ability', 'Imposter', '变身者'),
      item: entity('item', 'Choice Scarf', '讲究围巾'), moves: [entity('move', 'Transform', '变身')],
      stats: { hp: 155, atk: 68, def: 68, spa: 68, spd: 68, spe: 68 } },
    { species: entity('species', 'Mawile', '大嘴娃'), ability: entity('ability', 'Intimidate', '威吓'),
      item: entity('item', 'Mawilite', '大嘴娃进化石'), moves: [entity('move', 'Play Rough', '嬉闹'),
        entity('move', 'Iron Head', '铁头'), entity('move', 'Sucker Punch', '突袭'), protect],
      stats: { hp: 157, atk: 150, def: 105, spa: 75, spd: 77, spe: 63 } },
    { species: entity('species', 'Armarouge', '红莲铠骑'), ability: entity('ability', 'Flash Fire', '引火'),
      moves: [entity('move', 'Armor Cannon', '铠农炮'), entity('move', 'Expanding Force', '广域战力'),
        entity('move', 'Trick Room', '戏法空间'), protect],
      stats: { hp: 161, atk: 72, def: 120, spa: 194, spd: 100, spe: 127 } },
    { species: entity('species', 'Gengar', '耿鬼'), ability: entity('ability', 'Cursed Body', '诅咒之躯'),
      item: entity('item', 'Focus Sash', '气势披带'), moves: [entity('move', 'Shadow Ball', '暗影球'),
        entity('move', 'Sludge Bomb', '污泥炸弹'), entity('move', 'Icy Wind', '冰冻之风'), protect],
      stats: { hp: 135, atk: 63, def: 80, spa: 182, spd: 95, spe: 178 } },
    { species: entity('species', 'Whimsicott', '风妖精'), ability: entity('ability', 'Prankster', '恶作剧之心'),
      item: entity('item', 'Covert Cloak', '密探斗篷'), moves: [entity('move', 'Moonblast', '月亮之力'),
        entity('move', 'Tailwind', '顺风'), entity('move', 'Encore', '再来一次'), protect],
      stats: { hp: 167, atk: 64, def: 105, spa: 97, spd: 95, spe: 184 } },
    { species: entity('species', 'Tyranitar', '班基拉斯'), ability: entity('ability', 'Sand Stream', '扬沙'),
      item: entity('item', 'Assault Vest', '突击背心'), moves: [entity('move', 'Rock Slide', '岩崩'),
        entity('move', 'Crunch', '咬碎'), entity('move', 'Low Kick', '踢倒'), entity('move', 'Ice Punch', '冰冻拳')],
      stats: { hp: 207, atk: 204, def: 130, spa: 103, spd: 120, spe: 82 } }
  ];
}

function page(sceneType: 'OWN_TEAM_MOVE_ITEM' | 'OWN_TEAM_STATS', data: SeedMember[]): RecognizedOwnTeamPage {
  const movePage = sceneType === 'OWN_TEAM_MOVE_ITEM';
  const slots: RecognizedOwnTeamSlot[] = data.map((member: SeedMember, slotIndex: number): RecognizedOwnTeamSlot => ({
    slotIndex,
    species: member.species,
    ability: movePage ? member.ability : undefined,
    item: movePage ? member.item : undefined,
    moves: movePage ? member.moves : [],
    moveSlotIndexes: movePage ? member.moves.map((_move: RecognizedEntity, index: number) => index) : [],
    actualStats: movePage ? {} : member.stats
  }));
  const recognized = movePage ? 41 : 42;
  return { sceneType, image: { width: 2772, height: 1240, capturedAt: '2026-08-01T00:00:00Z' }, slots,
    recognition: { recognized, total: 42, rate: recognized / 42 } };
}

function verificationDraft(): OwnTeamImportDraft {
  const data = members();
  return { schemaVersion: 1, kind: 'OwnTeamImportDraft',
    moveItemPage: page('OWN_TEAM_MOVE_ITEM', data), statsPage: page('OWN_TEAM_STATS', data) };
}

@Entry
@Component
struct Stage6Verification {
  @State status: string = 'RUNNING';

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    const repository = new AppStorageRepository(context.filesDir);
    const mode = takeStage6VerificationMode();
    try {
      if (mode === 'seed') {
        repository.saveOwnTeamImportDraft(verificationDraft() as StoredOwnTeamImportDraft);
        this.status = 'PASS seed';
      } else if (mode === 'clear') {
        repository.clearOwnTeamImportDraft();
        this.status = 'PASS clear';
      } else {
        this.status = 'FAIL invalid mode';
      }
    } catch (error) {
      this.status = `FAIL ${String(error)}`;
    }
  }

  build() {
    Column({ space: 12 }) {
      Text('Stage 6 verification').fontSize(22).fontWeight(FontWeight.Bold)
      Text(this.status).id('stage6-verification-status').fontSize(16)
    }.width('100%').height('100%').justifyContent(FlexAlign.Center)
  }
}
