import { DamageProjection, EngineInfo } from './Models';

interface DamageRangePayload {
  minDamage: number;
  maxDamage: number;
  minPercent: number;
  maxPercent: number;
}

interface WarningPayload {
  code: string;
}

interface MovePayload {
  moveId: string;
  moveSource: 'OWN_BUILD' | 'OPPONENT_LEGAL_MOVE_POOL' | 'PROFILE_PRESET' | 'MANUAL_OVERRIDE';
  moveCategory: string;
  selectedProfileRange: DamageRangePayload;
  koSummary: { hits: number };
}

interface DamageResultPayload {
  requestId: string;
  calculationDirection: 'OWN_TO_OPPONENT' | 'OPPONENT_TO_OWN';
  attackerSide: 'OWN' | 'OPPONENT';
  attackerSummary: { speciesId: string };
  defenderSide: 'OWN' | 'OPPONENT';
  defenderIdentity: { species: { canonicalId: string } };
  selectedDefenderProfile: { profileId: string };
  warnings: WarningPayload[];
  moveResults: MovePayload[];
}

interface EngineResponsePayload {
  ok: boolean;
  result?: DamageResultPayload;
  error?: { name: string; message: string };
}

export const ENGINE_VERSION = 'pokemon-champions-smogon-0.11.0-3677e41';

export function parseEngineInfo(json: string): EngineInfo {
  const info = JSON.parse(json) as EngineInfo;
  if (info.version !== ENGINE_VERSION || info.generation !== 'Champions' || !info.offline) {
    throw new Error('Damage engine metadata is incompatible.');
  }
  return info;
}

export function projectDamageResponse(json: string): DamageProjection {
  const response = JSON.parse(json) as EngineResponsePayload;
  if (!response.ok || !response.result) {
    throw new Error(response.error ? 'Damage engine returned an error.' : 'Damage engine returned no result.');
  }
  const result = response.result;
  const move = result.moveResults[0];
  if (!move) {
    throw new Error('Damage engine returned no move result.');
  }
  const range = move.selectedProfileRange;
  return {
    requestId: result.requestId,
    calculationDirection: result.calculationDirection,
    attackerSide: result.attackerSide,
    attackerSpeciesId: result.attackerSummary.speciesId,
    defenderSide: result.defenderSide,
    defenderSpeciesId: result.defenderIdentity.species.canonicalId,
    selectedProfileId: result.selectedDefenderProfile.profileId,
    warningCodes: result.warnings.map((warning: WarningPayload) => warning.code),
    moveId: move.moveId,
    moveSource: move.moveSource,
    moveCategory: move.moveCategory,
    minDamage: range.minDamage,
    maxDamage: range.maxDamage,
    minPercent: range.minPercent,
    maxPercent: range.maxPercent,
    koHits: move.koSummary.hits
  };
}

export function isGoldenProjection(projection: DamageProjection): boolean {
  return projection.requestId === 'harmony-port-phase0-own-output' &&
    projection.calculationDirection === 'OWN_TO_OPPONENT' &&
    projection.attackerSide === 'OWN' &&
    projection.attackerSpeciesId === 'species.mawile' &&
    projection.defenderSide === 'OPPONENT' &&
    projection.defenderSpeciesId === 'species.armarouge' &&
    projection.selectedProfileId === 'phase0-exact-defender' &&
    projection.warningCodes.join(',') === 'ACTUAL_STATS_APPROXIMATED,ACTUAL_STATS_APPROXIMATED' &&
    projection.moveId === 'move.playrough' &&
    projection.moveSource === 'OWN_BUILD' &&
    projection.moveCategory === 'Physical' &&
    projection.minDamage === 32 && projection.maxDamage === 38 &&
    projection.minPercent === 19.9 && projection.maxPercent === 23.6 &&
    projection.koHits === 5;
}
