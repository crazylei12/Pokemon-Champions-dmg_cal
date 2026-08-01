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
      type: move.type
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

export function userProfilesForSpecies(root: UserOpponentPresetRoot, species: EntityRef): OpponentProfile[] {
  const id = normalizeShowdownId(species.showdownId);
  return root.presets.filter((entry: StoredOpponentPresetEntry) => normalizeShowdownId(entry.speciesId) === id)
    .map((entry: StoredOpponentPresetEntry) => profileFromStored(entry));
}

export function makePokemonBuild(repository: RuntimeDataRepository, species: EntityRef,
  profile?: OpponentProfile): PokemonBuild {
  const selected = profile ?? repository.profilesFor(species.showdownId)[0];
  const points = selected?.statPoints ?? {};
  const actualStats = selected?.actualStats ?? repository.actualStatsFor(species.showdownId, points,
    selected?.statAlignment);
  const legal = repository.legalMovesFor(species.showdownId);
  const profileMoves = selected?.moves ?? [];
  const moves = profileMoves.length > 0 ? profileMoves.slice(0, 4) :
    legal.filter((move: MoveValue) => (move.basePower ?? 0) > 0).slice(0, 4);
  return {
    species,
    level: selected?.level ?? 50,
    actualStats,
    statPoints: points,
    ability: selected?.ability ?? repository.abilitiesFor(species.showdownId)[0],
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
  if (value === 'No direct damage.') return '无直接伤害';
  return value
    .replace(/guaranteed OHKO/ig, '必定一击击倒')
    .replace(/possible OHKO/ig, '可能一击击倒')
    .replace(/guaranteed (\d+)HKO/ig, '必定 $1 次击倒')
    .replace(/possible (\d+)HKO/ig, '可能 $1 次击倒');
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
        assumptions: move.assumptions ?? []
      };
    }),
    warnings: result.warnings.map((warning: EngineWarning) => warning.message ?? warning.code ?? '计算提示')
  };
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
