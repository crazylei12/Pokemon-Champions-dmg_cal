import { EntityRef } from './Models';
import { StoredBattleSession, StoredEntity, StoredTeam } from '../storage/StorageContracts';

export interface TeamPreviewCandidate {
  entityType: 'SPECIES';
  canonicalId: string;
  showdownId: string;
  displayName: string;
  confidence: number;
  score: number;
  scoreMargin: number;
  source: string;
  visualVariant: string;
  isShiny: boolean;
}

export interface TeamPreviewSlotResult {
  side: 'own' | 'opponent';
  slotIndex: number;
  roiId: string;
  confirmed: boolean;
  requiresConfirmation: boolean;
  selectedCandidate?: TeamPreviewCandidate;
  candidates: TeamPreviewCandidate[];
}

export interface TeamPreviewRecognitionResult {
  schemaVersion: number;
  kind: 'TeamPreviewRecognitionResult';
  sceneType: 'TEAM_PREVIEW';
  capturedAt: string;
  imageSize: { width: number; height: number };
  backend: string;
  templateAsset: string;
  roiMapping: {
    asset: string;
    mode: 'largest_centered_aspect';
    gameViewport: { left: number; top: number; width: number; height: number };
  };
  elapsedMs: number;
  performance: Object;
  confirmed: boolean;
  ownTeamCandidates: TeamPreviewSlotResult[];
  opponentTeamCandidates: TeamPreviewSlotResult[];
  warnings: string[];
}

export interface TeamPreviewReviewSlot {
  side: 'own' | 'opponent';
  slotIndex: number;
  roiId: string;
  candidates: TeamPreviewCandidate[];
  selected?: EntityRef;
  confidence: number;
  scoreMargin: number;
  recognitionRisk: boolean;
  confirmed: boolean;
}

export interface TeamPreviewReviewDraft {
  capturedAt: string;
  own: TeamPreviewReviewSlot[];
  opponent: TeamPreviewReviewSlot[];
}

function candidateEntity(candidate: TeamPreviewCandidate): EntityRef {
  return { entityType: 'species', canonicalId: candidate.canonicalId, showdownId: candidate.showdownId,
    displayName: candidate.displayName, confidence: candidate.confidence, source: 'system' };
}

function validateSlots(slots: TeamPreviewSlotResult[], side: 'own' | 'opponent'): TeamPreviewSlotResult[] {
  if (!Array.isArray(slots) || slots.length !== 6) throw new Error(`${side} team preview must contain six slots`);
  const sorted = slots.slice().sort((left: TeamPreviewSlotResult, right: TeamPreviewSlotResult) =>
    left.slotIndex - right.slotIndex);
  sorted.forEach((slot: TeamPreviewSlotResult, index: number) => {
    if (slot.side !== side || slot.slotIndex !== index || !Array.isArray(slot.candidates)) {
      throw new Error(`${side} team preview slot order is invalid`);
    }
    const ids = new Set<string>();
    for (const candidate of slot.candidates) {
      if (!candidate || candidate.entityType !== 'SPECIES' || !candidate.canonicalId || !candidate.showdownId ||
        !Number.isFinite(candidate.confidence) || candidate.confidence < 0 || candidate.confidence > 1 ||
        !Number.isFinite(candidate.score) || !Number.isFinite(candidate.scoreMargin) ||
        candidate.scoreMargin < 0) throw new Error(`${side} team preview candidate is invalid`);
      const id = candidate.canonicalId.toLocaleLowerCase();
      if (ids.has(id)) throw new Error(`${side} team preview contains duplicate candidates`);
      ids.add(id);
    }
    if (slot.selectedCandidate && !ids.has(slot.selectedCandidate.canonicalId.toLocaleLowerCase())) {
      throw new Error(`${side} team preview selected candidate is not in its candidate list`);
    }
  });
  return sorted;
}

export function parseTeamPreviewRecognition(json: string): TeamPreviewRecognitionResult {
  const result = JSON.parse(json) as TeamPreviewRecognitionResult;
  if (result.schemaVersion !== 1 || result.kind !== 'TeamPreviewRecognitionResult' ||
    result.sceneType !== 'TEAM_PREVIEW' || result.roiMapping?.mode !== 'largest_centered_aspect') {
    throw new Error('Unsupported team-preview recognition result');
  }
  result.ownTeamCandidates = validateSlots(result.ownTeamCandidates, 'own');
  result.opponentTeamCandidates = validateSlots(result.opponentTeamCandidates, 'opponent');
  return result;
}

function reviewSlot(slot: TeamPreviewSlotResult): TeamPreviewReviewSlot {
  const selected = slot.selectedCandidate ?? slot.candidates[0];
  return { side: slot.side, slotIndex: slot.slotIndex, roiId: slot.roiId, candidates: slot.candidates,
    selected: selected ? candidateEntity(selected) : undefined, confidence: selected?.confidence ?? 0,
    scoreMargin: selected?.scoreMargin ?? 0,
    recognitionRisk: slot.requiresConfirmation || !selected || selected.confidence < 0.90 ||
      selected.scoreMargin < 0.035, confirmed: false };
}

export function buildTeamPreviewReview(result: TeamPreviewRecognitionResult): TeamPreviewReviewDraft {
  return { capturedAt: result.capturedAt, own: result.ownTeamCandidates.map(reviewSlot),
    opponent: result.opponentTeamCandidates.map(reviewSlot) };
}

export function replaceTeamPreviewSlot(draft: TeamPreviewReviewDraft, side: 'own' | 'opponent',
  slotIndex: number, entity: EntityRef, confirmed: boolean = true): TeamPreviewReviewDraft {
  const update = (slot: TeamPreviewReviewSlot): TeamPreviewReviewSlot => slot.slotIndex !== slotIndex ? slot : {
    side: slot.side, slotIndex: slot.slotIndex, roiId: slot.roiId, candidates: slot.candidates, selected: entity,
    confidence: entity.confidence ?? slot.confidence, scoreMargin: slot.scoreMargin,
    recognitionRisk: slot.recognitionRisk, confirmed };
  return { capturedAt: draft.capturedAt, own: side === 'own' ? draft.own.map(update) : draft.own,
    opponent: side === 'opponent' ? draft.opponent.map(update) : draft.opponent };
}

export function allTeamPreviewSlotsConfirmed(draft: TeamPreviewReviewDraft): boolean {
  return draft.own.length === 6 && draft.opponent.length === 6 &&
    [...draft.own, ...draft.opponent].every((slot: TeamPreviewReviewSlot) => slot.confirmed && !!slot.selected);
}

export function teamPreviewSlotReady(slot: TeamPreviewReviewSlot): boolean {
  return !!slot.selected && (!slot.recognitionRisk || slot.confirmed);
}

export function teamPreviewReadyForSession(draft: TeamPreviewReviewDraft): boolean {
  return draft.own.length === 6 && draft.opponent.length === 6 &&
    [...draft.own, ...draft.opponent].every((slot: TeamPreviewReviewSlot) => teamPreviewSlotReady(slot));
}

function teamMembers(team: StoredTeam): StoredEntity[] {
  return (team.pokemon ?? team.members ?? []).map((member) => member.species);
}

function normalizedSpecies(values: StoredEntity[]): string[] {
  return values.map((value: StoredEntity) => value.canonicalId || value.showdownId.toLocaleLowerCase()).sort();
}

export function matchingSavedOwnTeams(draft: TeamPreviewReviewDraft, teams: StoredTeam[]): StoredTeam[] {
  if (draft.own.length !== 6 || draft.own.some((slot: TeamPreviewReviewSlot) => !teamPreviewSlotReady(slot))) return [];
  const selected = draft.own.map((slot: TeamPreviewReviewSlot) => slot.selected)
    .filter((entity: EntityRef | undefined): entity is EntityRef => !!entity)
    .map((entity: EntityRef): StoredEntity => ({ entityType: 'species', canonicalId: entity.canonicalId,
      showdownId: entity.showdownId, displayName: entity.displayName }));
  if (selected.length !== 6) return [];
  const key = normalizedSpecies(selected).join('|');
  return teams.filter((team: StoredTeam) => {
    const members = teamMembers(team);
    return members.length === 6 && normalizedSpecies(members).join('|') === key;
  });
}

export function buildBattleSession(draft: TeamPreviewReviewDraft, selectedOwnTeamId: string,
  now: string = new Date().toISOString(), sessionSuffix: number = Date.now()): StoredBattleSession {
  if (!allTeamPreviewSlotsConfirmed(draft)) throw new Error('Please confirm all twelve team-preview slots first');
  const opponents: StoredEntity[] = draft.opponent.map((slot: TeamPreviewReviewSlot): StoredEntity => ({
    entityType: 'species', canonicalId: (slot.selected as EntityRef).canonicalId,
    showdownId: (slot.selected as EntityRef).showdownId, displayName: (slot.selected as EntityRef).displayName }));
  return { schemaVersion: 6, kind: 'BattleSession', sessionId: `battle-${sessionSuffix}`, createdAt: now,
    previewCapturedAt: draft.capturedAt, selectedOwnTeamId, opponentTeam: opponents,
    calculationSelection: { battleType: 'DOUBLE', direction: 'OWN_TO_OPPONENT', ownSlot: 0, opponentSlot: 0 } };
}

export function buildBattleSessionFromSetup(draft: TeamPreviewReviewDraft, selectedOwnTeamId: string,
  now: string = new Date().toISOString(), sessionSuffix: number = Date.now()): StoredBattleSession {
  if (!selectedOwnTeamId || !teamPreviewReadyForSession(draft)) {
    throw new Error('Please select an own team and confirm every low-confidence team-preview slot first');
  }
  const opponents: StoredEntity[] = draft.opponent.map((slot: TeamPreviewReviewSlot): StoredEntity => ({
    entityType: 'species', canonicalId: (slot.selected as EntityRef).canonicalId,
    showdownId: (slot.selected as EntityRef).showdownId, displayName: (slot.selected as EntityRef).displayName }));
  return { schemaVersion: 6, kind: 'BattleSession', sessionId: `battle-${sessionSuffix}`, createdAt: now,
    previewCapturedAt: draft.capturedAt, selectedOwnTeamId, opponentTeam: opponents,
    calculationSelection: { battleType: 'DOUBLE', direction: 'OWN_TO_OPPONENT', ownSlot: 0, opponentSlot: 0 } };
}
