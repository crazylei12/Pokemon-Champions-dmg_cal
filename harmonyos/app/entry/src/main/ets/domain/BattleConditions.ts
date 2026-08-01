import { BattleCondition, BattleType } from './Models';

const WEATHER_VALUES = new Set<string>(['NONE', 'Sun', 'Rain', 'Sand', 'Snow']);
const TERRAIN_VALUES = new Set<string>(['NONE', 'Electric', 'Grassy', 'Psychic', 'Misty']);

export function normalizeBattleType(value: string | undefined): BattleType {
  return value === 'DOUBLE' ? 'DOUBLE' : 'SINGLE';
}

export function normalizeWeather(value: string | undefined): string {
  return value && WEATHER_VALUES.has(value) ? value : 'NONE';
}

export function normalizeTerrain(value: string | undefined): string {
  return value && TERRAIN_VALUES.has(value) ? value : 'NONE';
}

export function withBattleTypeDefaults(condition: BattleCondition, value: string): BattleCondition {
  const battleType = normalizeBattleType(value);
  return {
    ...condition,
    battleType,
    isSpreadMove: battleType === 'DOUBLE',
    attackerSideConditions: {
      ...condition.attackerSideConditions,
      helpingHand: battleType === 'DOUBLE' && condition.attackerSideConditions?.helpingHand === true
    }
  };
}

export function defaultBattleCondition(): BattleCondition {
  return {
    battleType: 'SINGLE',
    weather: 'NONE',
    terrain: 'NONE',
    attackerSideConditions: {},
    defenderSideConditions: {},
    isCritical: false,
    isSpreadMove: false
  };
}
