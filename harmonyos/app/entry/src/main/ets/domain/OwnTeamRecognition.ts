import { EntityRef, EntityType, MoveValue, PokemonBuild, StatValues } from './Models';
import { StoredEntity, StoredMove, StoredPokemon, StoredTeam } from '../storage/StorageContracts';

export type OwnTeamPageType = 'MOVE_ITEM' | 'STATS';

export interface OcrPoint {
  x: number;
  y: number;
}

export interface OcrWord {
  text: string;
  points: OcrPoint[];
}

export interface OcrLine {
  text: string;
  points: OcrPoint[];
  words: OcrWord[];
}

export interface OcrCard {
  width: number;
  height: number;
  lines: OcrLine[];
}

export interface RecognizedEntity extends EntityRef {
  originalText: string;
  confidence: number;
  source: 'ocr';
}

export interface RecognizedOwnTeamSlot {
  slotIndex: number;
  species?: RecognizedEntity;
  ability?: RecognizedEntity;
  item?: RecognizedEntity;
  moves: RecognizedEntity[];
  moveSlotIndexes: number[];
  actualStats: StatValues;
}

export interface RecognizedOwnTeamPage {
  sceneType: 'OWN_TEAM_MOVE_ITEM' | 'OWN_TEAM_STATS';
  image: {
    width: number;
    height: number;
    capturedAt: string;
    frameHash?: number;
  };
  slots: RecognizedOwnTeamSlot[];
  recognition: {
    recognized: number;
    total: number;
    rate: number;
  };
}

export interface OwnTeamImportDraft {
  schemaVersion: number;
  kind: 'OwnTeamImportDraft';
  moveItemPage?: RecognizedOwnTeamPage;
  statsPage?: RecognizedOwnTeamPage;
}

export interface OwnTeamCorrectionSlot {
  slotIndex: number;
  species?: EntityRef;
  speciesConfirmed: boolean;
  ability?: EntityRef;
  item?: EntityRef;
  itemResolved: boolean;
  moves: MoveValue[];
  recognizedMoveSlotIndexes: number[];
  actualStats: StatValues;
}

export interface OwnTeamCorrectionDraft {
  moveRecognized: number;
  moveTotal: number;
  statsRecognized: number;
  statsTotal: number;
  moveItemCapturedAt: string;
  statsCapturedAt: string;
  slots: OwnTeamCorrectionSlot[];
}

export interface OwnTeamDraftUpdate {
  draft: OwnTeamImportDraft;
  restarted: boolean;
  nextStep: 'CAPTURE_MOVE_ITEM' | 'CAPTURE_STATS' | 'MANUAL_CORRECTION';
  message: string;
}

export interface OwnTeamEntityResolver {
  resolve(text: string, entityType: EntityType): RecognizedEntity | undefined;
}

export interface RelativeRegion {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

const STAT_KEYS: string[] = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];
const STAT_LABELS: Record<string, string> = {
  hp: '生命', atk: '攻击', def: '防御', spa: '特攻', spd: '特防', spe: '速度'
};
const STAT_CELLS: Record<string, RelativeRegion> = {
  hp: { left: 0.24, right: 0.39, top: 0.21, bottom: 0.41 },
  atk: { left: 0.24, right: 0.39, top: 0.43, bottom: 0.64 },
  def: { left: 0.24, right: 0.39, top: 0.66, bottom: 0.90 },
  spa: { left: 0.71, right: 0.86, top: 0.21, bottom: 0.41 },
  spd: { left: 0.71, right: 0.86, top: 0.43, bottom: 0.64 },
  spe: { left: 0.71, right: 0.86, top: 0.66, bottom: 0.90 }
};

export function statCellRegion(stat: string): RelativeRegion {
  return STAT_CELLS[stat];
}

export function selectUnambiguousRecognitionEntity(candidates: RecognizedEntity[],
  ambiguityMargin: number = 0.01): RecognizedEntity | undefined {
  const bestByEntity: Record<string, RecognizedEntity> = {};
  for (const candidate of candidates) {
    const current = bestByEntity[candidate.canonicalId];
    if (!current || candidate.confidence > current.confidence ||
      (candidate.confidence === current.confidence && normalizedText(candidate.originalText).length >
      normalizedText(current.originalText).length)) bestByEntity[candidate.canonicalId] = candidate;
  }
  const ranked = Object.values(bestByEntity).sort((left: RecognizedEntity, right: RecognizedEntity): number =>
    right.confidence - left.confidence || normalizedText(right.originalText).length -
    normalizedText(left.originalText).length);
  const best = ranked[0];
  const runnerUp = ranked[1];
  return best && runnerUp && best.confidence - runnerUp.confidence <= ambiguityMargin ? undefined : best;
}

export function selectStatValueCandidates(candidates: Array<number | undefined>): number | undefined {
  const values: Array<[number, number]> = [];
  candidates.forEach((value: number | undefined, index: number): void => {
    if (value !== undefined) values.push([index, value]);
  });
  if (values.length === 0) return undefined;
  const groups: Record<string, Array<[number, number]>> = {};
  for (const value of values) {
    const key = String(value[1]);
    if (!groups[key]) groups[key] = [];
    groups[key].push(value);
  }
  const keys = Object.keys(groups).map((value: string) => Number(value));
  keys.sort((left: number, right: number): number => groups[String(right)].length - groups[String(left)].length ||
    Number(right > 32) - Number(left > 32) || groups[String(left)][0][0] - groups[String(right)][0][0]);
  return keys[0];
}

export function correctSixNineDigitConfusions(value: number, holeYs: Array<number | undefined>): number {
  const corrected = value.toString().split('').map((digit: string, index: number): string => {
    const holeY = holeYs[index];
    if (digit === '6' && holeY !== undefined && holeY < 0.45) return '9';
    if (digit === '9' && holeY !== undefined && holeY > 0.55) return '6';
    return digit;
  }).join('');
  const parsed = Number(corrected);
  return Number.isInteger(parsed) ? parsed : value;
}

export function correctTwoThreeMiddleDigitConfusion(value: number, lowerLeftRatio?: number): number {
  if (value < 100 || value > 999 || lowerLeftRatio === undefined) return value;
  const middle = Math.floor(value / 10) % 10;
  if (middle === 2 && lowerLeftRatio <= 0.38) return value + 10;
  if (middle === 3 && lowerLeftRatio >= 0.43) return value - 10;
  return value;
}

export function normalizeStatValueDigitCount(value: number, detectedDigitCount: number): number {
  const digits = value.toString();
  if (detectedDigitCount < 1 || detectedDigitCount > 3 || digits.length <= detectedDigitCount) return value;
  const parsed = Number(digits.slice(0, detectedDigitCount));
  return Number.isInteger(parsed) ? parsed : value;
}

export function statCropHorizontalRanges(region: RelativeRegion): RelativeRegion[] {
  if (region.left < 0.5) {
    return [
      { left: 0.24, right: 0.39, top: region.top, bottom: region.bottom },
      { left: 0.23, right: 0.415, top: region.top, bottom: region.bottom }
    ];
  }
  return [
    { left: 0.71, right: 0.86, top: region.top, bottom: region.bottom },
    { left: 0.70, right: 0.885, top: region.top, bottom: region.bottom }
  ];
}

export function entityCropRegions(field: string): RelativeRegion[] {
  if (field === 'species') return [{ left: 0.04, right: 0.58, top: 0.0, bottom: 0.30 }];
  if (field === 'ability') return [{ left: 0.04, right: 0.58, top: 0.20, bottom: 0.55 }];
  if (field === 'item') return [{ left: 0.04, right: 0.58, top: 0.45, bottom: 0.82 }];
  const row = Number(field.replace('move', ''));
  const tops: number[] = [0.0, 0.25, 0.48, 0.71];
  const bottoms: number[] = [0.31, 0.55, 0.79, 1.0];
  return [
    { left: 0.50, right: 0.99, top: tops[row], bottom: bottoms[row] },
    { left: 0.66, right: 0.99, top: tops[row], bottom: bottoms[row] }
  ];
}

function normalizedText(value: string): string {
  return value.normalize('NFKC').replace(/\s+/g, '');
}

function bounds(points: OcrPoint[]): RelativeRegion {
  if (points.length === 0) return { left: 0, right: 0, top: 0, bottom: 0 };
  let left = points[0].x;
  let right = points[0].x;
  let top = points[0].y;
  let bottom = points[0].y;
  for (const point of points) {
    left = Math.min(left, point.x);
    right = Math.max(right, point.x);
    top = Math.min(top, point.y);
    bottom = Math.max(bottom, point.y);
  }
  return { left, right, top, bottom };
}

function center(line: OcrLine, card: OcrCard): OcrPoint {
  const box = bounds(line.points);
  return {
    x: (box.left + box.right) / 2 / Math.max(1, card.width),
    y: (box.top + box.bottom) / 2 / Math.max(1, card.height)
  };
}

function inRegion(point: OcrPoint, region: RelativeRegion): boolean {
  return point.x >= region.left && point.x <= region.right && point.y >= region.top && point.y <= region.bottom;
}

function fieldContains(field: string, point: OcrPoint): boolean {
  if (field === 'species') return point.x < 0.58 && point.y < 0.29;
  if (field === 'ability') return point.x < 0.58 && point.y >= 0.20 && point.y < 0.55;
  if (field === 'item') return point.x < 0.58 && point.y >= 0.45 && point.y < 0.82;
  if (!field.startsWith('move') || point.x < 0.50) return false;
  const moveIndex = Number(field.slice(4));
  const row = point.y < 0.29 ? 0 : point.y < 0.52 ? 1 : point.y < 0.75 ? 2 : 3;
  return row === moveIndex;
}

function lineCandidates(line: OcrLine): string[] {
  const values: string[] = [line.text];
  for (const word of line.words) values.push(word.text);
  const result: string[] = [];
  for (const raw of values) {
    const normalized = normalizedText(raw);
    if (normalized.length === 0) continue;
    const stripped = normalized.replace(/^(宝可梦|特性|道具|招式|能力)[：:·・-]?/, '');
    if (!result.includes(normalized)) result.push(normalized);
    if (stripped.length > 0 && !result.includes(stripped)) result.push(stripped);
  }
  return result;
}

function resolveField(card: OcrCard, field: string, entityType: EntityType,
  resolver: OwnTeamEntityResolver): RecognizedEntity | undefined {
  const candidates: RecognizedEntity[] = [];
  for (const line of card.lines) {
    if (!fieldContains(field, center(line, card))) continue;
    for (const text of lineCandidates(line)) {
      const entity = resolver.resolve(text, entityType);
      if (entity) candidates.push(entity);
    }
  }
  return selectUnambiguousRecognitionEntity(candidates);
}

function parseNumericText(text: string, stat: string): number[] {
  const corrected = normalizedText(text).replace(/[Il|!]/g, '1').replace(/[Oo]/g, '0');
  const matches = corrected.match(/\d{1,3}/g) ?? [];
  const values: number[] = [];
  for (const match of matches) {
    const value = Number(match);
    if (!Number.isInteger(value) || value > 500) continue;
    if ((stat === 'hp' && value >= 1) || (stat !== 'hp' && value >= 10)) values.push(value);
  }
  return values;
}

function readStat(card: OcrCard, stat: string): number | undefined {
  const region = STAT_CELLS[stat];
  const candidates: number[] = [];
  for (const line of card.lines) {
    if (!inRegion(center(line, card), region)) continue;
    for (const word of line.words) candidates.push(...parseNumericText(word.text, stat));
    candidates.push(...parseNumericText(line.text, stat));
  }
  return selectStatValueCandidates(candidates);
}

export function classifyOwnTeamPage(cards: OcrCard[], resolver: OwnTeamEntityResolver): OwnTeamPageType {
  let statEvidence = 0;
  let moveItemEvidence = 0;
  for (const card of cards) {
    for (const stat of STAT_KEYS) if (readStat(card, stat) !== undefined) statEvidence += 1;
    if (resolveField(card, 'ability', 'ability', resolver)) moveItemEvidence += 1;
    if (resolveField(card, 'item', 'item', resolver)) moveItemEvidence += 1;
    for (let index = 0; index < 4; index += 1) {
      if (resolveField(card, `move${index}`, 'move', resolver)) moveItemEvidence += 1;
    }
  }
  if (moveItemEvidence >= 6) return 'MOVE_ITEM';
  if (statEvidence >= 6) return 'STATS';
  return 'MOVE_ITEM';
}

export function parseOwnTeamCards(cards: OcrCard[], resolver: OwnTeamEntityResolver, width: number, height: number,
  capturedAt: string, frameHash?: number): RecognizedOwnTeamPage {
  if (cards.length !== 6) throw new Error(`应检测到 6 个队伍卡片，实际检测到 ${cards.length} 个`);
  const type = classifyOwnTeamPage(cards, resolver);
  const slots: RecognizedOwnTeamSlot[] = cards.map((card: OcrCard, slotIndex: number): RecognizedOwnTeamSlot => {
    const species = resolveField(card, 'species', 'species', resolver);
    if (type === 'STATS') {
      const actualStats: StatValues = {};
      for (const stat of STAT_KEYS) {
        const value = readStat(card, stat);
        if (value !== undefined) actualStats[stat as keyof StatValues] = value;
      }
      return { slotIndex, species, moves: [], moveSlotIndexes: [], actualStats };
    }
    const indexedMoves: Array<[number, RecognizedEntity | undefined]> = [];
    for (let index = 0; index < 4; index += 1) {
      indexedMoves.push([index, resolveField(card, `move${index}`, 'move', resolver)]);
    }
    return {
      slotIndex,
      species,
      ability: resolveField(card, 'ability', 'ability', resolver),
      item: resolveField(card, 'item', 'item', resolver),
      moves: indexedMoves.filter((entry: [number, RecognizedEntity | undefined]) => entry[1] !== undefined)
        .map((entry: [number, RecognizedEntity | undefined]) => entry[1] as RecognizedEntity),
      moveSlotIndexes: indexedMoves.filter((entry: [number, RecognizedEntity | undefined]) => entry[1] !== undefined)
        .map((entry: [number, RecognizedEntity | undefined]) => entry[0]),
      actualStats: {}
    };
  });
  let recognized = 0;
  for (const slot of slots) {
    recognized += slot.species ? 1 : 0;
    if (type === 'STATS') recognized += Object.keys(slot.actualStats).length;
    else recognized += (slot.ability ? 1 : 0) + (slot.item ? 1 : 0) + slot.moves.length;
  }
  const total = 42;
  const image: RecognizedOwnTeamPage['image'] = { width, height, capturedAt };
  if (frameHash !== undefined) image.frameHash = frameHash;
  return {
    sceneType: type === 'STATS' ? 'OWN_TEAM_STATS' : 'OWN_TEAM_MOVE_ITEM',
    image,
    slots,
    recognition: { recognized, total, rate: recognized / total }
  };
}

export function acceptOwnTeamPage(previous: OwnTeamImportDraft | undefined,
  page: RecognizedOwnTeamPage): OwnTeamDraftUpdate {
  const draft: OwnTeamImportDraft = previous ? JSON.parse(JSON.stringify(previous)) as OwnTeamImportDraft :
    { schemaVersion: 1, kind: 'OwnTeamImportDraft' };
  let restarted = false;
  if (page.sceneType === 'OWN_TEAM_MOVE_ITEM') {
    restarted = draft.moveItemPage !== undefined || draft.statsPage !== undefined;
    draft.moveItemPage = page;
    draft.statsPage = undefined;
  } else {
    draft.statsPage = page;
  }
  if (!draft.moveItemPage) {
    return { draft, restarted, nextStep: 'CAPTURE_MOVE_ITEM',
      message: '能力值页面已识别，请继续识别同一支队伍的“招式与道具”页面' };
  }
  if (!draft.statsPage) {
    return { draft, restarted, nextStep: 'CAPTURE_STATS', message: restarted ?
      '检测到新的“招式与道具”页面，已重新开始录入；请继续识别它的“能力值”页面' :
      '“招式与道具”页面已识别，请继续识别同一支队伍的“能力值”页面' };
  }
  return { draft, restarted, nextStep: 'MANUAL_CORRECTION',
    message: '两张队伍页面均已识别，请返回助手逐项核对后命名保存' };
}

function plainEntity(value: RecognizedEntity): EntityRef {
  return {
    entityType: value.entityType,
    canonicalId: value.canonicalId,
    showdownId: value.showdownId,
    displayName: value.displayName,
    originalText: value.originalText,
    confidence: value.confidence,
    source: 'ocr'
  };
}

export function buildOwnTeamCorrectionDraft(draft: OwnTeamImportDraft): OwnTeamCorrectionDraft {
  const move = draft.moveItemPage;
  const stats = draft.statsPage;
  if (!move || !stats) throw new Error('两张队伍页面尚未收集完整');
  const slots: OwnTeamCorrectionSlot[] = [];
  for (let slotIndex = 0; slotIndex < 6; slotIndex += 1) {
    const moveSlot = move.slots.find((slot: RecognizedOwnTeamSlot) => slot.slotIndex === slotIndex);
    const statsSlot = stats.slots.find((slot: RecognizedOwnTeamSlot) => slot.slotIndex === slotIndex);
    const moveSpecies = moveSlot?.species;
    const statsSpecies = statsSlot?.species;
    const conflict = moveSpecies !== undefined && statsSpecies !== undefined &&
      moveSpecies.canonicalId !== statsSpecies.canonicalId;
    let species = moveSpecies ?? statsSpecies;
    if (conflict && (statsSpecies?.confidence ?? 0) > (moveSpecies?.confidence ?? 0)) species = statsSpecies;
    slots.push({
      slotIndex,
      species: species ? plainEntity(species) : undefined,
      speciesConfirmed: species !== undefined && !conflict,
      ability: moveSlot?.ability ? plainEntity(moveSlot.ability) : undefined,
      item: moveSlot?.item ? plainEntity(moveSlot.item) : undefined,
      itemResolved: moveSlot?.item !== undefined,
      moves: (moveSlot?.moves ?? []).slice(0, 4).map((entity: RecognizedEntity): MoveValue => ({
        entity: plainEntity(entity), source: 'OWN_BUILD'
      })),
      recognizedMoveSlotIndexes: moveSlot?.moveSlotIndexes ?? [],
      actualStats: statsSlot?.actualStats ?? {}
    });
  }
  return {
    moveRecognized: move.recognition.recognized,
    moveTotal: move.recognition.total,
    statsRecognized: stats.recognition.recognized,
    statsTotal: stats.recognition.total,
    moveItemCapturedAt: move.image.capturedAt,
    statsCapturedAt: stats.image.capturedAt,
    slots
  };
}

export function requiredOwnTeamMoveCount(species: EntityRef | undefined): number {
  const canonical = species?.canonicalId.toLocaleLowerCase() ?? '';
  const showdown = species?.showdownId.toLocaleLowerCase() ?? '';
  return canonical === 'species.ditto' || showdown === 'ditto' ? 1 : 4;
}

export function unresolvedOwnTeamFields(slot: OwnTeamCorrectionSlot): string[] {
  const result: string[] = [];
  if (!slot.species) result.push('宝可梦');
  else if (!slot.speciesConfirmed) result.push('宝可梦（两页结果冲突，请确认）');
  if (!slot.ability) result.push('特性');
  if (!slot.itemResolved) result.push('道具（或确认无道具）');
  const moveIds = slot.moves.map((move: MoveValue) => move.entity.showdownId.toLocaleLowerCase());
  const uniqueMoves = new Set<string>(moveIds);
  const required = requiredOwnTeamMoveCount(slot.species);
  if (uniqueMoves.size < required) {
    for (let index = uniqueMoves.size; index < required; index += 1) result.push(`招式 ${index + 1}`);
  }
  if (uniqueMoves.size !== moveIds.length) result.push('招式重复');
  const stats = slot.actualStats;
  for (const key of STAT_KEYS) {
    const value = stats[key as keyof StatValues] ?? 0;
    if (value <= 0) result.push(`能力值：${STAT_LABELS[key]}`);
  }
  return result;
}

export function ownTeamCorrectionComplete(slots: OwnTeamCorrectionSlot[]): boolean {
  return slots.length === 6 && slots.every((slot: OwnTeamCorrectionSlot) => unresolvedOwnTeamFields(slot).length === 0);
}

function entityToStored(value: EntityRef): StoredEntity {
  return { entityType: value.entityType, canonicalId: value.canonicalId, showdownId: value.showdownId,
    displayName: value.displayName ?? value.showdownId };
}

function pokemonToStored(slot: OwnTeamCorrectionSlot): StoredPokemon {
  const species = slot.species as EntityRef;
  const moves: StoredMove[] = slot.moves.slice(0, 4).map((move: MoveValue): StoredMove => ({
    move: entityToStored(move.entity), source: 'OWN_BUILD', basePower: move.basePower, type: move.type
  }));
  const pokemon: StoredPokemon = { species: entityToStored(species), level: 50,
    actualStats: slot.actualStats, statPoints: {}, moves };
  if (slot.ability) pokemon.ability = entityToStored(slot.ability);
  if (slot.item) pokemon.item = entityToStored(slot.item);
  const build: StoredPokemon = JSON.parse(JSON.stringify(pokemon)) as StoredPokemon;
  pokemon.build = build;
  return pokemon;
}

export function buildSavedOwnTeam(name: string, correction: OwnTeamCorrectionDraft,
  timestamp: number = Date.now()): StoredTeam {
  const normalizedName = name.trim();
  if (normalizedName.length < 1 || normalizedName.length > 30) throw new Error('队伍名称应为 1–30 个字符');
  if (!ownTeamCorrectionComplete(correction.slots)) throw new Error('队伍仍有内容需要补全');
  const id = `harmony-own-team-${timestamp}`;
  return {
    schemaVersion: 1,
    kind: 'SavedOwnTeam',
    savedTeamId: id,
    teamName: normalizedName,
    teamSlotName: normalizedName,
    pokemon: correction.slots.slice().sort((left: OwnTeamCorrectionSlot, right: OwnTeamCorrectionSlot) =>
      left.slotIndex - right.slotIndex).map((slot: OwnTeamCorrectionSlot) => pokemonToStored(slot))
  };
}

export function pokemonBuildFromCorrection(slot: OwnTeamCorrectionSlot): PokemonBuild | undefined {
  if (!slot.species) return undefined;
  return { species: slot.species, level: 50, actualStats: slot.actualStats, statPoints: {}, ability: slot.ability,
    item: slot.item, moves: slot.moves };
}
