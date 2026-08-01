import { MoveValue } from './Models';
import { normalizeShowdownId } from './EntityCatalog';

export function actualConfiguredMoves(configuredMoves: MoveValue[]): MoveValue[] {
  const seen = new Set<string>();
  return configuredMoves.filter((move: MoveValue) => {
    const id = normalizeShowdownId(move.entity.showdownId);
    if (seen.has(id)) {
      return false;
    }
    seen.add(id);
    return true;
  });
}

export function prioritizeLegalMoves(preferredMoves: MoveValue[], legalMoves: MoveValue[]): MoveValue[] {
  const legalIds = new Set<string>(legalMoves.map((move: MoveValue) => normalizeShowdownId(move.entity.showdownId)));
  return actualConfiguredMoves([
    ...preferredMoves.filter((move: MoveValue) => legalIds.has(normalizeShowdownId(move.entity.showdownId))),
    ...legalMoves
  ]);
}

export function configuredMoveOptions(configuredMoves: MoveValue[], legalMoves: MoveValue[]): MoveValue[] {
  return actualConfiguredMoves([...configuredMoves, ...legalMoves]);
}

export function chooseCompatibleMoveId(
  moves: MoveValue[],
  selectedMoveId: string | undefined,
  preferDamagingDefault: boolean
): string | undefined {
  const normalizedSelection = normalizeShowdownId(selectedMoveId ?? '');
  const selected = moves.find((move: MoveValue) =>
    normalizeShowdownId(move.entity.showdownId) === normalizedSelection);
  if (selected) {
    return selected.entity.showdownId;
  }
  if (preferDamagingDefault) {
    const damaging = moves.find((move: MoveValue) => (move.basePower ?? 0) > 0);
    if (damaging) {
      return damaging.entity.showdownId;
    }
  }
  return moves[0]?.entity.showdownId;
}
