import { EntityRef, MoveValue, OpponentProfile, PokemonBuild, SpeedRange } from './Models';
import { StoredCalculationSelection } from '../storage/StorageContracts';

export type BattleSide = 'OWN' | 'OPPONENT';

export interface BattleStatStages {
  atk: number;
  def: number;
  spa: number;
  spd: number;
  spe: number;
}

export interface BattlePokemonCondition {
  burned: boolean;
  stages: BattleStatStages;
}

export interface SpeedPokemonModifiers {
  stage: number;
  paralyzed: boolean;
  doubled: boolean;
  choiceScarf?: boolean;
}

export interface SpeedLineState {
  ownTailwind: boolean;
  opponentTailwind: boolean;
  trickRoom: boolean;
  ownPokemon: Record<string, SpeedPokemonModifiers>;
  opponentPokemon: Record<string, SpeedPokemonModifiers>;
}

export interface BattleDirectHudState {
  ownSlots: number[];
  opponentSlots: number[];
  visible: boolean;
}

export interface BattleCalculationState extends StoredCalculationSelection {
  direction: string;
  ownSlot: number;
  opponentSlot: number;
  opponentPresetIds: Record<string, string>;
  battleType: string;
  weather: string;
  terrain: string;
  ownReflect: boolean;
  ownLightScreen: boolean;
  ownAuroraVeil: boolean;
  opponentReflect: boolean;
  opponentLightScreen: boolean;
  opponentAuroraVeil: boolean;
  ownProtected: boolean;
  opponentProtected: boolean;
  helpingHand: boolean;
  critical: boolean;
  spread: boolean;
  ownConditions: Record<string, BattlePokemonCondition>;
  opponentConditions: Record<string, BattlePokemonCondition>;
  speedLine: SpeedLineState;
  directHud: BattleDirectHudState;
}

export interface SpeedLinePokemonInput {
  side: BattleSide;
  slot: number;
  name: string;
  baseSpeed: SpeedRange;
  modifiers: SpeedPokemonModifiers;
  tailwind: boolean;
  knownChoiceScarf: boolean;
  priorityMoves: MoveValue[];
  exactBaseSpeed: boolean;
}

export interface SpeedLineAction {
  side: BattleSide;
  slot: number;
  pokemonName: string;
  moveName?: string;
  priority: number;
  minimumSpeed: number;
  maximumSpeed: number;
  exactBaseSpeed: boolean;
}

export interface OverlayRegion {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface OverlayPlacement {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface BattleDamageRequestContext {
  own: PokemonBuild;
  opponent: EntityRef;
  preset: OpponentProfile;
  legalMoves: MoveValue[];
  calculation: BattleCalculationState;
  allOwnMoves?: boolean;
  requestId?: string;
}

const DEFAULT_STAGES: BattleStatStages = { atk: 0, def: 0, spa: 0, spd: 0, spe: 0 };
const DEFAULT_MODIFIERS: SpeedPokemonModifiers = { stage: 0, paralyzed: false, doubled: false };

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, Math.trunc(value)));
}

function objectValue<T>(value: T | undefined, fallback: T): T {
  return value === undefined ? fallback : value;
}

export function defaultBattlePokemonCondition(): BattlePokemonCondition {
  return { burned: false, stages: { ...DEFAULT_STAGES } };
}

export function defaultSpeedPokemonModifiers(): SpeedPokemonModifiers {
  return { ...DEFAULT_MODIFIERS };
}

export function defaultBattleCalculation(): BattleCalculationState {
  return {
    direction: 'OWN_TO_OPPONENT', ownSlot: 0, opponentSlot: 0, opponentPresetIds: {},
    battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE', ownReflect: false,
    ownLightScreen: false, ownAuroraVeil: false, opponentReflect: false,
    opponentLightScreen: false, opponentAuroraVeil: false, ownProtected: false,
    opponentProtected: false, helpingHand: false, critical: false, spread: false,
    ownConditions: {}, opponentConditions: {},
    speedLine: { ownTailwind: false, opponentTailwind: false, trickRoom: false,
      ownPokemon: {}, opponentPokemon: {} },
    directHud: { ownSlots: [0, 1], opponentSlots: [0, 1], visible: true }
  };
}

function normalizeStages(value: BattleStatStages | undefined): BattleStatStages {
  return {
    atk: clamp(value?.atk ?? 0, -6, 6), def: clamp(value?.def ?? 0, -6, 6),
    spa: clamp(value?.spa ?? 0, -6, 6), spd: clamp(value?.spd ?? 0, -6, 6),
    spe: clamp(value?.spe ?? 0, -6, 6)
  };
}

function normalizeCondition(value: BattlePokemonCondition | undefined): BattlePokemonCondition {
  return { burned: value?.burned === true, stages: normalizeStages(value?.stages) };
}

function normalizeConditionMap(value: Record<string, BattlePokemonCondition> | undefined,
  size: number): Record<string, BattlePokemonCondition> {
  const result: Record<string, BattlePokemonCondition> = {};
  for (const key of Object.keys(value ?? {})) {
    const slot = Number.parseInt(key, 10);
    if (Number.isInteger(slot) && slot >= 0 && slot < size) result[String(slot)] = normalizeCondition(value?.[key]);
  }
  return result;
}

function normalizeModifiers(value: SpeedPokemonModifiers | undefined): SpeedPokemonModifiers {
  const result: SpeedPokemonModifiers = {
    stage: clamp(value?.stage ?? 0, -6, 6), paralyzed: value?.paralyzed === true,
    doubled: value?.doubled === true
  };
  if (value?.choiceScarf !== undefined) result.choiceScarf = value.choiceScarf === true;
  return result;
}

function normalizeModifierMap(value: Record<string, SpeedPokemonModifiers> | undefined,
  size: number): Record<string, SpeedPokemonModifiers> {
  const result: Record<string, SpeedPokemonModifiers> = {};
  for (const key of Object.keys(value ?? {})) {
    const slot = Number.parseInt(key, 10);
    if (Number.isInteger(slot) && slot >= 0 && slot < size) result[String(slot)] = normalizeModifiers(value?.[key]);
  }
  return result;
}

export function normalizeBattleDirectHudSlots(slots: number[] | undefined, teamSize: number): number[] {
  const size = Math.max(1, teamSize);
  const first = clamp(slots?.[0] ?? 0, 0, size - 1);
  let second = clamp(slots?.[1] ?? Math.min(1, size - 1), 0, size - 1);
  if (size > 1 && first === second) second = first === 0 ? 1 : 0;
  return [first, second];
}

export function replaceBattleDirectHudSlot(slots: number[], displayIndex: number, teamSlot: number,
  teamSize: number = 6): number[] {
  const normalized = normalizeBattleDirectHudSlots(slots, teamSize);
  const index = displayIndex === 1 ? 1 : 0;
  const other = index === 0 ? 1 : 0;
  const target = clamp(teamSlot, 0, Math.max(0, teamSize - 1));
  if (normalized[other] === target) normalized[other] = normalized[index];
  normalized[index] = target;
  return normalized;
}

export function includeBattleDirectHudSlot(slots: number[], selectedSlot: number, teamSize: number): number[] {
  const normalized = normalizeBattleDirectHudSlots(slots, teamSize);
  const target = clamp(selectedSlot, 0, Math.max(0, teamSize - 1));
  return normalized.includes(target) ? normalized : [target, normalized[0]];
}

export function prioritizeBattleDirectHudSlot(slots: number[], selectedSlot: number, teamSize: number): number[] {
  const included = includeBattleDirectHudSlot(slots, selectedSlot, teamSize);
  const target = clamp(selectedSlot, 0, Math.max(0, teamSize - 1));
  return included[0] === target ? included : [target, included[0]];
}

export function battleDirectHudSlotsPerSide(battleType: string): number {
  return battleType === 'DOUBLE' ? 2 : 1;
}

export function normalizeBattleCalculation(value: StoredCalculationSelection | undefined,
  ownTeamSize: number = 6, opponentTeamSize: number = 6): BattleCalculationState {
  const source = (value ?? {}) as BattleCalculationState;
  const defaults = defaultBattleCalculation();
  const ownSlot = clamp(source.ownSlot ?? 0, 0, Math.max(0, ownTeamSize - 1));
  const opponentSlot = clamp(source.opponentSlot ?? 0, 0, Math.max(0, opponentTeamSize - 1));
  const opponentPresetIds: Record<string, string> = { ...(source.opponentPresetIds ?? {}) };
  if (source.selectedPresetId && !opponentPresetIds[String(opponentSlot)]) {
    opponentPresetIds[String(opponentSlot)] = source.selectedPresetId;
  }
  const battleType = source.battleType === 'DOUBLE' ? 'DOUBLE' : 'SINGLE';
  const speed = source.speedLine ?? defaults.speedLine;
  const direct = source.directHud ?? defaults.directHud;
  return {
    ...defaults, ...source,
    direction: source.direction === 'OPPONENT_TO_OWN' ? 'OPPONENT_TO_OWN' : 'OWN_TO_OPPONENT',
    ownSlot, opponentSlot, selectedPresetId: opponentPresetIds[String(opponentSlot)], opponentPresetIds,
    battleType, spread: battleType === 'DOUBLE' && source.spread !== false,
    helpingHand: battleType === 'DOUBLE' && source.helpingHand === true,
    ownConditions: normalizeConditionMap(source.ownConditions, ownTeamSize),
    opponentConditions: normalizeConditionMap(source.opponentConditions, opponentTeamSize),
    speedLine: {
      ownTailwind: speed.ownTailwind === true, opponentTailwind: speed.opponentTailwind === true,
      trickRoom: speed.trickRoom === true,
      ownPokemon: normalizeModifierMap(speed.ownPokemon, ownTeamSize),
      opponentPokemon: normalizeModifierMap(speed.opponentPokemon, opponentTeamSize)
    },
    directHud: {
      ownSlots: normalizeBattleDirectHudSlots(direct.ownSlots, ownTeamSize),
      opponentSlots: normalizeBattleDirectHudSlots(direct.opponentSlots, opponentTeamSize),
      visible: direct.visible !== false
    }
  };
}

export function withBattleCalculationTypeDefaults(state: BattleCalculationState, next: string): BattleCalculationState {
  const battleType = next === 'DOUBLE' ? 'DOUBLE' : 'SINGLE';
  return { ...state, battleType, spread: battleType === 'DOUBLE',
    helpingHand: battleType === 'DOUBLE' && state.helpingHand };
}

export function withOpponentSlot(state: BattleCalculationState, slot: number): BattleCalculationState {
  const remembered: Record<string, string> = { ...state.opponentPresetIds };
  if (state.selectedPresetId) remembered[String(state.opponentSlot)] = state.selectedPresetId;
  return { ...state, opponentSlot: slot, selectedPresetId: remembered[String(slot)],
    opponentPresetIds: remembered };
}

export function withOpponentPreset(state: BattleCalculationState, profileId: string | undefined): BattleCalculationState {
  const remembered: Record<string, string> = { ...state.opponentPresetIds };
  if (profileId) remembered[String(state.opponentSlot)] = profileId;
  else delete remembered[String(state.opponentSlot)];
  return { ...state, selectedPresetId: profileId, opponentPresetIds: remembered };
}

export function battleCondition(state: BattleCalculationState, side: BattleSide,
  slot?: number): BattlePokemonCondition {
  const target = slot ?? (side === 'OWN' ? state.ownSlot : state.opponentSlot);
  const values = side === 'OWN' ? state.ownConditions : state.opponentConditions;
  return normalizeCondition(values[String(target)]);
}

export function withBattleCondition(state: BattleCalculationState, side: BattleSide,
  condition: BattlePokemonCondition, slot?: number): BattleCalculationState {
  const target = slot ?? (side === 'OWN' ? state.ownSlot : state.opponentSlot);
  if (side === 'OWN') return { ...state, ownConditions: { ...state.ownConditions,
    [String(target)]: normalizeCondition(condition) } };
  return { ...state, opponentConditions: { ...state.opponentConditions,
    [String(target)]: normalizeCondition(condition) } };
}

export function speedModifiers(state: BattleCalculationState, side: BattleSide,
  slot?: number): SpeedPokemonModifiers {
  const target = slot ?? (side === 'OWN' ? state.ownSlot : state.opponentSlot);
  const values = side === 'OWN' ? state.speedLine.ownPokemon : state.speedLine.opponentPokemon;
  return normalizeModifiers(values[String(target)]);
}

export function withSpeedModifiers(state: BattleCalculationState, side: BattleSide,
  modifiers: SpeedPokemonModifiers, slot?: number): BattleCalculationState {
  const target = slot ?? (side === 'OWN' ? state.ownSlot : state.opponentSlot);
  const speedLine: SpeedLineState = { ...state.speedLine };
  if (side === 'OWN') speedLine.ownPokemon = { ...speedLine.ownPokemon,
    [String(target)]: normalizeModifiers(modifiers) };
  else speedLine.opponentPokemon = { ...speedLine.opponentPokemon,
    [String(target)]: normalizeModifiers(modifiers) };
  return { ...state, speedLine };
}

export function effectiveSpeed(baseSpeed: number, modifiers: SpeedPokemonModifiers, tailwind: boolean,
  knownChoiceScarf: boolean = false): number {
  const stage = clamp(modifiers.stage, -6, 6);
  const numerator = stage >= 0 ? 2 + stage : 2;
  const denominator = stage >= 0 ? 2 : 2 - stage;
  let speed = Math.floor(Math.max(1, baseSpeed) * numerator / denominator);
  if (modifiers.doubled) speed *= 2;
  if (objectValue(modifiers.choiceScarf, knownChoiceScarf)) speed = Math.floor(speed * 3 / 2);
  if (tailwind) speed *= 2;
  if (modifiers.paralyzed) speed = Math.floor(speed / 2);
  return clamp(speed, 1, 10000);
}

export function buildSpeedLineActions(inputs: SpeedLinePokemonInput[], trickRoom: boolean): SpeedLineAction[] {
  const actions: SpeedLineAction[] = [];
  for (const pokemon of inputs) {
    const first = effectiveSpeed(pokemon.baseSpeed.minimum, pokemon.modifiers, pokemon.tailwind,
      pokemon.knownChoiceScarf);
    const last = effectiveSpeed(pokemon.baseSpeed.maximum, pokemon.modifiers, pokemon.tailwind,
      pokemon.knownChoiceScarf);
    const minimumSpeed = Math.min(first, last);
    const maximumSpeed = Math.max(first, last);
    actions.push({ side: pokemon.side, slot: pokemon.slot, pokemonName: pokemon.name, priority: 0,
      minimumSpeed, maximumSpeed, exactBaseSpeed: pokemon.exactBaseSpeed });
    for (const move of pokemon.priorityMoves.filter((candidate: MoveValue) => (candidate.priority ?? 0) > 0)) {
      actions.push({ side: pokemon.side, slot: pokemon.slot, pokemonName: pokemon.name,
        moveName: move.entity.displayName ?? move.entity.showdownId, priority: move.priority ?? 0,
        minimumSpeed, maximumSpeed, exactBaseSpeed: pokemon.exactBaseSpeed });
    }
  }
  return actions.sort((left: SpeedLineAction, right: SpeedLineAction): number => {
    if (left.priority !== right.priority) return right.priority - left.priority;
    const leftMiddle = left.minimumSpeed + left.maximumSpeed;
    const rightMiddle = right.minimumSpeed + right.maximumSpeed;
    if (leftMiddle !== rightMiddle) return trickRoom ? leftMiddle - rightMiddle : rightMiddle - leftMiddle;
    if (left.side !== right.side) return left.side === 'OWN' ? -1 : 1;
    if (left.slot !== right.slot) return left.slot - right.slot;
    return (left.moveName ?? '').localeCompare(right.moveName ?? '');
  });
}

export function battleDirectSpeedRangesOverlap(first: SpeedLineAction, second: SpeedLineAction): boolean {
  return Math.max(first.minimumSpeed, second.minimumSpeed) <= Math.min(first.maximumSpeed, second.maximumSpeed);
}

export function battleDirectHudLayoutProfileKey(region: OverlayRegion): string {
  return region.right - region.left >= region.bottom - region.top ? 'landscape' : 'portrait';
}

export function placementFromBounds(region: OverlayRegion, bounds: OverlayRegion): OverlayPlacement {
  const width = Math.max(1, region.right - region.left);
  const height = Math.max(1, region.bottom - region.top);
  return {
    x: Math.max(0, Math.min(1, (bounds.left - region.left) / width)),
    y: Math.max(0, Math.min(1, (bounds.top - region.top) / height)),
    width: Math.max(0, Math.min(1, (bounds.right - bounds.left) / width)),
    height: Math.max(0, Math.min(1, (bounds.bottom - bounds.top) / height))
  };
}

export function resolvePlacement(region: OverlayRegion, placement: OverlayPlacement,
  minimumWidth: number, minimumHeight: number): OverlayRegion {
  const safeWidth = Math.max(1, region.right - region.left);
  const safeHeight = Math.max(1, region.bottom - region.top);
  const width = clamp(Math.round(safeWidth * Math.max(0, Math.min(1, placement.width))),
    Math.min(minimumWidth, safeWidth), safeWidth);
  const height = clamp(Math.round(safeHeight * Math.max(0, Math.min(1, placement.height))),
    Math.min(minimumHeight, safeHeight), safeHeight);
  const proposedLeft = region.left + Math.round(safeWidth * Math.max(0, Math.min(1, placement.x)));
  const proposedTop = region.top + Math.round(safeHeight * Math.max(0, Math.min(1, placement.y)));
  const left = clamp(proposedLeft, region.left, Math.max(region.left, region.right - width));
  const top = clamp(proposedTop, region.top, Math.max(region.top, region.bottom - height));
  return { left, top, right: left + width, bottom: top + height };
}

function entityJson(entity: EntityRef): object {
  return { entityType: entity.entityType, canonicalId: entity.canonicalId,
    showdownId: entity.showdownId, displayName: entity.displayName ?? entity.showdownId, source: 'user' };
}

function stagesJson(stages: BattleStatStages): object | undefined {
  const result: Record<string, number> = {};
  for (const key of ['atk', 'def', 'spa', 'spd', 'spe']) {
    const value = stages[key as keyof BattleStatStages];
    if (value !== 0) result[key] = value;
  }
  return Object.keys(result).length > 0 ? result : undefined;
}

function engineBuild(build: PokemonBuild, condition: BattlePokemonCondition): object {
  const result: Record<string, object | string | number> = {
    species: entityJson(build.species), level: build.level,
    actualStats: build.actualStats ?? {}, statPoints: build.statPoints ?? {},
    moves: build.moves.map((move: MoveValue) => ({ move: entityJson(move.entity), source: 'OWN_BUILD' }))
  };
  if (build.ability) result.ability = entityJson(build.ability);
  if (build.item) result.item = entityJson(build.item);
  const stages = stagesJson(condition.stages);
  if (stages) result.statStages = stages;
  if (condition.burned) result.status = 'brn';
  return result;
}

function profileJson(profile: OpponentProfile, condition: BattlePokemonCondition): object {
  const result: Record<string, object | string | number | boolean> = {
    profileId: profile.profileId, profileName: profile.profileName, source: profile.source,
    isSelected: true, includedInEnvelope: true, level: profile.level ?? 50,
    statPoints: profile.statPoints ?? {}, actualStats: profile.actualStats ?? {},
    moves: (profile.moves ?? []).map((move: MoveValue) => ({ move: entityJson(move.entity), source: 'PROFILE_PRESET' }))
  };
  if (profile.statAlignment) result.statAlignment = entityJson(profile.statAlignment);
  if (profile.ability) result.ability = entityJson(profile.ability);
  if (profile.item) result.item = entityJson(profile.item);
  const stages = stagesJson(condition.stages);
  if (stages) result.statStages = stages;
  if (condition.burned) result.status = 'brn';
  return result;
}

export function buildBattleDamageRequest(context: BattleDamageRequestContext): string {
  const state = context.calculation;
  const ownCondition = battleCondition(state, 'OWN');
  const opponentCondition = battleCondition(state, 'OPPONENT');
  const battle: Record<string, object | string | boolean> = {
    battleType: state.battleType, weather: state.weather, terrain: state.terrain,
    isCritical: state.critical, isSpreadMove: state.battleType === 'DOUBLE' && state.spread
  };
  const request: Record<string, object | string> = {
    requestId: context.requestId ?? `harmony-battle-${Date.now()}`,
    calculationDirection: state.direction, calculationMode: 'TEMPLATE', battle
  };
  const opponentEntity = entityJson(context.opponent);
  if (state.direction === 'OPPONENT_TO_OWN') {
    request.attackerSide = 'OPPONENT'; request.defenderSide = 'OWN';
    request.attackerIdentity = { species: opponentEntity };
    request.attackerProfileSet = { attackerSpecies: opponentEntity, selectedProfileId: context.preset.profileId,
      profiles: [profileJson(context.preset, opponentCondition)] };
    request.attackerLegalMovePool = { species: opponentEntity,
      rulesetVersion: 'pkmn-mods-champions-0.10.11', source: 'CHAMPIONS_SNAPSHOT',
      learnableMoves: context.legalMoves.map((move: MoveValue) => entityJson(move.entity)) };
    request.defender = engineBuild(context.own, ownCondition);
    request.moveSelection = { mode: 'ONE_MOVE', moveId: state.selectedMoveId ?? '',
      source: 'OPPONENT_LEGAL_MOVE_POOL', legalMovePoolVersion: 'pkmn-mods-champions-0.10.11' };
    battle.attackerSideConditions = { helpingHand: state.battleType === 'DOUBLE' && state.helpingHand };
    battle.defenderSideConditions = { reflect: state.ownReflect, lightScreen: state.ownLightScreen,
      auroraVeil: state.ownAuroraVeil, protected: state.ownProtected };
  } else {
    request.attackerSide = 'OWN'; request.defenderSide = 'OPPONENT';
    request.attacker = engineBuild(context.own, ownCondition);
    request.defenderIdentity = { species: opponentEntity };
    request.defenderProfileSet = { defenderSpecies: opponentEntity, selectedProfileId: context.preset.profileId,
      profiles: [profileJson(context.preset, opponentCondition)] };
    request.moveSelection = context.allOwnMoves ? { mode: 'ALL_ATTACKER_MOVES' } :
      { mode: 'ONE_MOVE', moveId: state.selectedMoveId ?? '' };
    battle.attackerSideConditions = { helpingHand: state.battleType === 'DOUBLE' && state.helpingHand };
    battle.defenderSideConditions = { reflect: state.opponentReflect, lightScreen: state.opponentLightScreen,
      auroraVeil: state.opponentAuroraVeil, protected: state.opponentProtected };
  }
  return JSON.stringify(request);
}

function normalizedId(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '');
}

export function battleDamageCacheKey(request: string): string {
  const parsed = JSON.parse(request) as Record<string, object | string>;
  delete parsed.requestId;
  return JSON.stringify(parsed);
}

export function parseBattleDirectDamageValues(raw: string, configuredMoves: MoveValue[]): string[] {
  const envelope = JSON.parse(raw) as Record<string, object | boolean>;
  if (envelope.ok !== true) return ['1 ?', '2 ?', '3 ?', '4 ?'];
  const result = envelope.result as Record<string, object[]>;
  const values = result.moveResults ?? [];
  return [0, 1, 2, 3].map((index: number): string => {
    const move = configuredMoves[index];
    if (!move) return `${index + 1} —`;
    const found = values.find((entry: object): boolean => {
      const value = entry as Record<string, object | string>;
      return [move.entity.canonicalId, move.entity.showdownId, move.entity.displayName ?? '']
        .map(normalizedId).includes(normalizedId(String(value.moveId ?? '')));
    }) as Record<string, object | string> | undefined;
    if (!found) return `${index + 1} ?`;
    if (found.moveCategory === 'Status') return `${index + 1} —`;
    const range = found.selectedProfileRange as Record<string, number> | undefined;
    if (!range || !Number.isFinite(range.minPercent) || !Number.isFinite(range.maxPercent)) return `${index + 1} ?`;
    if (range.maxPercent <= 0) return `${index + 1} 0%`;
    return `${index + 1} ${range.minPercent.toFixed(1)}–${range.maxPercent.toFixed(1)}%`;
  });
}

export function battleStatusText(state: BattleCalculationState): string {
  const values: string[] = [];
  const weather: Record<string, string> = { RAIN: '雨', SUN: '晴', SAND: '沙暴', SNOW: '雪' };
  const terrain: Record<string, string> = { ELECTRIC: '电场', GRASSY: '青草场地',
    PSYCHIC: '精神场地', MISTY: '薄雾场地' };
  if (weather[state.weather]) values.push(weather[state.weather]);
  if (terrain[state.terrain]) values.push(terrain[state.terrain]);
  if (state.speedLine.ownTailwind) values.push('我方顺风');
  if (state.speedLine.opponentTailwind) values.push('对方顺风');
  if (state.speedLine.trickRoom) values.push('戏法空间');
  if (state.ownReflect || state.ownLightScreen || state.ownAuroraVeil) values.push('我方墙');
  if (state.opponentReflect || state.opponentLightScreen || state.opponentAuroraVeil) values.push('对方墙');
  return `状态：${values.length > 0 ? values.join(' · ') : '默认'}`;
}
