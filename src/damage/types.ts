export type StatId = 'hp' | 'atk' | 'def' | 'spa' | 'spd' | 'spe';
export type LegacyStatId = 'at' | 'df' | 'sa' | 'sd' | 'sp';

export type StatTableInput = Partial<Record<StatId | LegacyStatId, number>>;
export type StatTable = Record<StatId, number>;

export type EntityType = 'species' | 'move' | 'ability' | 'item' | 'nature' | 'type';
export type InputSource = 'ocr' | 'user' | 'preset' | 'generated' | 'manual' | 'system';

export interface EntityRef {
  entityType: EntityType;
  canonicalId: string;
  showdownId: string;
  displayName?: string;
  originalText?: string;
  confidence?: number;
  source?: InputSource;
}

export interface OcrEntityCandidate extends Partial<EntityRef> {
  entityType: EntityType;
  text: string;
  confidence: number;
  source: 'ocr';
}

export type PokemonTypeName =
  | 'Normal'
  | 'Fighting'
  | 'Flying'
  | 'Poison'
  | 'Ground'
  | 'Rock'
  | 'Bug'
  | 'Ghost'
  | 'Steel'
  | 'Fire'
  | 'Water'
  | 'Grass'
  | 'Electric'
  | 'Psychic'
  | 'Ice'
  | 'Dragon'
  | 'Dark'
  | 'Fairy'
  | 'Stellar'
  | '???';

export type MoveCategory = 'Physical' | 'Special' | 'Status';
export type StatusName = 'slp' | 'psn' | 'brn' | 'frz' | 'par' | 'tox';
export type BattleType = 'SINGLE' | 'DOUBLE';
export type CalculationMode = 'EXACT' | 'TEMPLATE' | 'COMPARE_TEMPLATES' | 'ENVELOPE';
export type CalculationDirection = 'OWN_TO_OPPONENT' | 'OPPONENT_TO_OWN';
export type BattleSide = 'OWN' | 'OPPONENT';
export type MoveSelectionMode = 'ONE_MOVE' | 'ALL_ATTACKER_MOVES';
export type MoveSource =
  | 'OWN_BUILD'
  | 'OPPONENT_LEGAL_MOVE_POOL'
  | 'PROFILE_PRESET'
  | 'MANUAL_OVERRIDE';

export type WeatherName =
  | 'Sand'
  | 'Sun'
  | 'Rain'
  | 'Hail'
  | 'Snow'
  | 'Harsh Sunshine'
  | 'Heavy Rain'
  | 'Strong Winds'
  | 'NONE';

export type TerrainName = 'Electric' | 'Grassy' | 'Psychic' | 'Misty' | 'NONE';

export interface LocalizedNameEntry {
  entityType: EntityType;
  canonicalId: string;
  showdownId: string;
  englishName?: string;
  localizedNames?: Record<string, string[]>;
  aliases?: string[];
}

export interface LocalizationContext {
  gameLanguage: string;
  displayLanguage: string;
  nameEntries?: LocalizedNameEntry[];
}

export interface MoveBuild {
  move: EntityRef;
  source?: MoveSource;
  basePowerOverride?: number;
  typeOverride?: PokemonTypeName;
  categoryOverride?: MoveCategory;
  isCritical?: boolean;
  isStellarFirstUse?: boolean;
  hits?: number;
  timesUsed?: number;
  timesUsedWithMetronome?: number;
  isSpreadMoveOverride?: boolean;
}

export interface KnownPokemonBuild {
  species: EntityRef;
  form?: EntityRef;
  level?: number;
  statPoints?: StatTableInput;
  actualStats?: StatTableInput;
  statAlignment?: EntityRef;
  legacyEvs?: StatTableInput;
  legacyIvs?: StatTableInput;
  ability?: EntityRef;
  abilityOn?: boolean;
  item?: EntityRef;
  gender?: 'M' | 'F' | 'N';
  moves: MoveBuild[];
  typeOverride?: PokemonTypeName[];
  statStages?: StatTableInput;
  status?: StatusName | '';
  toxicCounter?: number;
  currentHp?: number;
  currentHpPercent?: number;
  teraType?: PokemonTypeName;
  alliesFainted?: number;
  boostedStat?: Exclude<StatId, 'hp'> | 'auto';
  notes?: string[];
}

export interface OpponentPokemonIdentity {
  species: EntityRef;
  form?: EntityRef;
  sourceTeamSlotIndex?: number;
  recognitionConfidence?: number;
}

export type DefenderProfileSource =
  | 'OPEN_SOURCE_PRESET'
  | 'GENERATED_TEMPLATE'
  | 'USER_CUSTOM'
  | 'MANUAL_CURRENT';

export type OpponentAttackerProfileSource = DefenderProfileSource;

export interface DefenderProfile {
  profileId: string;
  profileName: string;
  source: DefenderProfileSource;
  baseProfileId?: string;
  isSelected?: boolean;
  includedInEnvelope?: boolean;
  editable?: boolean;
  level?: number;
  statPoints?: StatTableInput;
  actualStats?: StatTableInput;
  statAlignment?: EntityRef;
  ability?: EntityRef;
  abilityOn?: boolean;
  item?: EntityRef;
  moves?: MoveBuild[];
  typeOverride?: PokemonTypeName[];
  statStages?: StatTableInput;
  status?: StatusName | '';
  toxicCounter?: number;
  currentHp?: number;
  currentHpPercent?: number;
  teraType?: PokemonTypeName;
  notes?: string[];
}

export interface DefenderProfileSet {
  defenderSpecies: EntityRef;
  selectedProfileId?: string;
  profiles: DefenderProfile[];
}

export interface OpponentAttackerProfile {
  profileId: string;
  profileName: string;
  source: OpponentAttackerProfileSource;
  baseProfileId?: string;
  isSelected?: boolean;
  editable?: boolean;
  level?: number;
  statPoints?: StatTableInput;
  actualStats?: StatTableInput;
  statAlignment?: EntityRef;
  ability?: EntityRef;
  abilityOn?: boolean;
  item?: EntityRef;
  moves?: MoveBuild[];
  typeOverride?: PokemonTypeName[];
  statStages?: StatTableInput;
  status?: StatusName | '';
  toxicCounter?: number;
  currentHp?: number;
  currentHpPercent?: number;
  teraType?: PokemonTypeName;
  notes?: string[];
}

export interface OpponentAttackerProfileSet {
  attackerSpecies: EntityRef;
  selectedProfileId?: string;
  profiles: OpponentAttackerProfile[];
}

export type LegalMovePoolSource = 'SHOWDOWN_DATA' | 'CHAMPIONS_SNAPSHOT' | 'USER_PATCH';

export interface LegalMovePool {
  species: EntityRef;
  form?: EntityRef;
  rulesetVersion?: string;
  learnableMoves: EntityRef[];
  source?: LegalMovePoolSource;
  dataDate?: string;
}

export interface SideConditions {
  spikes?: number;
  steelsurge?: boolean;
  vinelash?: boolean;
  wildfire?: boolean;
  cannonade?: boolean;
  volcalith?: boolean;
  stealthRock?: boolean;
  reflect?: boolean;
  lightScreen?: boolean;
  protected?: boolean;
  seeded?: boolean;
  saltCured?: boolean;
  foresight?: boolean;
  tailwind?: boolean;
  helpingHand?: boolean;
  flowerGift?: boolean;
  powerTrick?: boolean;
  friendGuard?: boolean;
  auroraVeil?: boolean;
  battery?: boolean;
  powerSpot?: boolean;
  steelySpirit?: boolean;
  switching?: 'out' | 'in';
}

export interface BattleCondition {
  battleType: BattleType;
  weather?: WeatherName;
  terrain?: TerrainName;
  attackerSideConditions?: SideConditions;
  defenderSideConditions?: SideConditions;
  isCritical?: boolean;
  isSpreadMove?: boolean;
  isMagicRoom?: boolean;
  isWonderRoom?: boolean;
  isGravity?: boolean;
  ruinAbilities?: {
    beadsOfRuin?: boolean;
    swordOfRuin?: boolean;
    tabletsOfRuin?: boolean;
    vesselOfRuin?: boolean;
  };
  customFlags?: Record<string, boolean | number | string>;
}

export interface DamageRequest {
  requestId?: string;
  calculationDirection?: CalculationDirection;
  direction?: CalculationDirection;
  attackerSide?: BattleSide;
  attacker?: KnownPokemonBuild;
  attackerIdentity?: OpponentPokemonIdentity;
  attackerProfileSet?: OpponentAttackerProfileSet;
  attackerLegalMovePool?: LegalMovePool;
  opponentLegalMovePool?: LegalMovePool;
  defenderSide?: BattleSide;
  defender?: KnownPokemonBuild;
  defenderIdentity?: OpponentPokemonIdentity;
  defenderProfileSet?: DefenderProfileSet;
  localization?: LocalizationContext;
  moveSelection: {
    mode: MoveSelectionMode;
    moveId?: string;
    move?: EntityRef;
    source?: MoveSource;
    legalMovePoolVersion?: string;
  };
  battle: BattleCondition;
  calculationMode: CalculationMode;
}

export interface PokemonSummary {
  speciesId: string;
  speciesName: string;
  profileId?: string;
  profileName?: string;
  level: number;
  ability?: string;
  item?: string;
  nature?: string;
  statPoints?: Partial<StatTable>;
  actualStats?: Partial<StatTable>;
  status?: StatusName | '';
  currentHp?: number;
  maxHp?: number;
  notes?: string[];
}

export interface DamageRange {
  minDamage: number;
  maxDamage: number;
  minPercent: number;
  maxPercent: number;
  rolls: number[] | number[][];
  description: string;
}

export interface KoSummary {
  chance?: number;
  hits: number;
  text: string;
}

export interface ProfileDamageRange {
  defenderProfileId: string;
  defenderProfileName: string;
  profileSource: DefenderProfileSource;
  includedInEnvelope: boolean;
  damageRange: DamageRange;
  koSummary: KoSummary;
  assumptions: string[];
  notes?: string[];
}

export interface MoveDamageResult {
  moveId: string;
  moveName: string;
  moveSource: MoveSource;
  moveCategory: MoveCategory;
  selectedProfileRange: DamageRange;
  profileRanges: ProfileDamageRange[];
  envelopeRange: DamageRange;
  koSummary: KoSummary;
  assumptions: string[];
}

export type DamageWarningCode =
  | 'NO_ATTACKER_MOVES'
  | 'NO_ATTACKER_PROFILE'
  | 'NO_SELECTED_ATTACKER_PROFILE'
  | 'NO_DEFENDER_PROFILES'
  | 'NO_SELECTED_PROFILE'
  | 'EMPTY_ENVELOPE'
  | 'CUSTOM_FLAGS_NOT_APPLIED'
  | 'ACTUAL_STATS_APPROXIMATED'
  | 'MOVE_NOT_FOUND'
  | 'SPECIES_NOT_FOUND'
  | 'NO_OPPONENT_MOVE_SELECTED'
  | 'LEGAL_MOVE_POOL_MISSING'
  | 'ILLEGAL_OPPONENT_MOVE';

export interface DamageWarning {
  code: DamageWarningCode;
  message: string;
  path?: string;
}

export interface DamageCalculationResult {
  requestId?: string;
  calculationDirection: CalculationDirection;
  attackerSide: BattleSide;
  attackerSummary: PokemonSummary;
  defenderSide: BattleSide;
  defenderIdentity: OpponentPokemonIdentity;
  selectedDefenderProfile: DefenderProfile;
  selectedAttackerProfile?: OpponentAttackerProfile;
  moveResults: MoveDamageResult[];
  warnings: DamageWarning[];
}

export interface DamageEngineFacade {
  calculate(request: DamageRequest): DamageCalculationResult;
}
