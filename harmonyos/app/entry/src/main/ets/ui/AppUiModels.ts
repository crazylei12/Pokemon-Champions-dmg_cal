import {
  BattleCondition,
  EntityRef,
  EntityType,
  MoveSource,
  MoveValue,
  OpponentProfile,
  PokemonBuild,
  SideConditions,
  StatValues
} from '../domain/Models';
import { normalizeShowdownId } from '../domain/EntityCatalog';
import {
  defaultAbilityForTarget,
  isSpeedLinePriorityMove,
  transformActualStats,
  userOpponentPresetSharingForms
} from '../domain/PresetLogic';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import {
  StoredEntity,
  StoredMove,
  StoredOpponentPresetEntry,
  StoredPokemon,
  StoredTeam,
  UserOpponentPresetRoot
} from '../storage/StorageContracts';

export interface TeamDisplayModel {
  id: string;
  name: string;
  speciesSummary: string;
  damageReady: boolean;
  issues: string[];
  pokemon: PokemonBuild[];
  stored: StoredTeam;
}

export interface ManualBattleOptions {
  direction: string;
  selectedMoveId: string;
  battleType: string;
  weather: string;
  terrain: string;
  ownReflect: boolean;
  ownLightScreen: boolean;
  opponentReflect: boolean;
  opponentLightScreen: boolean;
  critical: boolean;
  spread: boolean;
}

export interface CalculationMoveResult {
  name: string;
  minPercent: number;
  maxPercent: number;
  minDamage: number;
  maxDamage: number;
  koText: string;
  assumptions: string[];
}

export interface CalculationDisplayResult {
  direction: string;
  attacker: string;
  defender: string;
  moves: CalculationMoveResult[];
  warnings: string[];
}

export interface SafeAreaInsets {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface WindowBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface OcclusionRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

interface DamageRequestEnvelope {
  requestId?: string;
}

export class StringLruCache {
  private values: Map<string, string> = new Map<string, string>();
  private order: string[] = [];
  private capacity: number;

  constructor(capacity: number) {
    this.capacity = Math.max(1, Math.round(capacity));
  }

  get(key: string): string | undefined {
    const value = this.values.get(key);
    if (value === undefined) return undefined;
    this.touch(key);
    return value;
  }

  set(key: string, value: string): void {
    this.values.set(key, value);
    this.touch(key);
    while (this.order.length > this.capacity) {
      const oldest = this.order.shift();
      if (oldest !== undefined) this.values.delete(oldest);
    }
  }

  clear(): void {
    this.values.clear();
    this.order = [];
  }

  size(): number {
    return this.order.length;
  }

  private touch(key: string): void {
    const index = this.order.indexOf(key);
    if (index >= 0) this.order.splice(index, 1);
    this.order.push(key);
  }
}

export function damageRequestCacheKey(request: string): string {
  const envelope = JSON.parse(request) as DamageRequestEnvelope;
  envelope.requestId = '';
  return JSON.stringify(envelope);
}

export class BattlePanelNavigation {
  private currentPage: string = 'DAMAGE';
  private visible: boolean = false;

  page(): string {
    return this.currentPage;
  }

  isVisible(): boolean {
    return this.visible;
  }

  show(page: string): void {
    this.currentPage = page;
    this.visible = true;
  }

  collapse(): void {
    this.visible = false;
  }

  reopen(): string {
    this.visible = true;
    return this.currentPage;
  }

  resetForTeamRecognition(): void {
    this.currentPage = 'DAMAGE';
    this.visible = false;
  }
}

interface EngineDamageRange {
  minPercent: number;
  maxPercent: number;
  minDamage: number;
  maxDamage: number;
}

interface EngineMoveResult {
  moveName: string;
  selectedProfileRange: EngineDamageRange;
  koSummary?: EngineKoSummary;
  assumptions?: string[];
}

interface EngineKoSummary {
  text?: string;
}

interface EngineWarning {
  code?: string;
  message?: string;
}

interface EngineCalculationResult {
  calculationDirection: string;
  attackerSummary: EngineAttackerSummary;
  defenderIdentity: EngineDefenderIdentity;
  moveResults: EngineMoveResult[];
  warnings: EngineWarning[];
}

interface EngineAttackerSummary {
  speciesName: string;
}

interface EngineDefenderIdentity {
  species: EntityRef;
}

interface EngineEnvelope {
  ok: boolean;
  result?: EngineCalculationResult;
  error?: EngineError;
}

interface EngineError {
  message?: string;
}

interface EngineProfileMove {
  move: EntityRef;
  source: string;
}

interface EngineProfile {
  profileId: string;
  profileName: string;
  source: string;
  isSelected: boolean;
  includedInEnvelope?: boolean;
  level: number;
  actualStats?: StatValues;
  statPoints?: StatValues;
  ability?: EntityRef;
  item?: EntityRef;
  moves?: EngineProfileMove[];
}

interface EnginePokemonBuild {
  species: EntityRef;
  level: number;
  actualStats: StatValues;
  statPoints: StatValues;
  ability?: EntityRef;
  item?: EntityRef;
  moves: EngineProfileMove[];
}

interface EngineProfileSet {
  attackerSpecies?: EntityRef;
  defenderSpecies?: EntityRef;
  selectedProfileId: string;
  profiles: EngineProfile[];
}

interface EngineLegalMovePool {
  species: EntityRef;
  rulesetVersion: string;
  source: string;
  learnableMoves: EntityRef[];
}

interface EngineMoveSelection {
  mode: string;
  moveId: string;
  source?: string;
  legalMovePoolVersion?: string;
}

interface OpponentToOwnRequest {
  requestId: string;
  calculationDirection: string;
  calculationMode: string;
  attackerSide: string;
  defenderSide: string;
  attackerIdentity: EngineDefenderIdentity;
  attackerProfileSet: EngineProfileSet;
  defender: EnginePokemonBuild;
  attackerLegalMovePool: EngineLegalMovePool;
  moveSelection: EngineMoveSelection;
  battle: BattleCondition;
}

interface OwnToOpponentRequest {
  requestId: string;
  calculationDirection: string;
  calculationMode: string;
  attackerSide: string;
  defenderSide: string;
  attacker: EnginePokemonBuild;
  defenderIdentity: EngineDefenderIdentity;
  defenderProfileSet: EngineProfileSet;
  moveSelection: EngineMoveSelection;
  battle: BattleCondition;
}

function entityFromStored(value: StoredEntity, fallbackType: EntityType): EntityRef {
  return {
    entityType: (value.entityType ?? fallbackType) as EntityType,
    canonicalId: value.canonicalId,
    showdownId: value.showdownId,
    displayName: value.displayName ?? value.showdownId,
    source: 'user'
  };
}

function entityToStored(value: EntityRef): StoredEntity {
  return {
    entityType: value.entityType,
    canonicalId: value.canonicalId,
    showdownId: value.showdownId,
    displayName: value.displayName ?? value.showdownId
  };
}

function moveFromStored(value: StoredMove): MoveValue {
  return {
    entity: entityFromStored(value.move, 'move'),
    basePower: value.basePower,
    type: value.type,
    priority: value.priority,
    source: (value.source ?? 'MANUAL_OVERRIDE') as MoveSource
  };
}

export function pokemonFromStored(value: StoredPokemon): PokemonBuild {
  const source = value.build ?? value;
  return {
    species: entityFromStored(source.species, 'species'),
    level: Math.max(1, Math.min(100, source.level ?? 50)),
    actualStats: source.actualStats ?? {},
    statPoints: source.statPoints ?? {},
    ability: source.ability ? entityFromStored(source.ability, 'ability') : undefined,
    item: source.item ? entityFromStored(source.item, 'item') : undefined,
    moves: (source.moves ?? []).map((move: StoredMove) => moveFromStored(move))
  };
}

export function pokemonToStored(value: PokemonBuild): StoredPokemon {
  const moves: StoredMove[] = value.moves.slice(0, 4).map((move: MoveValue): StoredMove => {
    return {
      move: entityToStored(move.entity),
      source: move.source ?? 'MANUAL_CURRENT',
      basePower: move.basePower,
      type: move.type,
      priority: move.priority
    };
  });
  const result: StoredPokemon = {
    species: entityToStored(value.species),
    level: Math.max(1, Math.min(100, value.level)),
    actualStats: value.actualStats ?? {},
    statPoints: value.statPoints ?? {},
    moves
  };
  if (value.ability) result.ability = entityToStored(value.ability);
  if (value.item) result.item = entityToStored(value.item);
  return result;
}

function statsComplete(stats: StatValues | undefined): boolean {
  if (!stats) return false;
  return (stats.hp ?? 0) > 0 && (stats.atk ?? 0) > 0 && (stats.def ?? 0) > 0 &&
    (stats.spa ?? 0) > 0 && (stats.spd ?? 0) > 0 && (stats.spe ?? 0) > 0;
}

export function toTeamDisplay(team: StoredTeam): TeamDisplayModel {
  const pokemon = (team.pokemon ?? team.members ?? []).map((value: StoredPokemon) => pokemonFromStored(value));
  const issues: string[] = [];
  if (pokemon.length !== 6) issues.push('队伍必须包含 6 只宝可梦');
  pokemon.forEach((entry: PokemonBuild, index: number) => {
    if (!statsComplete(entry.actualStats)) issues.push(`${index + 1}. ${entry.species.displayName}：六项实际能力值未完整填写`);
    if (!entry.ability) issues.push(`${index + 1}. ${entry.species.displayName}：未设置特性`);
    if (entry.moves.length === 0) issues.push(`${index + 1}. ${entry.species.displayName}：至少需要一个招式`);
  });
  return {
    id: team.savedTeamId,
    name: team.teamSlotName ?? team.teamName ?? team.savedTeamId,
    speciesSummary: pokemon.map((entry: PokemonBuild) => entry.species.displayName ?? entry.species.showdownId).join(' / '),
    damageReady: issues.length === 0,
    issues,
    pokemon,
    stored: team
  };
}

export function teamWithName(team: StoredTeam, name: string): StoredTeam {
  const normalized = name.trim();
  if (normalized.length === 0) throw new Error('队伍名称不能为空');
  if (normalized.length > 30) throw new Error('队伍名称不能超过 30 个字符');
  const result: StoredTeam = JSON.parse(JSON.stringify(team)) as StoredTeam;
  result.teamName = normalized;
  result.teamSlotName = normalized;
  return result;
}

export function teamWithPokemon(team: StoredTeam, slot: number, pokemon: PokemonBuild): StoredTeam {
  const members = [...(team.pokemon ?? team.members ?? [])];
  if (slot < 0 || slot >= members.length) throw new Error('队伍槽位无效');
  members[slot] = pokemonToStored(pokemon);
  const result: StoredTeam = JSON.parse(JSON.stringify(team)) as StoredTeam;
  result.pokemon = members;
  result.members = undefined;
  return result;
}

export function profileFromStored(entry: StoredOpponentPresetEntry): OpponentProfile {
  return {
    profileId: entry.preset.profileId,
    profileName: entry.preset.profileName,
    source: 'USER_SAVED',
    level: entry.preset.level,
    statPoints: entry.preset.statPoints,
    actualStats: entry.preset.actualStats,
    statAlignment: entry.preset.statAlignment ? entityFromStored(entry.preset.statAlignment, 'nature') : undefined,
    ability: entry.preset.ability ? entityFromStored(entry.preset.ability, 'ability') : undefined,
    item: entry.preset.item ? entityFromStored(entry.preset.item, 'item') : undefined,
    moves: entry.preset.moves.map((move: StoredMove) => moveFromStored(move))
  };
}

export class AsyncRequestGate {
  private generation: number = 0;
  private active: boolean = true;

  activate(): void {
    this.active = true;
  }

  begin(): number {
    this.generation += 1;
    return this.generation;
  }

  supersede(): void {
    this.generation += 1;
  }

  isCurrent(generation: number): boolean {
    return this.active && generation === this.generation;
  }

  dispose(): void {
    this.active = false;
    this.generation += 1;
  }
}

export function manualCalculationFingerprint(own: PokemonBuild, opponent: PokemonBuild,
  options: ManualBattleOptions): string {
  return JSON.stringify({ own, opponent, options });
}

export function legalMoveWithMetadata(repository: RuntimeDataRepository, species: EntityRef,
  selected: EntityRef, source?: MoveSource): MoveValue {
  const id = normalizeShowdownId(selected.showdownId);
  const matched = repository.legalMovesFor(species.showdownId).find((move: MoveValue) =>
    normalizeShowdownId(move.entity.showdownId) === id);
  if (!matched) return { entity: selected, source: source ?? 'MANUAL_OVERRIDE' };
  return {
    entity: matched.entity,
    basePower: matched.basePower,
    type: matched.type,
    priority: matched.priority,
    source: source ?? 'MANUAL_OVERRIDE'
  };
}

export function priorityMovesForSpecies(repository: RuntimeDataRepository, species: EntityRef,
  preferred: MoveValue[] = []): MoveValue[] {
  const merged: MoveValue[] = [];
  for (const move of [...preferred, ...repository.legalMovesFor(species.showdownId)]) {
    if (!merged.some((entry: MoveValue) => normalizeShowdownId(entry.entity.showdownId) ===
      normalizeShowdownId(move.entity.showdownId))) merged.push(move);
  }
  return merged.filter((move: MoveValue) => isSpeedLinePriorityMove(move));
}

function adaptProfileForSpecies(repository: RuntimeDataRepository, species: EntityRef,
  profile: OpponentProfile, exactSpecies: boolean): OpponentProfile {
  const target = repository.formFor(species.showdownId);
  const abilities = repository.abilitiesFor(species.showdownId);
  const result: OpponentProfile = {
    profileId: profile.profileId,
    profileName: profile.profileName,
    source: profile.source,
    level: profile.level,
    statPoints: profile.statPoints,
    statAlignment: profile.statAlignment,
    ability: defaultAbilityForTarget(profile.ability, abilities, target?.defaultAbility),
    item: profile.item,
    moves: (profile.moves ?? []).map((move: MoveValue) =>
      legalMoveWithMetadata(repository, species, move.entity, move.source))
      .filter((move: MoveValue) => repository.legalMovesFor(species.showdownId).some((legal: MoveValue) =>
        normalizeShowdownId(legal.entity.showdownId) === normalizeShowdownId(move.entity.showdownId)))
  };
  result.actualStats = exactSpecies && profile.actualStats ? profile.actualStats :
    repository.actualStatsFor(species.showdownId, profile.statPoints ?? {}, profile.statAlignment);
  return result;
}

export function userProfilesForSpecies(root: UserOpponentPresetRoot, species: EntityRef,
  repository?: RuntimeDataRepository): OpponentProfile[] {
  const id = normalizeShowdownId(species.showdownId);
  if (!repository) {
    return root.presets.filter((entry: StoredOpponentPresetEntry) => normalizeShowdownId(entry.speciesId) === id)
      .map((entry: StoredOpponentPresetEntry) => profileFromStored(entry));
  }
  const target = repository.formFor(species.showdownId);
  const sharedIds = target ? userOpponentPresetSharingForms(target, repository.formsFor(species.showdownId))
    .map((form) => normalizeShowdownId(form.species.showdownId)) : [id];
  return root.presets.filter((entry: StoredOpponentPresetEntry) =>
    sharedIds.includes(normalizeShowdownId(entry.speciesId)))
    .map((entry: StoredOpponentPresetEntry) => adaptProfileForSpecies(repository, species,
      profileFromStored(entry), normalizeShowdownId(entry.speciesId) === id));
}

export function buildsShareConfiguration(repository: RuntimeDataRepository, source: EntityRef,
  target: EntityRef): boolean {
  const sourceForm = repository.formFor(source.showdownId);
  const targetId = normalizeShowdownId(target.showdownId);
  return !!sourceForm && userOpponentPresetSharingForms(sourceForm, repository.formsFor(source.showdownId))
    .some((form) => normalizeShowdownId(form.species.showdownId) === targetId);
}

export function adaptPokemonBuildForSpecies(repository: RuntimeDataRepository, source: PokemonBuild,
  target: EntityRef): PokemonBuild {
  if (!buildsShareConfiguration(repository, source.species, target)) return makePokemonBuild(repository, target);
  const sourceForm = repository.formFor(source.species.showdownId);
  const targetForm = repository.formFor(target.showdownId);
  return {
    species: target,
    level: source.level,
    statPoints: source.statPoints,
    actualStats: sourceForm && targetForm ? transformActualStats(source.actualStats ?? {}, sourceForm.baseStats,
      targetForm.baseStats) : repository.actualStatsFor(target.showdownId, source.statPoints ?? {}),
    ability: defaultAbilityForTarget(source.ability, repository.abilitiesFor(target.showdownId),
      targetForm?.defaultAbility),
    item: source.item,
    moves: source.moves.map((move: MoveValue) => legalMoveWithMetadata(repository, target, move.entity, move.source))
      .filter((move: MoveValue) => repository.legalMovesFor(target.showdownId).some((legal: MoveValue) =>
        normalizeShowdownId(legal.entity.showdownId) === normalizeShowdownId(move.entity.showdownId)))
      .slice(0, 4)
  };
}

export function makePokemonBuild(repository: RuntimeDataRepository, species: EntityRef,
  profile?: OpponentProfile): PokemonBuild {
  const selected = profile ?? repository.profilesFor(species.showdownId)[0];
  const points = selected?.statPoints ?? {};
  const actualStats = selected?.actualStats ?? repository.actualStatsFor(species.showdownId, points,
    selected?.statAlignment);
  const legal = repository.legalMovesFor(species.showdownId);
  const profileMoves = selected?.moves ?? [];
  const compatibleProfileMoves = profileMoves.map((move: MoveValue) =>
    legalMoveWithMetadata(repository, species, move.entity, move.source))
    .filter((move: MoveValue) => legal.some((candidate: MoveValue) => normalizeShowdownId(candidate.entity.showdownId) ===
      normalizeShowdownId(move.entity.showdownId)));
  const moves = compatibleProfileMoves.length > 0 ? compatibleProfileMoves.slice(0, 4) :
    legal.filter((move: MoveValue) => (move.basePower ?? 0) > 0).slice(0, 4);
  return {
    species,
    level: selected?.level ?? 50,
    actualStats,
    statPoints: points,
    ability: defaultAbilityForTarget(selected?.ability, repository.abilitiesFor(species.showdownId),
      repository.formFor(species.showdownId)?.defaultAbility),
    item: selected?.item,
    moves
  };
}

function engineBuild(build: PokemonBuild, moveSource: string): EnginePokemonBuild {
  const result: EnginePokemonBuild = {
    species: build.species,
    level: Math.max(1, Math.min(100, build.level)),
    actualStats: build.actualStats ?? {},
    statPoints: build.statPoints ?? {},
    moves: build.moves.map((move: MoveValue): EngineProfileMove => {
      return { move: move.entity, source: moveSource };
    })
  };
  if (build.ability) result.ability = build.ability;
  if (build.item) result.item = build.item;
  return result;
}

function exactProfile(build: PokemonBuild, id: string, includeMoves: boolean): EngineProfile {
  const profile: EngineProfile = {
    profileId: id,
    profileName: '手动精确配置',
    source: 'MANUAL_CURRENT',
    isSelected: true,
    includedInEnvelope: true,
    level: build.level,
    actualStats: build.actualStats,
    statPoints: build.statPoints,
    ability: build.ability,
    item: build.item
  };
  if (includeMoves) profile.moves = build.moves.map((move: MoveValue): EngineProfileMove => {
    return { move: move.entity, source: 'PROFILE_PRESET' };
  });
  return profile;
}

export function buildManualDamageRequest(own: PokemonBuild, opponent: PokemonBuild,
  options: ManualBattleOptions): string {
  const attackerSideConditions: SideConditions = {};
  const defenderSideConditions: SideConditions = {};
  const battle: BattleCondition = {
    battleType: options.battleType === 'DOUBLE' ? 'DOUBLE' : 'SINGLE',
    weather: options.weather,
    terrain: options.terrain,
    isCritical: options.critical,
    isSpreadMove: options.spread && options.battleType === 'DOUBLE',
    attackerSideConditions,
    defenderSideConditions
  };
  if (options.direction === 'OPPONENT_TO_OWN') {
    battle.defenderSideConditions = { reflect: options.ownReflect, lightScreen: options.ownLightScreen };
    const attackerIdentity: EngineDefenderIdentity = { species: opponent.species };
    const attackerProfileSet: EngineProfileSet = {
      attackerSpecies: opponent.species,
      selectedProfileId: 'manual-exact-attacker',
      profiles: [exactProfile(opponent, 'manual-exact-attacker', true)]
    };
    const attackerLegalMovePool: EngineLegalMovePool = {
      species: opponent.species,
      rulesetVersion: 'provided-team-v1',
      source: 'USER_PATCH',
      learnableMoves: opponent.moves.map((move: MoveValue) => move.entity)
    };
    const moveSelection: EngineMoveSelection = {
      mode: 'ONE_MOVE',
      moveId: options.selectedMoveId,
      source: 'OPPONENT_LEGAL_MOVE_POOL',
      legalMovePoolVersion: 'provided-team-v1'
    };
    const request: OpponentToOwnRequest = {
      requestId: `harmony-manual-${Date.now()}`,
      calculationDirection: 'OPPONENT_TO_OWN',
      calculationMode: 'EXACT',
      attackerSide: 'OPPONENT',
      defenderSide: 'OWN',
      attackerIdentity,
      attackerProfileSet,
      defender: engineBuild(own, 'OWN_BUILD'),
      attackerLegalMovePool,
      moveSelection,
      battle
    };
    return JSON.stringify(request);
  }
  battle.defenderSideConditions = { reflect: options.opponentReflect, lightScreen: options.opponentLightScreen };
  const defenderIdentity: EngineDefenderIdentity = { species: opponent.species };
  const defenderProfileSet: EngineProfileSet = {
    defenderSpecies: opponent.species,
    selectedProfileId: 'manual-exact-defender',
    profiles: [exactProfile(opponent, 'manual-exact-defender', false)]
  };
  const moveSelection: EngineMoveSelection = { mode: 'ONE_MOVE', moveId: options.selectedMoveId };
  const request: OwnToOpponentRequest = {
    requestId: `harmony-manual-${Date.now()}`,
    calculationDirection: 'OWN_TO_OPPONENT',
    calculationMode: 'EXACT',
    attackerSide: 'OWN',
    defenderSide: 'OPPONENT',
    attacker: engineBuild(own, 'OWN_BUILD'),
    defenderIdentity,
    defenderProfileSet,
    moveSelection,
    battle
  };
  return JSON.stringify(request);
}

function localizeKoText(value: string): string {
  if (value === 'No direct damage.') return '无直接伤害（属性免疫或变化招式）';
  return value
    .replace(/guaranteed OHKO/ig, '必定一击击倒')
    .replace(/possible OHKO/ig, '可能一击击倒')
    .replace(/guaranteed (\d+)HKO/ig, '必定 $1 次击倒')
    .replace(/possible (\d+)HKO/ig, '可能 $1 次击倒');
}

function localizeCalculationText(value: string): string {
  return value
    .replace(/^Defender profile:\s*/i, '防守方配置：')
    .replace(/^Attacker profile:\s*/i, '攻击方配置：')
    .replace(/^Defender ability is unspecified\.$/i, '防守方特性未指定。')
    .replace(/^Defender item is unspecified\.$/i, '防守方道具未指定。')
    .replace(/^Defender Stat Points use calculator defaults\.$/i, '防守方能力点使用计算器默认值。')
    .replace(/^No direct damage\.$/i, '无直接伤害（属性免疫或变化招式）。');
}

function localizeEngineWarning(warning: EngineWarning): string {
  const labels: Record<string, string> = {
    NO_ATTACKER_PROFILE: '未提供对手攻击方配置，已使用空白当前配置。',
    NO_SELECTED_ATTACKER_PROFILE: '指定的对手攻击方配置不存在，已使用第一项。',
    NO_DEFENDER_PROFILES: '未提供防守方配置，已使用空白当前配置。',
    NO_SELECTED_PROFILE: '指定的防守方配置不存在，已使用第一项。',
    NO_ATTACKER_MOVES: '没有招式与本次计算请求匹配。',
    NO_OPPONENT_MOVE_SELECTED: '我方承伤计算需要先选择对手招式。',
    LEGAL_MOVE_POOL_MISSING: '未提供对手的合法招式池。',
    ILLEGAL_OPPONENT_MOVE: '所选对手招式不在当前合法招式池中。',
    SPECIES_NOT_FOUND: '伤害计算数据中找不到所选宝可梦。',
    MOVE_NOT_FOUND: '伤害计算数据中找不到所选招式。',
    CUSTOM_FLAGS_NOT_APPLIED: '自定义战场标记已保留，但当前计算适配器不会应用它们。',
    EMPTY_ENVELOPE: '没有配置纳入伤害包络，已改用全部配置。',
    ACTUAL_STATS_APPROXIMATED: '本次计算已把实际能力值换算为近似基础能力。'
  };
  if (warning.code && labels[warning.code]) return labels[warning.code];
  const message = warning.message ?? '';
  if (message.length > 0 && !/[A-Za-z]{3}/.test(message)) return message;
  return warning.code ? '计算提示（未分类）' : '计算提示';
}

export function parseCalculationResult(raw: string): CalculationDisplayResult {
  const envelope = JSON.parse(raw) as EngineEnvelope;
  if (!envelope.ok || !envelope.result) throw new Error(envelope.error?.message ?? '伤害计算失败');
  const result = envelope.result;
  return {
    direction: result.calculationDirection,
    attacker: result.attackerSummary.speciesName,
    defender: result.defenderIdentity.species.displayName ?? result.defenderIdentity.species.showdownId ?? '未知目标',
    moves: result.moveResults.map((move: EngineMoveResult): CalculationMoveResult => {
      return {
        name: move.moveName,
        minPercent: move.selectedProfileRange.minPercent,
        maxPercent: move.selectedProfileRange.maxPercent,
        minDamage: move.selectedProfileRange.minDamage,
        maxDamage: move.selectedProfileRange.maxDamage,
        koText: localizeKoText(move.koSummary?.text ?? ''),
        assumptions: (move.assumptions ?? []).map((assumption: string) => localizeCalculationText(assumption))
      };
    }),
    warnings: result.warnings.map((warning: EngineWarning) => localizeEngineWarning(warning))
  };
}

export function presetSourceLabel(source: string | undefined): string {
  if (source === 'USER_SAVED') return '用户保存';
  if (source === 'CANONICAL') return '内置标准配置';
  if (source === 'MANUAL_CURRENT') return '本局手动配置';
  if (source === 'MANUAL_OVERRIDE') return '用户手动选择';
  if (source === 'OWN_BUILD') return '己方队伍配置';
  if (source === 'PROFILE_PRESET') return '配置预设';
  if (source === 'OPPONENT_LEGAL_MOVE_POOL') return '对手合法招式池';
  if (source === 'POPULAR_USAGE') return '常用配置数据';
  return source && !/[A-Za-z_]{3}/.test(source) ? source : '来源未标注';
}

export function teamSourceLabel(team: StoredTeam): string {
  if (team.importSource === 'HARMONYOS_ALBUM_SCREEN_CAPTURE') return '相册窗口识别并人工核对';
  if (team.importSource === 'ANDROID_SCREEN_CAPTURE') return 'Android 画面识别导入';
  if (team.importSource && !/[A-Za-z_]{3}/.test(team.importSource)) return team.importSource;
  return team.userConfirmed === true ? '用户确认的本地队伍' : '本地保存队伍';
}

export function clampWindowBounds(bounds: WindowBounds, displayWidth: number, displayHeight: number,
  insets: SafeAreaInsets, minimumWidth: number, minimumHeight: number): WindowBounds {
  const left = Math.max(0, Math.round(insets.left));
  const top = Math.max(0, Math.round(insets.top));
  const right = Math.max(0, Math.round(insets.right));
  const bottom = Math.max(0, Math.round(insets.bottom));
  const availableWidth = Math.max(1, Math.round(displayWidth) - left - right);
  const availableHeight = Math.max(1, Math.round(displayHeight) - top - bottom);
  const width = Math.min(availableWidth, Math.max(Math.min(minimumWidth, availableWidth), Math.round(bounds.width)));
  const height = Math.min(availableHeight, Math.max(Math.min(minimumHeight, availableHeight), Math.round(bounds.height)));
  const x = Math.max(left, Math.min(Math.round(displayWidth) - right - width, Math.round(bounds.x)));
  const y = Math.max(top, Math.min(Math.round(displayHeight) - bottom - height, Math.round(bounds.y)));
  return { x, y, width, height };
}

function windowIntersectsRect(bounds: WindowBounds, rect: OcclusionRect): boolean {
  return bounds.x < rect.left + rect.width && bounds.x + bounds.width > rect.left &&
    bounds.y < rect.top + rect.height && bounds.y + bounds.height > rect.top;
}

export function avoidWindowOcclusions(bounds: WindowBounds, displayWidth: number, displayHeight: number,
  insets: SafeAreaInsets, minimumWidth: number, minimumHeight: number,
  occlusions: OcclusionRect[]): WindowBounds {
  let current = clampWindowBounds(bounds, displayWidth, displayHeight, insets, minimumWidth, minimumHeight);
  for (const raw of occlusions) {
    const rect: OcclusionRect = { left: Math.max(0, Math.round(raw.left)), top: Math.max(0, Math.round(raw.top)),
      width: Math.max(0, Math.round(raw.width)), height: Math.max(0, Math.round(raw.height)) };
    if (rect.width === 0 || rect.height === 0 || !windowIntersectsRect(current, rect)) continue;
    const vertical = rect.height >= rect.width;
    if (vertical) {
      const leftWidth = Math.max(0, rect.left - Math.max(0, Math.round(insets.left)));
      const rightStart = rect.left + rect.width;
      const rightWidth = Math.max(0, Math.round(displayWidth) - Math.max(0, Math.round(insets.right)) - rightStart);
      const useLeft = leftWidth >= minimumWidth && (rightWidth < minimumWidth ||
        current.x + current.width / 2 <= rect.left + rect.width / 2);
      const regionWidth = useLeft ? leftWidth : rightWidth;
      if (regionWidth >= minimumWidth) {
        current = clampWindowBounds({ ...current, x: useLeft ? current.x : Math.max(current.x, rightStart),
          width: Math.min(current.width, regionWidth) }, useLeft ? rect.left : displayWidth,
          displayHeight, useLeft ? { ...insets, right: 0 } : { ...insets, left: rightStart },
          minimumWidth, minimumHeight);
      }
    } else {
      const topHeight = Math.max(0, rect.top - Math.max(0, Math.round(insets.top)));
      const bottomStart = rect.top + rect.height;
      const bottomHeight = Math.max(0, Math.round(displayHeight) - Math.max(0, Math.round(insets.bottom)) - bottomStart);
      const useTop = topHeight >= minimumHeight && (bottomHeight < minimumHeight ||
        current.y + current.height / 2 <= rect.top + rect.height / 2);
      const regionHeight = useTop ? topHeight : bottomHeight;
      if (regionHeight >= minimumHeight) {
        current = clampWindowBounds({ ...current, y: useTop ? current.y : Math.max(current.y, bottomStart),
          height: Math.min(current.height, regionHeight) }, displayWidth, useTop ? rect.top : displayHeight,
          useTop ? { ...insets, bottom: 0 } : { ...insets, top: bottomStart }, minimumWidth, minimumHeight);
      }
    }
  }
  return current;
}

export function snapWindowBoundsToEdge(bounds: WindowBounds, displayWidth: number, displayHeight: number,
  insets: SafeAreaInsets, minimumWidth: number, minimumHeight: number): WindowBounds {
  const clamped = clampWindowBounds(bounds, displayWidth, displayHeight, insets, minimumWidth, minimumHeight);
  const left = Math.max(0, Math.round(insets.left));
  const right = Math.max(left, Math.round(displayWidth) - Math.max(0, Math.round(insets.right)) - clamped.width);
  const center = clamped.x + clamped.width / 2;
  return { ...clamped, x: center <= displayWidth / 2 ? left : right };
}

export function presetSearchMatches(entry: StoredOpponentPresetEntry, species: EntityRef | undefined,
  query: string): boolean {
  const normalized = query.trim().toLocaleLowerCase();
  if (normalized.length === 0) return true;
  const showdown = species?.showdownId ?? entry.speciesId;
  const display = species?.displayName ?? showdown;
  return [showdown, display, entry.preset.profileName, entry.speciesId]
    .some((value: string) => value.toLocaleLowerCase().includes(normalized));
}

export function statSummary(stats: StatValues | undefined): string {
  if (!stats) return '未设置';
  const values: string[] = [];
  if (stats.hp !== undefined && stats.hp > 0) values.push(`HP ${stats.hp}`);
  if (stats.atk !== undefined && stats.atk > 0) values.push(`攻击 ${stats.atk}`);
  if (stats.def !== undefined && stats.def > 0) values.push(`防御 ${stats.def}`);
  if (stats.spa !== undefined && stats.spa > 0) values.push(`特攻 ${stats.spa}`);
  if (stats.spd !== undefined && stats.spd > 0) values.push(`特防 ${stats.spd}`);
  if (stats.spe !== undefined && stats.spe > 0) values.push(`速度 ${stats.spe}`);
  return values.length > 0 ? values.join(' / ') : '未设置';
}
