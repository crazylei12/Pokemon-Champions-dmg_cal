import { EntityRef, MoveValue, OpponentProfile, SpeciesFormOption, SpeedRange, StatValues } from './Models';
import { normalizeShowdownId } from './EntityCatalog';

const MEGA_FORM_SUFFIX = /-mega(?:-[a-z0-9]+)*$/i;

export function isMegaSpeciesForm(showdownId: string): boolean {
  return MEGA_FORM_SUFFIX.test(showdownId);
}

function baseKey(form: SpeciesFormOption, familyForms: SpeciesFormOption[]): string {
  if (!isMegaSpeciesForm(form.species.showdownId)) {
    return normalizeShowdownId(form.species.showdownId);
  }
  const stem = form.species.showdownId.replace(MEGA_FORM_SUFFIX, '');
  const nonMega = familyForms.filter((candidate: SpeciesFormOption) => !isMegaSpeciesForm(candidate.species.showdownId));
  const base = nonMega.find((candidate: SpeciesFormOption) =>
    normalizeShowdownId(candidate.species.showdownId) === normalizeShowdownId(stem)) ??
    nonMega.find((candidate: SpeciesFormOption) =>
      normalizeShowdownId(candidate.species.showdownId) === normalizeShowdownId(form.familyId));
  return normalizeShowdownId(base?.species.showdownId ?? form.species.showdownId);
}

export function userOpponentPresetSharingForms(
  selected: SpeciesFormOption,
  family: SpeciesFormOption[]
): SpeciesFormOption[] {
  const familyForms = family.filter((form: SpeciesFormOption) => form.familyId === selected.familyId);
  if (selected.configurationShareGroupId) {
    return familyForms.filter((form: SpeciesFormOption) =>
      form.configurationShareGroupId === selected.configurationShareGroupId);
  }
  const selectedBaseKey = baseKey(selected, familyForms);
  return familyForms.filter((form: SpeciesFormOption) => baseKey(form, familyForms) === selectedBaseKey);
}

export function defaultAbilityForTarget(
  savedAbility: EntityRef | undefined,
  targetAbilities: EntityRef[],
  targetDefaultAbility: EntityRef | undefined
): EntityRef | undefined {
  if (savedAbility) {
    const matched = targetAbilities.find((ability: EntityRef) =>
      normalizeShowdownId(ability.showdownId) === normalizeShowdownId(savedAbility.showdownId));
    if (matched) {
      return matched;
    }
  }
  return targetDefaultAbility ?? targetAbilities[0];
}

export function orderOpponentProfiles(
  userPresets: OpponentProfile[],
  generatedPresets: OpponentProfile[],
  builtInPresets: OpponentProfile[]
): OpponentProfile[] {
  return [...userPresets, ...generatedPresets, ...builtInPresets];
}

export function championsStat(
  stat: string,
  base: number,
  points: number,
  plus: string | undefined,
  minus: string | undefined
): number {
  if (stat === 'hp') {
    return base === 1 ? 1 : base + points + 75;
  }
  const multiplier = stat === plus ? 1.1 : stat === minus ? 0.9 : 1;
  return Math.floor(multiplier * (base + points + 20));
}

export function transformActualStats(source: StatValues, sourceBase: StatValues, targetBase: StatValues): StatValues {
  const stats: Array<keyof StatValues> = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];
  const nonHp: Array<keyof StatValues> = ['atk', 'def', 'spa', 'spd', 'spe'];
  if (stats.some((stat: keyof StatValues) =>
    source[stat] === undefined || sourceBase[stat] === undefined || targetBase[stat] === undefined)) {
    return source;
  }

  const natureCandidates: Array<{ plus?: string; minus?: string }> = [{ plus: undefined, minus: undefined }];
  for (const plus of nonHp) {
    for (const minus of nonHp) {
      if (plus !== minus) {
        natureCandidates.push({ plus, minus });
      }
    }
  }

  let best = natureCandidates[0];
  let bestError = Number.MAX_SAFE_INTEGER;
  for (const candidate of natureCandidates) {
    let error = 0;
    for (const stat of stats) {
      let statError = Number.MAX_SAFE_INTEGER;
      for (let points = 0; points <= 32; points += 1) {
        statError = Math.min(statError, Math.abs(championsStat(stat, sourceBase[stat] as number, points,
          candidate.plus, candidate.minus) - (source[stat] as number)));
      }
      error += statError;
    }
    if (error < bestError) {
      best = candidate;
      bestError = error;
    }
  }

  const result: StatValues = {};
  for (const stat of stats) {
    let bestPoints = 0;
    let bestPointError = Number.MAX_SAFE_INTEGER;
    for (let points = 0; points <= 32; points += 1) {
      const error = Math.abs(championsStat(stat, sourceBase[stat] as number, points, best.plus, best.minus) -
        (source[stat] as number));
      if (error < bestPointError) {
        bestPoints = points;
        bestPointError = error;
      }
    }
    result[stat] = championsStat(stat, targetBase[stat] as number, bestPoints, best.plus, best.minus);
  }
  return result;
}

export function possibleSpeedRange(baseSpeed: number): SpeedRange {
  return {
    minimum: Math.floor(((baseSpeed + 20) * 9) / 10),
    maximum: Math.floor(((baseSpeed + 52) * 11) / 10)
  };
}

export function isSpeedLinePriorityMove(move: MoveValue): boolean {
  const id = normalizeShowdownId(move.entity.showdownId);
  const protective = new Set<string>(['protect', 'detect', 'kingsshield', 'spikyshield', 'banefulbunker',
    'silktrap', 'burningbulwark', 'obstruct', 'endure', 'wideguard', 'quickguard']);
  return (move.priority ?? 0) > 0 && !protective.has(id);
}
