import {
  calculate as smogonCalculate,
  Field as SmogonField,
  Generations,
  Move as SmogonMove,
  Pokemon as SmogonPokemon,
  toID,
  type Result as SmogonResult,
} from '../../external/smogon-damage-calc/calc/dist';

import type {
  BattleSide,
  CalculationDirection,
  DamageCalculationResult,
  DamageEngineFacade,
  DamageRange,
  DamageRequest,
  DamageWarning,
  DefenderProfile,
  EntityRef,
  LegalMovePool,
  KoSummary,
  KnownPokemonBuild,
  MoveBuild,
  MoveDamageResult,
  MoveSource,
  OpponentAttackerProfile,
  OpponentPokemonIdentity,
  PokemonTypeName,
  PokemonSummary,
  ProfileDamageRange,
  SideConditions,
  StatId,
  StatTable,
  StatTableInput,
} from './types';

const CHAMPIONS_GENERATION = 0;
const STATS: StatId[] = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];

interface DefenderTarget {
  identity: OpponentPokemonIdentity;
  profile: DefenderProfile;
  build: KnownPokemonBuild;
}

interface CalculationContext {
  direction: CalculationDirection;
  attackerSide: BattleSide;
  defenderSide: BattleSide;
  attackerBuild: KnownPokemonBuild;
  selectedAttackerProfile?: OpponentAttackerProfile;
  defenderIdentity: OpponentPokemonIdentity;
  defenderTargets: DefenderTarget[];
  selectedDefenderProfile: DefenderProfile;
  legalMovePool?: LegalMovePool;
}

const LEGACY_STAT_MAP: Record<string, StatId> = {
  hp: 'hp',
  at: 'atk',
  atk: 'atk',
  df: 'def',
  def: 'def',
  sa: 'spa',
  spa: 'spa',
  sd: 'spd',
  spd: 'spd',
  sp: 'spe',
  spe: 'spe',
};

export class DamageCalculationInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DamageCalculationInputError';
  }
}

export class SmogonDamageEngine implements DamageEngineFacade {
  calculate(request: DamageRequest): DamageCalculationResult {
    const warnings: DamageWarning[] = [];
    collectRequestWarnings(request, warnings);
    const context = createCalculationContext(request, warnings);
    validateOpponentMoveSelection(request, context, warnings);
    const moves = selectMoves(request, context, warnings);
    const attackerBuild = withSelectedMoves(context.attackerBuild, moves);
    const attacker = createAttacker(attackerBuild, warnings);
    const attackerSummary = summarizePokemon(attacker, attackerBuild);

    const moveResults = moves.map(move =>
      calculateMoveAgainstTargets(request, context, attacker, attackerBuild, move, warnings)
    );

    return {
      requestId: request.requestId,
      calculationDirection: context.direction,
      attackerSide: context.attackerSide,
      attackerSummary,
      defenderSide: context.defenderSide,
      defenderIdentity: context.defenderIdentity,
      selectedDefenderProfile: context.selectedDefenderProfile,
      selectedAttackerProfile: context.selectedAttackerProfile,
      moveResults,
      warnings,
    };
  }
}

export function createDefaultDamageEngine(): DamageEngineFacade {
  return new SmogonDamageEngine();
}

function createCalculationContext(
  request: DamageRequest,
  warnings: DamageWarning[]
): CalculationContext {
  const direction = request.calculationDirection || request.direction || 'OWN_TO_OPPONENT';
  const attackerSide = request.attackerSide || (direction === 'OPPONENT_TO_OWN' ? 'OPPONENT' : 'OWN');
  const defenderSide = request.defenderSide || (direction === 'OPPONENT_TO_OWN' ? 'OWN' : 'OPPONENT');

  if (direction === 'OPPONENT_TO_OWN') {
    const attackerIdentity = resolveOpponentAttackerIdentity(request);
    const selectedAttackerProfile = selectAttackerProfile(request, warnings);
    const attackerBuild = createOpponentAttackerBuild(request, attackerIdentity, selectedAttackerProfile);
    const defenderTargets = createOwnDefenderTargets(request, warnings);
    const selectedDefenderProfile = defenderTargets[0]?.profile;

    if (!selectedDefenderProfile) {
      throw new DamageCalculationInputError('A defender build or profile is required.');
    }

    return {
      direction,
      attackerSide,
      defenderSide,
      attackerBuild,
      selectedAttackerProfile,
      defenderIdentity: defenderTargets[0].identity,
      defenderTargets,
      selectedDefenderProfile,
      legalMovePool: request.attackerLegalMovePool || request.opponentLegalMovePool,
    };
  }

  const attackerBuild = requireKnownBuild(request.attacker, 'attacker');
  const defenderTargets = createOpponentDefenderTargets(request, warnings);
  const selectedDefenderProfile =
    defenderTargets.find(target => target.profile.isSelected)?.profile ||
    defenderTargets[0]?.profile;

  if (!selectedDefenderProfile) {
    throw new DamageCalculationInputError('At least one defender profile is required.');
  }

  return {
    direction,
    attackerSide,
    defenderSide,
    attackerBuild,
    defenderIdentity: defenderTargets[0].identity,
    defenderTargets,
    selectedDefenderProfile,
    legalMovePool: request.attackerLegalMovePool,
  };
}

function requireKnownBuild(build: KnownPokemonBuild | undefined, path: string) {
  if (!build) {
    throw new DamageCalculationInputError(`Missing required Pokemon build: ${path}`);
  }

  return build;
}

function resolveOpponentAttackerIdentity(request: DamageRequest): OpponentPokemonIdentity {
  if (request.attackerIdentity) return request.attackerIdentity;
  if (request.attackerProfileSet) {
    return {species: request.attackerProfileSet.attackerSpecies};
  }
  if (request.defenderIdentity) return request.defenderIdentity;

  throw new DamageCalculationInputError(
    'Opponent-to-own calculations require an opponent attacker identity.'
  );
}

function selectAttackerProfile(
  request: DamageRequest,
  warnings: DamageWarning[]
): OpponentAttackerProfile {
  const profileSet = request.attackerProfileSet;

  if (!profileSet || profileSet.profiles.length === 0) {
    warnings.push({
      code: 'NO_ATTACKER_PROFILE',
      message: 'No opponent attacker profile was provided; using a blank current profile.',
      path: 'attackerProfileSet.profiles',
    });

    return {
      profileId: 'default-attacker',
      profileName: 'Default attacker',
      source: 'MANUAL_CURRENT',
      isSelected: true,
      editable: true,
    };
  }

  const selectedId =
    profileSet.selectedProfileId ||
    profileSet.profiles.find(profile => profile.isSelected)?.profileId ||
    profileSet.profiles[0]?.profileId;

  const selected =
    profileSet.profiles.find(profile => profile.profileId === selectedId) ||
    profileSet.profiles[0];

  if (!selected) {
    throw new DamageCalculationInputError('At least one opponent attacker profile is required.');
  }

  if (selected.profileId !== selectedId) {
    warnings.push({
      code: 'NO_SELECTED_ATTACKER_PROFILE',
      message: 'Selected opponent attacker profile was not found; using the first profile.',
      path: 'attackerProfileSet.selectedProfileId',
    });
  }

  return selected;
}

function createOpponentAttackerBuild(
  request: DamageRequest,
  identity: OpponentPokemonIdentity,
  profile: OpponentAttackerProfile
): KnownPokemonBuild {
  const base = request.attacker || {
    species: identity.form || identity.species,
    form: identity.form,
    moves: [],
  };

  return {
    ...base,
    species: base.species || identity.form || identity.species,
    form: base.form || identity.form,
    level: profile.level ?? base.level,
    statPoints: profile.statPoints ?? base.statPoints,
    actualStats: profile.actualStats ?? base.actualStats,
    statAlignment: profile.statAlignment ?? base.statAlignment,
    ability: profile.ability ?? base.ability,
    abilityOn: profile.abilityOn ?? base.abilityOn,
    item: profile.item ?? base.item,
    moves: markMoves(profile.moves, 'PROFILE_PRESET') || base.moves || [],
    typeOverride: profile.typeOverride ?? base.typeOverride,
    statStages: profile.statStages ?? base.statStages,
    status: profile.status ?? base.status,
    toxicCounter: profile.toxicCounter ?? base.toxicCounter,
    currentHp: profile.currentHp ?? base.currentHp,
    currentHpPercent: profile.currentHpPercent ?? base.currentHpPercent,
    teraType: profile.teraType ?? base.teraType,
    notes: [...(base.notes || []), ...(profile.notes || [])],
  };
}

function createOwnDefenderTargets(
  request: DamageRequest,
  warnings: DamageWarning[]
): DefenderTarget[] {
  if (request.defender) {
    const profile = profileFromKnownBuild(request.defender, 'own-defender', 'Own defender');
    return [
      {
        identity: identityFromKnownBuild(request.defender),
        profile,
        build: request.defender,
      },
    ];
  }

  return createOpponentDefenderTargets(request, warnings);
}

function createOpponentDefenderTargets(
  request: DamageRequest,
  warnings: DamageWarning[]
): DefenderTarget[] {
  const defenderIdentity = resolveDefenderIdentity(request);
  const profiles = normalizeProfiles(request, warnings);
  const selectedProfile = selectProfile(request, profiles, warnings);

  return profiles.map(profile => ({
    identity: defenderIdentity,
    profile: profile.profileId === selectedProfile.profileId
      ? {...profile, isSelected: true}
      : profile,
    build: createDefenderBuild(defenderIdentity, profile),
  }));
}

function resolveDefenderIdentity(request: DamageRequest): OpponentPokemonIdentity {
  if (request.defenderIdentity) return request.defenderIdentity;
  if (request.defender) return identityFromKnownBuild(request.defender);
  if (request.defenderProfileSet) return {species: request.defenderProfileSet.defenderSpecies};

  throw new DamageCalculationInputError('A defender identity or build is required.');
}

function identityFromKnownBuild(build: KnownPokemonBuild): OpponentPokemonIdentity {
  return {
    species: build.species,
    form: build.form,
  };
}

function profileFromKnownBuild(
  build: KnownPokemonBuild,
  profileId: string,
  profileName: string
): DefenderProfile {
  return {
    profileId,
    profileName,
    source: 'MANUAL_CURRENT',
    isSelected: true,
    includedInEnvelope: true,
    editable: true,
    level: build.level,
    statPoints: build.statPoints,
    actualStats: build.actualStats,
    statAlignment: build.statAlignment,
    ability: build.ability,
    abilityOn: build.abilityOn,
    item: build.item,
    moves: build.moves,
    typeOverride: build.typeOverride,
    statStages: build.statStages,
    status: build.status,
    toxicCounter: build.toxicCounter,
    currentHp: build.currentHp,
    currentHpPercent: build.currentHpPercent,
    teraType: build.teraType,
    notes: build.notes,
  };
}

function normalizeProfiles(request: DamageRequest, warnings: DamageWarning[]) {
  if (request.defenderProfileSet && request.defenderProfileSet.profiles.length > 0) {
    return request.defenderProfileSet.profiles;
  }

  warnings.push({
    code: 'NO_DEFENDER_PROFILES',
    message: 'No defender profile was provided; using a blank current profile.',
    path: 'defenderProfileSet.profiles',
  });

  return [
    {
      profileId: 'default',
      profileName: 'Default',
      source: 'MANUAL_CURRENT',
      isSelected: true,
      includedInEnvelope: true,
      editable: true,
    } satisfies DefenderProfile,
  ];
}

function selectProfile(
  request: DamageRequest,
  profiles: DefenderProfile[],
  warnings: DamageWarning[]
) {
  const selectedId =
    request.defenderProfileSet?.selectedProfileId ||
    profiles.find(profile => profile.isSelected)?.profileId ||
    profiles[0]?.profileId;

  const selected = profiles.find(profile => profile.profileId === selectedId) || profiles[0];
  if (!selected) {
    throw new DamageCalculationInputError('At least one defender profile is required.');
  }

  if (selected.profileId !== selectedId) {
    warnings.push({
      code: 'NO_SELECTED_PROFILE',
      message: 'Selected defender profile was not found; using the first profile.',
      path: 'defenderProfileSet.selectedProfileId',
    });
  }

  return selected;
}

function selectMoves(
  request: DamageRequest,
  context: CalculationContext,
  warnings: DamageWarning[]
) {
  const attackerMoves = context.attackerBuild.moves || [];
  const legalMoves = context.legalMovePool?.learnableMoves || [];
  const defaultSource: MoveSource =
    context.direction === 'OPPONENT_TO_OWN' ? 'PROFILE_PRESET' : 'OWN_BUILD';

  const moves = request.moveSelection.mode === 'ONE_MOVE'
    ? selectOneMove(request, attackerMoves, legalMoves, defaultSource)
    : selectAllMoves(request, context, attackerMoves, legalMoves, defaultSource);

  if (moves.length === 0) {
    warnings.push({
      code: 'NO_ATTACKER_MOVES',
      message: 'No attacker move matched the request.',
      path: 'attacker.moves',
    });
  }

  return moves;
}

function selectOneMove(
  request: DamageRequest,
  attackerMoves: MoveBuild[],
  legalMoves: EntityRef[],
  defaultSource: MoveSource
) {
  const selectedMoveId = request.moveSelection.moveId;
  const fromBuild = selectedMoveId
    ? attackerMoves.filter(move => moveMatches(move.move, selectedMoveId))
    : [];

  if (fromBuild.length > 0) {
    return fromBuild.map(move => ({
      ...move,
      source: move.source || defaultSource,
    }));
  }

  if (request.moveSelection.move) {
    return [{
      move: request.moveSelection.move,
      source: request.moveSelection.source || 'MANUAL_OVERRIDE',
    }];
  }

  if (selectedMoveId) {
    const legalMove = legalMoves.find(move => moveMatches(move, selectedMoveId));
    if (legalMove) {
      return [{
        move: legalMove,
        source: request.moveSelection.source || 'OPPONENT_LEGAL_MOVE_POOL',
      }];
    }
  }

  return [];
}

function selectAllMoves(
  request: DamageRequest,
  context: CalculationContext,
  attackerMoves: MoveBuild[],
  legalMoves: EntityRef[],
  defaultSource: MoveSource
) {
  if (attackerMoves.length > 0) {
    return attackerMoves.map(move => ({
      ...move,
      source: move.source || defaultSource,
    }));
  }

  if (context.direction === 'OPPONENT_TO_OWN' && legalMoves.length > 0) {
    return legalMoves.map(move => ({
      move,
      source: request.moveSelection.source || 'OPPONENT_LEGAL_MOVE_POOL',
    }));
  }

  return [];
}

function validateOpponentMoveSelection(
  request: DamageRequest,
  context: CalculationContext,
  warnings: DamageWarning[]
) {
  if (context.direction !== 'OPPONENT_TO_OWN') return;

  if (request.moveSelection.mode === 'ONE_MOVE' && !request.moveSelection.moveId && !request.moveSelection.move) {
    warnings.push({
      code: 'NO_OPPONENT_MOVE_SELECTED',
      message: 'Opponent damage intake calculations require a selected opponent move.',
      path: 'moveSelection.moveId',
    });
    return;
  }

  const pool = context.legalMovePool;
  if (!pool) {
    warnings.push({
      code: 'LEGAL_MOVE_POOL_MISSING',
      message: 'No legal move pool was provided for the opponent attacker.',
      path: 'attackerLegalMovePool',
    });
    return;
  }

  if (request.moveSelection.mode !== 'ONE_MOVE') return;

  const selectedMove = request.moveSelection.move;
  const selectedMoveId = request.moveSelection.moveId;
  const isManualOverride = request.moveSelection.source === 'MANUAL_OVERRIDE';
  const isLegal = selectedMove
    ? pool.learnableMoves.some(move => sameMove(move, selectedMove))
    : !!selectedMoveId && pool.learnableMoves.some(move => moveMatches(move, selectedMoveId));

  if (!isLegal && !isManualOverride) {
    warnings.push({
      code: 'ILLEGAL_OPPONENT_MOVE',
      message: 'The selected opponent move is not present in the provided legal move pool.',
      path: 'moveSelection.moveId',
    });
  }
}

function calculateMoveAgainstTargets(
  request: DamageRequest,
  context: CalculationContext,
  attacker: InstanceType<typeof SmogonPokemon>,
  attackerBuild: KnownPokemonBuild,
  moveBuild: MoveBuild,
  warnings: DamageWarning[]
): MoveDamageResult {
  const profileRanges = context.defenderTargets.map(target => {
    const defender = createPokemon(target.build.species, target.build, warnings);
    const move = createMove(moveBuild, attackerBuild, request.battle, warnings);
    const field = createField(request);
    const result = smogonCalculate(CHAMPIONS_GENERATION, attacker, defender, move, field);
    const damageRange = toDamageRange(result);

    return {
      defenderProfileId: target.profile.profileId,
      defenderProfileName: target.profile.profileName,
      profileSource: target.profile.source,
      includedInEnvelope: target.profile.includedInEnvelope !== false,
      damageRange,
      koSummary: toKoSummary(result),
      assumptions: collectAssumptions(target.profile, context),
      notes: target.profile.notes,
    } satisfies ProfileDamageRange;
  });

  const selectedRange =
    profileRanges.find(profile => profile.defenderProfileId === context.selectedDefenderProfile.profileId) ||
    profileRanges[0];

  if (!selectedRange) {
    throw new DamageCalculationInputError('A move calculation requires at least one profile range.');
  }

  const envelopeRange = createEnvelopeRange(profileRanges, warnings);

  return {
    moveId: moveBuild.move.canonicalId,
    moveName: moveBuild.move.displayName || moveBuild.move.showdownId,
    moveSource: resolveMoveSource(moveBuild, context),
    moveCategory: readMoveCategory(moveBuild),
    selectedProfileRange: selectedRange.damageRange,
    profileRanges,
    envelopeRange,
    koSummary: selectedRange.koSummary,
    assumptions: selectedRange.assumptions,
  };
}

function createAttacker(build: KnownPokemonBuild, warnings: DamageWarning[]) {
  return createPokemon(build.species, build, warnings);
}

function createDefenderBuild(
  identity: OpponentPokemonIdentity,
  profile: DefenderProfile
): KnownPokemonBuild {
  return {
    species: identity.form || identity.species,
    form: identity.form,
    level: profile.level,
    statPoints: profile.statPoints,
    actualStats: profile.actualStats,
    statAlignment: profile.statAlignment,
    ability: profile.ability,
    abilityOn: profile.abilityOn,
    item: profile.item,
    moves: profile.moves || [],
    typeOverride: profile.typeOverride,
    statStages: profile.statStages,
    status: profile.status,
    toxicCounter: profile.toxicCounter,
    currentHp: profile.currentHp,
    currentHpPercent: profile.currentHpPercent,
    teraType: profile.teraType,
    notes: profile.notes,
  };
}

function markMoves(moves: MoveBuild[] | undefined, source: MoveSource) {
  if (!moves) return undefined;
  return moves.map(move => ({
    ...move,
    source: move.source || source,
  }));
}

function withSelectedMoves(build: KnownPokemonBuild, selectedMoves: MoveBuild[]) {
  const missingMoves = selectedMoves.filter(selected =>
    !build.moves.some(existing => sameMove(existing.move, selected.move))
  );

  if (missingMoves.length === 0) return build;

  return {
    ...build,
    moves: [...build.moves, ...missingMoves],
  };
}

function resolveMoveSource(moveBuild: MoveBuild, context: CalculationContext): MoveSource {
  if (moveBuild.source) return moveBuild.source;
  return context.direction === 'OPPONENT_TO_OWN' ? 'PROFILE_PRESET' : 'OWN_BUILD';
}

function sameMove(left: EntityRef, right: EntityRef) {
  return moveMatches(left, right.canonicalId) || moveMatches(left, right.showdownId);
}

function moveMatches(move: EntityRef, moveId: string) {
  return (
    move.canonicalId === moveId ||
    move.showdownId === moveId ||
    normalizeMoveId(move.canonicalId) === normalizeMoveId(moveId) ||
    normalizeMoveId(move.showdownId) === normalizeMoveId(moveId) ||
    (move.displayName ? normalizeMoveId(move.displayName) === normalizeMoveId(moveId) : false)
  );
}

function normalizeMoveId(value: string) {
  return toID(value.replace(/^move\./i, ''));
}

function createPokemon(
  species: EntityRef,
  build: KnownPokemonBuild,
  warnings: DamageWarning[]
) {
  const generation = Generations.get(CHAMPIONS_GENERATION);
  const speciesName = species.showdownId;
  const specie = generation.species.get(toID(speciesName));

  if (!specie) {
    warnings.push({
      code: 'SPECIES_NOT_FOUND',
      message: `Species '${speciesName}' was not found in the damage calculator data.`,
      path: 'species.showdownId',
    });
    throw new DamageCalculationInputError(`Unknown species: ${speciesName}`);
  }

  const statPoints = normalizeStats(build.statPoints);
  const boosts = normalizeStats(build.statStages);
  const ivs = normalizeStats(build.legacyIvs);
  const actualStats = normalizeStats(build.actualStats);
  const typeOverride = normalizeTypeOverride(build.typeOverride);

  const overrides = {
    baseStats: Object.keys(actualStats).length > 0
      ? deriveBaseStats(specie.baseStats as StatTable, statPoints, actualStats, build, warnings)
      : specie.baseStats,
    types: typeOverride || specie.types,
  };

  const baseOptions = {
    level: build.level,
    ability: build.ability?.showdownId,
    abilityOn: build.abilityOn,
    item: build.item?.showdownId,
    gender: build.gender,
    nature: build.statAlignment?.showdownId,
    ivs,
    evs: statPoints,
    boosts,
    status: build.status,
    toxicCounter: build.toxicCounter,
    teraType: build.teraType,
    alliesFainted: build.alliesFainted,
    boostedStat: build.boostedStat,
    moves: build.moves.map(move => move.move.showdownId),
    overrides,
  };

  const currentHp = resolveCurrentHp(speciesName, baseOptions, build);
  return new SmogonPokemon(CHAMPIONS_GENERATION, speciesName, {
    ...baseOptions,
    curHP: currentHp,
  });
}

function resolveCurrentHp(
  speciesName: string,
  baseOptions: Record<string, unknown>,
  build: KnownPokemonBuild
) {
  if (typeof build.currentHp === 'number') return build.currentHp;
  if (typeof build.currentHpPercent !== 'number') return undefined;

  const probe = new SmogonPokemon(CHAMPIONS_GENERATION, speciesName, baseOptions);
  const percent = Math.max(0, Math.min(100, build.currentHpPercent));
  return Math.round((probe.maxHP() * percent) / 100);
}

function createMove(
  moveBuild: MoveBuild,
  attacker: KnownPokemonBuild,
  battle: DamageRequest['battle'],
  warnings: DamageWarning[]
) {
  const moveName = moveBuild.move.showdownId;
  const generation = Generations.get(CHAMPIONS_GENERATION);
  const knownMove = generation.moves.get(toID(moveName));

  if (!knownMove) {
    warnings.push({
      code: 'MOVE_NOT_FOUND',
      message: `Move '${moveName}' was not found in the damage calculator data.`,
      path: 'attacker.moves',
    });
  }

  return new SmogonMove(CHAMPIONS_GENERATION, moveName, {
    ability: attacker.ability?.showdownId,
    item: attacker.item?.showdownId,
    species: attacker.form?.showdownId || attacker.species.showdownId,
    isCrit: moveBuild.isCritical ?? battle.isCritical,
    isStellarFirstUse: moveBuild.isStellarFirstUse,
    hits: moveBuild.hits,
    timesUsed: moveBuild.timesUsed,
    timesUsedWithMetronome: moveBuild.timesUsedWithMetronome,
    overrides: {
      basePower: moveBuild.basePowerOverride,
      type: moveBuild.typeOverride,
      category: moveBuild.categoryOverride,
      target: (moveBuild.isSpreadMoveOverride ?? battle.isSpreadMove)
        ? 'allAdjacentFoes'
        : undefined,
    },
  });
}

function collectRequestWarnings(request: DamageRequest, warnings: DamageWarning[]) {
  if (request.battle.customFlags && Object.keys(request.battle.customFlags).length > 0) {
    warnings.push({
      code: 'CUSTOM_FLAGS_NOT_APPLIED',
      message: 'Custom battle flags are preserved on the request but not applied by this adapter.',
      path: 'battle.customFlags',
    });
  }
}

function createField(request: DamageRequest) {
  const battle = request.battle;
  return new SmogonField({
    gameType: battle.battleType === 'DOUBLE' ? 'Doubles' : 'Singles',
    weather: battle.weather === 'NONE' ? undefined : battle.weather,
    terrain: battle.terrain === 'NONE' ? undefined : battle.terrain,
    isMagicRoom: battle.isMagicRoom,
    isWonderRoom: battle.isWonderRoom,
    isGravity: battle.isGravity,
    isBeadsOfRuin: battle.ruinAbilities?.beadsOfRuin,
    isSwordOfRuin: battle.ruinAbilities?.swordOfRuin,
    isTabletsOfRuin: battle.ruinAbilities?.tabletsOfRuin,
    isVesselOfRuin: battle.ruinAbilities?.vesselOfRuin,
    attackerSide: mapSideConditions(battle.attackerSideConditions),
    defenderSide: mapSideConditions(battle.defenderSideConditions),
  });
}

function mapSideConditions(side: SideConditions = {}) {
  return {
    spikes: side.spikes,
    steelsurge: side.steelsurge,
    vinelash: side.vinelash,
    wildfire: side.wildfire,
    cannonade: side.cannonade,
    volcalith: side.volcalith,
    isSR: side.stealthRock,
    isReflect: side.reflect,
    isLightScreen: side.lightScreen,
    isProtected: side.protected,
    isSeeded: side.seeded,
    isSaltCured: side.saltCured,
    isForesight: side.foresight,
    isTailwind: side.tailwind,
    isHelpingHand: side.helpingHand,
    isFlowerGift: side.flowerGift,
    isPowerTrick: side.powerTrick,
    isFriendGuard: side.friendGuard,
    isAuroraVeil: side.auroraVeil,
    isBattery: side.battery,
    isPowerSpot: side.powerSpot,
    isSteelySpirit: side.steelySpirit,
    isSwitching: side.switching,
  };
}

function toDamageRange(result: SmogonResult): DamageRange {
  const [minDamage, maxDamage] = result.range();
  const maxHp = result.defender.maxHP();

  return {
    minDamage,
    maxDamage,
    minPercent: toPercent(minDamage, maxHp),
    maxPercent: toPercent(maxDamage, maxHp),
    rolls: cloneDamageRolls(result.damage),
    // The vendored textual formatter intentionally rejects zero-damage rolls.
    // Immunities and status moves are still valid calculator results.
    description: maxDamage <= 0 ? 'No direct damage.' : result.fullDesc('%', true),
  };
}

function toKoSummary(result: SmogonResult): KoSummary {
  const [, maxDamage] = result.range();
  // Immunities and other zero-damage outcomes are valid calculator results.
  // Some multi-value result shapes can produce a non-finite range even though
  // every concrete roll is zero, so check the rolls before asking the vendored
  // calculator for a KO chance (it intentionally asserts on zero damage).
  const concreteRolls = flattenDamage(cloneDamageRolls(result.damage));
  const concreteMax = concreteRolls.length > 0 ? Math.max(...concreteRolls) : 0;
  if (!Number.isFinite(maxDamage) || maxDamage <= 0 || concreteMax <= 0) {
    return {
      chance: 0,
      hits: 0,
      text: 'No direct damage.',
    };
  }

  const ko = result.kochance(true);
  return {
    chance: ko.chance,
    hits: ko.n,
    text: ko.text,
  };
}

function createEnvelopeRange(
  profileRanges: ProfileDamageRange[],
  warnings: DamageWarning[]
): DamageRange {
  let included = profileRanges.filter(profile => profile.includedInEnvelope);

  if (included.length === 0) {
    warnings.push({
      code: 'EMPTY_ENVELOPE',
      message: 'No defender profile was included in the envelope; using all profiles.',
      path: 'defenderProfileSet.profiles.includedInEnvelope',
    });
    included = profileRanges;
  }

  const minDamage = Math.min(...included.map(profile => profile.damageRange.minDamage));
  const maxDamage = Math.max(...included.map(profile => profile.damageRange.maxDamage));
  const minPercent = Math.min(...included.map(profile => profile.damageRange.minPercent));
  const maxPercent = Math.max(...included.map(profile => profile.damageRange.maxPercent));

  return {
    minDamage,
    maxDamage,
    minPercent,
    maxPercent,
    rolls: included.flatMap(profile => flattenDamage(profile.damageRange.rolls)),
    description: `${formatPercent(minPercent)} - ${formatPercent(maxPercent)}`,
  };
}

function summarizePokemon(
  pokemon: InstanceType<typeof SmogonPokemon>,
  build: KnownPokemonBuild
): PokemonSummary {
  return {
    speciesId: build.species.canonicalId,
    speciesName: build.species.displayName || build.species.showdownId,
    level: pokemon.level,
    ability: build.ability?.displayName || build.ability?.showdownId,
    item: build.item?.displayName || build.item?.showdownId,
    nature: build.statAlignment?.displayName || build.statAlignment?.showdownId,
    statPoints: normalizeStats(build.statPoints),
    actualStats: normalizeStats(build.actualStats),
    status: build.status,
    currentHp: pokemon.curHP(),
    maxHp: pokemon.maxHP(),
    notes: build.notes,
  };
}

function collectAssumptions(profile: DefenderProfile, context: CalculationContext) {
  const assumptions = [`Defender profile: ${profile.profileName}`];
  if (context.selectedAttackerProfile) {
    assumptions.push(`Attacker profile: ${context.selectedAttackerProfile.profileName}`);
  }
  if (!profile.ability) assumptions.push('Defender ability is unspecified.');
  if (!profile.item) assumptions.push('Defender item is unspecified.');
  if (!profile.statPoints && !profile.actualStats) {
    assumptions.push('Defender Stat Points use calculator defaults.');
  }
  if (profile.notes) assumptions.push(...profile.notes);
  return assumptions;
}

function readMoveCategory(moveBuild: MoveBuild) {
  if (moveBuild.categoryOverride) return moveBuild.categoryOverride;
  const move = Generations.get(CHAMPIONS_GENERATION).moves.get(toID(moveBuild.move.showdownId));
  return move?.category || 'Status';
}

function normalizeStats(stats?: StatTableInput): Partial<StatTable> {
  const normalized: Partial<StatTable> = {};
  if (!stats) return normalized;

  for (const [key, value] of Object.entries(stats)) {
    const stat = LEGACY_STAT_MAP[key];
    if (stat && typeof value === 'number') normalized[stat] = value;
  }

  return normalized;
}

function normalizeTypeOverride(types?: PokemonTypeName[]) {
  if (!types || types.length === 0) return undefined;
  if (types.length === 1) return [types[0]] as [PokemonTypeName];
  return [types[0], types[1]] as [PokemonTypeName, PokemonTypeName];
}

function deriveBaseStats(
  defaultBaseStats: StatTable,
  statPoints: Partial<StatTable>,
  actualStats: Partial<StatTable>,
  build: KnownPokemonBuild,
  warnings: DamageWarning[]
): StatTable {
  const baseStats = {...defaultBaseStats};
  const nature = getNatureMods(build.statAlignment?.showdownId);

  for (const stat of STATS) {
    const actual = actualStats[stat];
    if (typeof actual !== 'number') continue;

    const sp = statPoints[stat] || 0;
    baseStats[stat] = deriveBaseStat(stat, actual, sp, nature.plus, nature.minus);
  }

  warnings.push({
    code: 'ACTUAL_STATS_APPROXIMATED',
    message: 'Actual stats were converted into calculator base-stat overrides for this run.',
    path: 'actualStats',
  });

  return baseStats;
}

function getNatureMods(natureName?: string) {
  const nature = natureName
    ? Generations.get(CHAMPIONS_GENERATION).natures.get(toID(natureName))
    : undefined;

  return {
    plus: nature?.plus as StatId | undefined,
    minus: nature?.minus as StatId | undefined,
  };
}

function deriveBaseStat(
  stat: StatId,
  actual: number,
  statPoint: number,
  plus?: StatId,
  minus?: StatId
) {
  if (stat === 'hp') {
    if (actual === 1) return 1;
    return clampStat(actual - statPoint - 75);
  }

  const natureMod = plus === stat && minus === stat
    ? 1
    : plus === stat
      ? 1.1
      : minus === stat
        ? 0.9
        : 1;

  for (let base = 1; base <= 255; base++) {
    if (Math.floor(natureMod * (base + statPoint + 20)) === actual) return base;
  }

  return clampStat(Math.round(actual / natureMod - statPoint - 20));
}

function clampStat(value: number) {
  return Math.max(1, Math.min(255, value));
}

function cloneDamageRolls(damage: SmogonResult['damage']): number[] | number[][] {
  if (typeof damage === 'number') return [damage];
  return JSON.parse(JSON.stringify(damage)) as number[] | number[][];
}

function flattenDamage(rolls: number[] | number[][]) {
  return rolls.flatMap(roll => Array.isArray(roll) ? roll : [roll]);
}

function toPercent(damage: number, maxHp: number) {
  if (maxHp <= 0) return 0;
  return Number(((damage * 100) / maxHp).toFixed(1));
}

function formatPercent(value: number) {
  return `${value.toFixed(1)}%`;
}
