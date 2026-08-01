import { common } from '@kit.AbilityKit';
import { AppStorageRepository } from '../storage/AppStorageRepository';
import { takeStage7VerificationMode } from '../storage/Stage7VerificationLaunch';
import { StoredEntity, StoredPokemon, StoredTeam, StoredTeamPreview } from '../storage/StorageContracts';
import { TeamPreviewRecognitionService } from '../services/TeamPreviewRecognitionService';
import { StableCaptureFrame } from '../services/ScreenCaptureService';

const TEAM_ID: string = 'stage7-preview-match';
const OWN: string[][] = [
  ['Ditto', '百变怪'], ['Mawile', '大嘴娃'], ['Armarouge', '红莲铠骑'], ['Gengar', '耿鬼'],
  ['Whimsicott', '风妖精'], ['Tyranitar', '班基拉斯']
];
const OPPONENT: string[][] = [
  ['Charizard', '喷火龙'], ['Azumarill', '玛力露丽'], ['Steelix', '大钢蛇'], ['Drampa', '老翁龙'],
  ['Aggron', '波士可多拉'], ['Sylveon', '仙子伊布']
];

interface SeedCandidate {
  entityType: string;
  canonicalId: string;
  showdownId: string;
  displayName: string;
  confidence: number;
  score: number;
  scoreMargin: number;
  source: string;
  visualVariant: string;
  isShiny: boolean;
}

interface SeedSlot {
  side: string;
  slotIndex: number;
  roiId: string;
  confirmed: boolean;
  requiresConfirmation: boolean;
  selectedCandidate: SeedCandidate;
  candidates: SeedCandidate[];
}

interface SeedPerformance {
  seeded: boolean;
}

interface SeedImageSize {
  width: number;
  height: number;
}

interface SeedViewport {
  left: number;
  top: number;
  width: number;
  height: number;
}

interface SeedRoiMapping {
  asset: string;
  mode: string;
  gameViewport: SeedViewport;
}

interface SeedPreview {
  schemaVersion: number;
  kind: string;
  sceneType: string;
  capturedAt: string;
  imageSize: SeedImageSize;
  backend: string;
  templateAsset: string;
  roiMapping: SeedRoiMapping;
  elapsedMs: number;
  performance: SeedPerformance;
  confirmed: boolean;
  ownTeamCandidates: SeedSlot[];
  opponentTeamCandidates: SeedSlot[];
  warnings: string[];
}

function entity(type: string, showdownId: string, displayName: string = showdownId): StoredEntity {
  return { entityType: type, canonicalId: `${type}.${showdownId.toLocaleLowerCase().replace(/[^a-z0-9]+/g, '')}`,
    showdownId, displayName };
}

function pokemon(value: string[]): StoredPokemon {
  return { species: entity('species', value[0], value[1]), level: 50,
    actualStats: { hp: 150, atk: 100, def: 100, spa: 100, spd: 100, spe: 100 },
    ability: entity('ability', 'Pressure', '压迫感'),
    moves: [{ move: entity('move', value[0] === 'Ditto' ? 'Transform' : 'Protect',
      value[0] === 'Ditto' ? '变身' : '守住'), source: 'OWN_BUILD' }] };
}

function team(): StoredTeam {
  return { schemaVersion: 1, kind: 'SavedOwnTeam', savedTeamId: TEAM_ID,
    teamName: '相册预览验收队', teamSlotName: '相册预览验收队', pokemon: OWN.map(pokemon) };
}

function candidate(value: string[], confidence: number, margin: number): SeedCandidate {
  return { entityType: 'SPECIES', canonicalId: `species.${value[0].toLocaleLowerCase()}`, showdownId: value[0],
    displayName: value[1], confidence, score: confidence, scoreMargin: margin,
    source: 'android_template_v2', visualVariant: 'default', isShiny: false };
}

function slot(side: string, index: number, value: string[]): SeedSlot {
  const confidence = index === 0 ? 0.89 : 0.97;
  const margin = index === 0 ? 0.02 : 0.12;
  const alternate = side === 'own' ? ['Pikachu', '皮卡丘'] : ['Eevee', '伊布'];
  const candidates = [candidate(value, confidence, margin), candidate(alternate, 0.82, 0.01)];
  return { side, slotIndex: index, roiId: `team_preview.${side}.slot${index}.pokemon_icon`, confirmed: false,
    requiresConfirmation: confidence < 0.90 || margin < 0.035, selectedCandidate: candidates[0], candidates };
}

function preview(): StoredTeamPreview {
  const imageSize: SeedImageSize = { width: 2772, height: 1240 };
  const viewport: SeedViewport = { left: 284, top: 0, width: 2204, height: 1240 };
  const roiMapping: SeedRoiMapping = { asset: 'team-preview.safe-zone-roi.zh-Hans.v2.json',
    mode: 'largest_centered_aspect', gameViewport: viewport };
  const performance: SeedPerformance = { seeded: true };
  const result: SeedPreview = {
    schemaVersion: 1, kind: 'TeamPreviewRecognitionResult', sceneType: 'TEAM_PREVIEW',
    capturedAt: '2026-08-01T00:07:00Z', imageSize,
    backend: 'harmonyos_opencv_4.13.0', templateAsset: 'team-preview-templates-v2.bin',
    roiMapping, elapsedMs: 4000, performance, confirmed: false,
    ownTeamCandidates: OWN.map((value: string[], index: number) => slot('own', index, value)),
    opponentTeamCandidates: OPPONENT.map((value: string[], index: number) => slot('opponent', index, value)),
    warnings: ['Low-confidence or low-margin candidates require user confirmation before damage calculation.']
  };
  return result as StoredTeamPreview;
}

function paintRect(bytes: Uint8Array, width: number, left: number, top: number, right: number, bottom: number,
  colorIndex: number): void {
  const red = 145 + (colorIndex * 29) % 100;
  const green = 80 + (colorIndex * 47) % 150;
  const blue = 110 + (colorIndex * 61) % 130;
  for (let y = top; y < bottom; y += 1) {
    for (let x = left; x < right; x += 1) {
      const offset = (y * width + x) * 4;
      bytes[offset] = red;
      bytes[offset + 1] = green;
      bytes[offset + 2] = blue;
      bytes[offset + 3] = 255;
    }
  }
}

function nativeSmokeFrame(): StableCaptureFrame {
  const width = 2772;
  const height = 1240;
  const bytes = new Uint8Array(width * height * 4);
  for (let offset = 0; offset < bytes.length; offset += 4) {
    bytes[offset] = 38;
    bytes[offset + 1] = 64;
    bytes[offset + 2] = 92;
    bytes[offset + 3] = 255;
  }
  for (let slotIndex = 0; slotIndex < 6; slotIndex += 1) {
    const top = 203 + slotIndex * 146;
    paintRect(bytes, width, 876, top, 944, top + 72, slotIndex);
    paintRect(bytes, width, 2154, top, 2260, top + 82, slotIndex + 6);
  }
  return { data: bytes.buffer as ArrayBuffer, width, height, hash: 7, timestampUs: 7, cards: [] };
}

@Entry
@Component
struct Stage7Verification {
  @State status: string = 'RUNNING';

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    const repository = new AppStorageRepository(context.filesDir);
    const mode = takeStage7VerificationMode();
    try {
      if (mode === 'seed') {
        repository.saveTeam(team());
        repository.saveCurrentTeamPreview(preview());
        this.status = 'PASS seed';
      } else if (mode === 'clear') {
        repository.clearCurrentTeamPreview();
        try { repository.deleteTeam(TEAM_ID); } catch (error) {}
        this.status = 'PASS clear';
      } else if (mode === 'native-smoke') {
        this.runNativeSmoke(context);
      } else {
        this.status = 'FAIL invalid mode';
      }
    } catch (error) {
      this.status = `FAIL ${String(error)}`;
    }
  }

  private async runNativeSmoke(context: common.UIAbilityContext): Promise<void> {
    try {
      this.status = 'RUNNING native-smoke';
      const result = await new TeamPreviewRecognitionService(context).recognize(nativeSmokeFrame());
      const slots = [...result.ownTeamCandidates, ...result.opponentTeamCandidates];
      if (slots.length !== 12 || slots.some((slot) => slot.candidates.length === 0)) {
        throw new Error('native engine did not return twelve ranked slots');
      }
      this.status = 'PASS native-smoke';
    } catch (error) {
      this.status = `FAIL native-smoke ${String(error)}`;
    }
  }

  build() {
    Column({ space: 12 }) {
      Text('Stage 7 verification').fontSize(22).fontWeight(FontWeight.Bold)
      Text(this.status).id('stage7-verification-status').fontSize(16)
    }.width('100%').height('100%').justifyContent(FlexAlign.Center)
  }
}
