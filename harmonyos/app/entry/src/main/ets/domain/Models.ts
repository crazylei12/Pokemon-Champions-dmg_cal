export type EntityType = 'species' | 'move' | 'ability' | 'item' | 'nature' | 'type';
export type InputSource = 'ocr' | 'user' | 'preset' | 'generated' | 'manual' | 'system';
export type MoveSource = 'OWN_BUILD' | 'OPPONENT_LEGAL_MOVE_POOL' | 'PROFILE_PRESET' | 'MANUAL_OVERRIDE';
export type CalculationDirection = 'OWN_TO_OPPONENT' | 'OPPONENT_TO_OWN';
export type BattleSide = 'OWN' | 'OPPONENT';
export type BattleType = 'SINGLE' | 'DOUBLE';

export interface EntityRef {
  entityType: EntityType;
  canonicalId: string;
  showdownId: string;
  displayName?: string;
  originalText?: string;
  confidence?: number;
  source?: InputSource;
}

export interface LocalizedNameEntry {
  entityType: EntityType;
  canonicalId: string;
  showdownId: string;
  englishName?: string;
  localizedNames?: Record<string, string[]>;
  aliases?: string[];
}

export interface StatValues {
  hp?: number;
  atk?: number;
  def?: number;
  spa?: number;
  spd?: number;
  spe?: number;
}

export interface MoveValue {
  entity: EntityRef;
  basePower?: number;
  type?: string;
  priority?: number;
  source?: MoveSource;
}

export interface PokemonBuild {
  species: EntityRef;
  level: number;
  statPoints?: StatValues;
  actualStats?: StatValues;
  ability?: EntityRef;
  item?: EntityRef;
  moves: MoveValue[];
}

export interface SpeciesFormOption {
  familyId: string;
  configurationShareGroupId?: string;
  species: EntityRef;
  baseStats: StatValues;
  defaultAbility?: EntityRef;
  abilities: EntityRef[];
  learnableMoves: MoveValue[];
}

export type OpponentProfileSource = 'USER_SAVED' | 'GENERATED_TEMPLATE' | 'OPEN_SOURCE_PRESET' | 'MANUAL_CURRENT';

export interface OpponentProfile {
  profileId: string;
  profileName: string;
  source: OpponentProfileSource;
  level?: number;
  statPoints?: StatValues;
  actualStats?: StatValues;
  statAlignment?: EntityRef;
  ability?: EntityRef;
  item?: EntityRef;
  moves?: MoveValue[];
}

export interface NatureOption {
  entity: EntityRef;
  plus?: string;
  minus?: string;
}

export interface SideConditions {
  reflect?: boolean;
  lightScreen?: boolean;
  protected?: boolean;
  tailwind?: boolean;
  helpingHand?: boolean;
  friendGuard?: boolean;
  auroraVeil?: boolean;
}

export interface BattleCondition {
  battleType: BattleType;
  weather?: string;
  terrain?: string;
  attackerSideConditions?: SideConditions;
  defenderSideConditions?: SideConditions;
  isCritical?: boolean;
  isSpreadMove?: boolean;
  isMagicRoom?: boolean;
  isWonderRoom?: boolean;
  isGravity?: boolean;
}

export interface DamageProjection {
  requestId: string;
  calculationDirection: CalculationDirection;
  attackerSide: BattleSide;
  attackerSpeciesId: string;
  defenderSide: BattleSide;
  defenderSpeciesId: string;
  selectedProfileId: string;
  warningCodes: string[];
  moveId: string;
  moveSource: MoveSource;
  moveCategory: string;
  minDamage: number;
  maxDamage: number;
  minPercent: number;
  maxPercent: number;
  koHits: number;
}

export interface EngineInfo {
  name: string;
  version: string;
  generation: string;
  offline: boolean;
}

export interface SpeedRange {
  minimum: number;
  maximum: number;
}
