import type { EntityType } from '../damage/types';

export const RECOGNITION_SCENE_TYPES = [
  'OWN_TEAM_MOVE_ITEM',
  'OWN_TEAM_STATS',
  'TEAM_PREVIEW',
  'BATTLE_FIELD',
  'UNKNOWN',
] as const;

export type RecognitionSceneType = (typeof RECOGNITION_SCENE_TYPES)[number];
export type KnownRecognitionSceneType = Exclude<RecognitionSceneType, 'UNKNOWN'>;

export type RoiRole =
  | 'viewport'
  | 'scene_marker'
  | 'ocr_text'
  | 'number_text'
  | 'pokemon_icon'
  | 'type_icon'
  | 'move_type_icon'
  | 'gender_icon'
  | 'hp_bar'
  | 'button_or_state';

export type RoiEntityType =
  | 'SPECIES'
  | 'MOVE'
  | 'ITEM'
  | 'ABILITY'
  | 'TYPE'
  | 'NATURE'
  | 'STAT'
  | 'UNKNOWN';

export type RecognitionEntityType = EntityType | 'stat' | 'unknown';

export type CandidateSource =
  | 'OCR_TEXT'
  | 'POKEMON_ICON_MATCH'
  | 'TYPE_ICON_HINT'
  | 'TEAM_CONTEXT'
  | 'USER_LOCKED';

export interface Size {
  width: number;
  height: number;
}

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PixelRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface RoiPreprocess {
  colorMode?: 'keep_color' | 'grayscale' | 'binary';
  scale?: number;
  threshold?: string;
  sharpen?: boolean;
}

export interface RoiMatch {
  engine?: 'ocr' | 'template_match' | 'feature_match' | 'manual';
  topK?: number;
  minConfidence?: number;
}

export interface RoiRegion {
  id: string;
  role: RoiRole;
  entityType?: RoiEntityType;
  rect: Rect;
  preprocess?: RoiPreprocess;
  match?: RoiMatch;
  notes?: string;
}

export interface RoiScene {
  description: string;
  status?: 'draft' | 'needs_labelme_annotation' | 'validated';
  samplePaths: string[];
  regions: RoiRegion[];
}

export interface RoiConfig {
  schemaVersion: number;
  game: string;
  language: string;
  canonicalViewport: Size;
  coordinateSpace: {
    origin: 'top-left';
    unit: 'normalized' | 'px';
    relativeTo: 'gameViewport';
  };
  viewportDetection?: {
    strategy?: string;
    targetAspectRatio?: string;
    notes?: string[];
  };
  scenes: Record<string, RoiScene>;
}

export interface GameViewport extends PixelRect {
  source: 'largest_16_9_game_content_area' | 'full_image' | 'manual';
}

export interface RoiCropPlan {
  sceneType: KnownRecognitionSceneType;
  region: RoiRegion;
  viewport: GameViewport;
  rect: PixelRect;
}

export interface OcrTextCandidate {
  text: string;
  confidence: number;
  language?: string;
}

export interface OcrRecognizeContext {
  language: string;
  region: RoiRegion;
  preprocess?: RoiPreprocess;
}

export interface OcrBackend<TImage = unknown> {
  id: string;
  recognize(image: TImage, context: OcrRecognizeContext): Promise<OcrTextCandidate[]>;
}

export interface RecognitionEntityCandidate {
  rawText?: string;
  roiId?: string;
  normalizedText?: string;
  language: string;
  entityType: RecognitionEntityType;
  canonicalId?: string;
  showdownId?: string;
  displayName?: string;
  confidence: number;
  source: CandidateSource;
}

export interface RoiTextRecognition<TImage = unknown> {
  region: RoiRegion;
  image: TImage;
}

export interface RoiTextRecognitionResult {
  roiId: string;
  entityType: RecognitionEntityType;
  rawTextCandidates: OcrTextCandidate[];
  entityCandidates: RecognitionEntityCandidate[];
  warnings: string[];
}

export interface RecognitionResult {
  sceneType: RecognitionSceneType;
  language: string;
  sideMode: 'SINGLE' | 'DOUBLE' | 'UNKNOWN';
  textResults: RoiTextRecognitionResult[];
  warnings: string[];
}
