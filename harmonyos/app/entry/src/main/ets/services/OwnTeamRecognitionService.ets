import { textRecognition } from '@kit.CoreVisionKit';
import { image } from '@kit.ImageKit';
import { CaptureRect } from 'libpcbridge.so';
import { EntityRef, EntityType, StatValues } from '../domain/Models';
import {
  correctSixNineDigitConfusions,
  correctTwoThreeMiddleDigitConfusion,
  entityCropRegions,
  normalizeStatValueDigitCount,
  OcrCard,
  OcrLine,
  OcrPoint,
  OcrWord,
  OwnTeamEntityResolver,
  parseOwnTeamCards,
  RecognizedEntity,
  RecognizedOwnTeamPage,
  RecognizedOwnTeamSlot,
  OwnTeamPageType,
  RelativeRegion,
  selectStatValueCandidates,
  selectUnambiguousRecognitionEntity,
  statCellRegion,
  statCropHorizontalRanges
} from '../domain/OwnTeamRecognition';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import { StableCaptureFrame } from './ScreenCaptureService';

interface RgbaCrop {
  data: ArrayBuffer;
  width: number;
  height: number;
}

interface BrightComponent {
  left: number;
  top: number;
  right: number;
  bottom: number;
  area: number;
}

const STAT_IDS: string[] = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];

function normalizeLookup(value: string): string {
  return value.normalize('NFKC').replace(/\s+/g, '').replace(/[·・’']/g, '').toLocaleLowerCase();
}

function distanceWithinOne(left: string, right: string): boolean {
  if (Math.abs(left.length - right.length) > 1) return false;
  let edits = 0;
  let leftIndex = 0;
  let rightIndex = 0;
  while (leftIndex < left.length && rightIndex < right.length) {
    if (left[leftIndex] === right[rightIndex]) {
      leftIndex += 1;
      rightIndex += 1;
      continue;
    }
    edits += 1;
    if (edits > 1) return false;
    if (left.length > right.length) leftIndex += 1;
    else if (right.length > left.length) rightIndex += 1;
    else {
      leftIndex += 1;
      rightIndex += 1;
    }
  }
  return edits + Number(leftIndex < left.length || rightIndex < right.length) <= 1;
}

function recognized(entity: EntityRef, originalText: string, confidence: number): RecognizedEntity {
  return {
    entityType: entity.entityType,
    canonicalId: entity.canonicalId,
    showdownId: entity.showdownId,
    displayName: entity.displayName,
    originalText,
    confidence,
    source: 'ocr'
  };
}

class CatalogEntityResolver implements OwnTeamEntityResolver {
  private catalog: RuntimeDataRepository;

  constructor(catalog: RuntimeDataRepository) {
    this.catalog = catalog;
  }

  resolve(text: string, entityType: EntityType): RecognizedEntity | undefined {
    const input = normalizeLookup(text);
    if (input.length === 0) return undefined;
    const exact = this.catalog.localize(entityType, text) ?? this.catalog.localize(entityType, input);
    if (exact) return recognized(exact, text, 1.0);
    const candidates: RecognizedEntity[] = [];
    for (const entity of this.catalog.entityCatalog(entityType)) {
      const aliases: string[] = [entity.showdownId, entity.displayName ?? ''];
      let score = 0;
      for (const alias of aliases) {
        const normalizedAlias = normalizeLookup(alias);
        if (normalizedAlias.length === 0) continue;
        if (input === normalizedAlias) score = Math.max(score, 0.96);
        else if (input.length >= 2 && normalizedAlias.includes(input)) {
          score = Math.max(score, 0.78 + 0.08 * input.length / normalizedAlias.length);
        } else if (normalizedAlias.length >= 2 && input.includes(normalizedAlias)) {
          score = Math.max(score, 0.82 + 0.08 * normalizedAlias.length / input.length);
        } else if (input.length >= 2 && distanceWithinOne(input, normalizedAlias)) score = Math.max(score, 0.74);
      }
      if (score > 0) candidates.push(recognized(entity, text, score));
    }
    return selectUnambiguousRecognitionEntity(candidates);
  }
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

function cropRgba(source: ArrayBuffer, sourceWidth: number, sourceHeight: number,
  card: CaptureRect, region?: RelativeRegion, horizontalPadding: number = 0): RgbaCrop {
  const cardWidth = card.right - card.left;
  const cardHeight = card.bottom - card.top;
  const target = region ?? { left: 0, right: 1, top: 0, bottom: 1 };
  const left = clamp(Math.floor(card.left + cardWidth * Math.max(0, target.left - horizontalPadding)),
    0, sourceWidth - 1);
  const right = clamp(Math.ceil(card.left + cardWidth * Math.min(1, target.right + horizontalPadding)),
    left + 1, sourceWidth);
  const top = clamp(Math.floor(card.top + cardHeight * target.top), 0, sourceHeight - 1);
  const bottom = clamp(Math.ceil(card.top + cardHeight * target.bottom), top + 1, sourceHeight);
  const width = right - left;
  const height = bottom - top;
  const sourceBytes = new Uint8Array(source);
  const output = new Uint8Array(width * height * 4);
  for (let row = 0; row < height; row += 1) {
    const sourceOffset = ((top + row) * sourceWidth + left) * 4;
    const targetOffset = row * width * 4;
    output.set(sourceBytes.subarray(sourceOffset, sourceOffset + width * 4), targetOffset);
  }
  const result: RgbaCrop = {
    data: output.buffer as ArrayBuffer,
    width: width,
    height: height
  };
  return result;
}

function thresholdForDigits(crop: RgbaCrop, threshold: number = 180): RgbaCrop {
  const source = new Uint8Array(crop.data);
  const output = new Uint8Array(source.length);
  for (let index = 0; index < source.length; index += 4) {
    const gray = Math.floor((source[index] * 299 + source[index + 1] * 587 + source[index + 2] * 114) / 1000);
    const value = gray >= threshold ? 0 : 255;
    output[index] = value;
    output[index + 1] = value;
    output[index + 2] = value;
    output[index + 3] = 255;
  }
  const result: RgbaCrop = {
    data: output.buffer as ArrayBuffer,
    width: crop.width,
    height: crop.height
  };
  return result;
}

function points(values: textRecognition.PixelPoint[], scale: number): OcrPoint[] {
  return values.map((point: textRecognition.PixelPoint): OcrPoint => ({
    x: point.x / scale,
    y: point.y / scale
  }));
}

function toOcrLines(result: textRecognition.TextRecognitionResult, scale: number): OcrLine[] {
  const lines: OcrLine[] = [];
  for (const block of result.blocks) {
    for (const line of block.lines) {
      const words: OcrWord[] = line.words.map((word: textRecognition.TextWord): OcrWord => ({
        text: word.value,
        points: points(word.cornerPoints, scale)
      }));
      lines.push({ text: line.value, points: points(line.cornerPoints, scale), words });
    }
  }
  return lines;
}

async function recognizeCrop(crop: RgbaCrop, scale: number): Promise<OcrCard> {
  const pixelMap: image.PixelMap = await image.createPixelMap(crop.data, {
    size: { width: crop.width, height: crop.height },
    srcPixelFormat: image.PixelMapFormat.RGBA_8888,
    pixelFormat: image.PixelMapFormat.RGBA_8888,
    editable: true
  });
  try {
    await pixelMap.scale(scale, scale);
    const result: textRecognition.TextRecognitionResult = await textRecognition.recognizeText({ pixelMap });
    return { width: crop.width, height: crop.height, lines: toOcrLines(result, scale) };
  } finally {
    pixelMap.release();
  }
}

function cardTexts(card: OcrCard): string[] {
  const result: string[] = [];
  for (const line of card.lines) {
    result.push(line.text);
    for (const word of line.words) result.push(word.text);
  }
  return result;
}

function resolveCardTexts(card: OcrCard, entityType: EntityType,
  resolver: OwnTeamEntityResolver): RecognizedEntity[] {
  const result: RecognizedEntity[] = [];
  for (const text of cardTexts(card)) {
    const candidate = resolver.resolve(text.normalize('NFKC'), entityType);
    if (candidate) result.push(candidate);
  }
  return result;
}

function definedEntities(first?: RecognizedEntity, second?: RecognizedEntity): RecognizedEntity[] {
  const values: RecognizedEntity[] = [];
  if (first) values.push(first);
  if (second) values.push(second);
  return values;
}

async function recognizeEntityRegion(frame: StableCaptureFrame, card: CaptureRect, region: RelativeRegion,
  entityType: EntityType, resolver: OwnTeamEntityResolver): Promise<RecognizedEntity | undefined> {
  const crop = cropRgba(frame.data, frame.width, frame.height, card, region, 0.01);
  const original = await recognizeCrop(crop, 3);
  const thresholded = await recognizeCrop(thresholdForDigits(crop), 3);
  return selectUnambiguousRecognitionEntity(resolveCardTexts(original, entityType, resolver)
    .concat(resolveCardTexts(thresholded, entityType, resolver)));
}

async function recognizeEntityCrop(frame: StableCaptureFrame, card: CaptureRect, field: string,
  entityType: EntityType, resolver: OwnTeamEntityResolver): Promise<RecognizedEntity | undefined> {
  const regions = entityCropRegions(field);
  const primary = await recognizeEntityRegion(frame, card, regions[0], entityType, resolver);
  if (regions.length === 1 || (primary && primary.confidence >= 0.90)) return primary;
  const retry = await recognizeEntityRegion(frame, card, regions[1], entityType, resolver);
  return selectUnambiguousRecognitionEntity(definedEntities(primary, retry));
}

function numericCandidates(text: string, stat: string): number[] {
  const normalized = text.normalize('NFKC').replace(/[Il|!]/g, '1').replace(/[Oo]/g, '0');
  const matches = normalized.match(/\d{1,3}/g) ?? [];
  const values = matches.map((value: string) => Number(value)).filter((value: number): boolean =>
    Number.isInteger(value) && value <= 500 && (stat === 'hp' ? value >= 1 : value >= 10));
  values.sort((left: number, right: number): number => Number(right > 32) - Number(left > 32) || right - left);
  return values;
}

function readStatValue(card: OcrCard, stat: string): number | undefined {
  const values: number[] = [];
  for (const text of cardTexts(card)) values.push(...numericCandidates(text, stat));
  return values[0];
}

function brightPixels(crop: RgbaCrop): boolean[] {
  const source = new Uint8Array(crop.data);
  const result: boolean[] = new Array<boolean>(crop.width * crop.height).fill(false);
  for (let pixel = 0; pixel < result.length; pixel += 1) {
    const index = pixel * 4;
    result[pixel] = Math.floor((source[index] * 299 + source[index + 1] * 587 + source[index + 2] * 114) / 1000) >= 180;
  }
  return result;
}

function digitComponents(crop: RgbaCrop, bright: boolean[]): BrightComponent[] {
  const visited: boolean[] = new Array<boolean>(bright.length).fill(false);
  const queue = new Int32Array(bright.length);
  const components: BrightComponent[] = [];
  for (let start = 0; start < bright.length; start += 1) {
    if (!bright[start] || visited[start]) continue;
    let head = 0;
    let tail = 0;
    queue[tail++] = start;
    visited[start] = true;
    let left = crop.width;
    let top = crop.height;
    let right = 0;
    let bottom = 0;
    let area = 0;
    while (head < tail) {
      const current = queue[head++];
      const x = current % crop.width;
      const y = Math.floor(current / crop.width);
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x);
      bottom = Math.max(bottom, y);
      area += 1;
      const neighbours: number[] = [];
      if (x > 0) neighbours.push(current - 1);
      if (x < crop.width - 1) neighbours.push(current + 1);
      if (y > 0) neighbours.push(current - crop.width);
      if (y < crop.height - 1) neighbours.push(current + crop.width);
      for (const next of neighbours) {
        if (bright[next] && !visited[next]) {
          visited[next] = true;
          queue[tail++] = next;
        }
      }
    }
    const height = bottom + 1 - top;
    if (area >= 30 && top > crop.height * 0.35 && height >= Math.floor(crop.height * 0.25) &&
      height <= Math.floor(crop.height * 0.65)) {
      components.push({ left, top, right: right + 1, bottom: bottom + 1, area });
    }
  }
  components.sort((left: BrightComponent, right: BrightComponent): number => left.left - right.left);
  return components;
}

function digitHoleY(bright: boolean[], sourceWidth: number, digit: BrightComponent): number | undefined {
  const localWidth = digit.right - digit.left;
  const localHeight = digit.bottom - digit.top;
  const exterior: boolean[] = new Array<boolean>(localWidth * localHeight).fill(false);
  const queue = new Int32Array(exterior.length);
  let head = 0;
  let tail = 0;
  const enqueue = (x: number, y: number): void => {
    const local = y * localWidth + x;
    const source = (digit.top + y) * sourceWidth + digit.left + x;
    if (!bright[source] && !exterior[local]) {
      exterior[local] = true;
      queue[tail++] = local;
    }
  };
  for (let x = 0; x < localWidth; x += 1) {
    enqueue(x, 0);
    enqueue(x, localHeight - 1);
  }
  for (let y = 0; y < localHeight; y += 1) {
    enqueue(0, y);
    enqueue(localWidth - 1, y);
  }
  while (head < tail) {
    const current = queue[head++];
    const x = current % localWidth;
    const y = Math.floor(current / localWidth);
    const neighbours: Array<[number, number]> = [[x - 1, y], [x + 1, y], [x, y - 1], [x, y + 1]];
    for (const pair of neighbours) {
      const nextX = pair[0];
      const nextY = pair[1];
      if (nextX < 0 || nextX >= localWidth || nextY < 0 || nextY >= localHeight) continue;
      enqueue(nextX, nextY);
    }
  }
  let holePixels = 0;
  let holeYTotal = 0;
  for (let y = 0; y < localHeight; y += 1) {
    for (let x = 0; x < localWidth; x += 1) {
      const local = y * localWidth + x;
      const source = (digit.top + y) * sourceWidth + digit.left + x;
      if (!bright[source] && !exterior[local]) {
        holePixels += 1;
        holeYTotal += y;
      }
    }
  }
  return holePixels < 4 ? undefined : holeYTotal / holePixels / localHeight;
}

function statDigitHoleYs(crop: RgbaCrop, digitCount: number): Array<number | undefined> {
  const bright = brightPixels(crop);
  return digitComponents(crop, bright).slice(0, digitCount).map((digit: BrightComponent) =>
    digitHoleY(bright, crop.width, digit));
}

function middleDigitLowerLeftRatio(crop: RgbaCrop): number | undefined {
  const bright = brightPixels(crop);
  const digits = digitComponents(crop, bright);
  if (digits.length < 3) return undefined;
  const digit = digits[1];
  const middleX = digit.left + Math.floor((digit.right - digit.left) / 2);
  const lowerY = digit.top + Math.floor((digit.bottom - digit.top) / 2);
  let leftCount = 0;
  let rightCount = 0;
  for (let y = lowerY; y < digit.bottom; y += 1) {
    for (let x = digit.left; x < digit.right; x += 1) {
      if (!bright[y * crop.width + x]) continue;
      if (x < middleX) leftCount += 1;
      else rightCount += 1;
    }
  }
  const total = leftCount + rightCount;
  return total === 0 ? undefined : leftCount / total;
}

function correctCommonDigitConfusions(value: number, crop: RgbaCrop): number {
  let corrected = value;
  if (corrected >= 10 && corrected <= 999) {
    const holeYs = statDigitHoleYs(crop, corrected.toString().length);
    corrected = normalizeStatValueDigitCount(corrected, holeYs.length);
    corrected = correctSixNineDigitConfusions(corrected, holeYs);
  }
  const middle = Math.floor(corrected / 10) % 10;
  const ratio = corrected >= 100 && corrected <= 999 && (middle === 2 || middle === 3) ?
    middleDigitLowerLeftRatio(crop) : undefined;
  return correctTwoThreeMiddleDigitConfusion(corrected, ratio);
}

function plausibleStat(value: number | undefined, stat: string): number | undefined {
  if (value === undefined || value > 500 || (stat === 'hp' ? value < 1 : value < 10)) return undefined;
  return value;
}

async function recognizeStatValue(frame: StableCaptureFrame, card: CaptureRect, stat: string,
  wholeCardValue?: number): Promise<number | undefined> {
  const cell = statCellRegion(stat);
  const expandedRanges = statCropHorizontalRanges(cell).map((range: RelativeRegion): RelativeRegion => ({
    left: range.left,
    right: range.right,
    top: Math.max(0, range.top - 0.025),
    bottom: Math.min(1, range.bottom + 0.025)
  }));
  const cropCandidates: Array<number | undefined> = [];
  const wholeCandidates: Array<number | undefined> = [];
  for (const range of expandedRanges) {
    const crop = cropRgba(frame.data, frame.width, frame.height, card, range);
    const smoothValue = readStatValue(await recognizeCrop(crop, 6), stat);
    const thresholdValue = readStatValue(await recognizeCrop(thresholdForDigits(crop), 6), stat);
    cropCandidates.push(plausibleStat(smoothValue === undefined ? undefined :
      correctCommonDigitConfusions(smoothValue, crop), stat));
    cropCandidates.push(plausibleStat(thresholdValue === undefined ? undefined :
      correctCommonDigitConfusions(thresholdValue, crop), stat));
    wholeCandidates.push(plausibleStat(wholeCardValue === undefined ? undefined :
      correctCommonDigitConfusions(wholeCardValue, crop), stat));
  }
  return selectStatValueCandidates([
    selectStatValueCandidates(cropCandidates),
    selectStatValueCandidates(wholeCandidates)
  ]);
}

function recalculateRecognition(page: RecognizedOwnTeamPage): void {
  let recognizedCount = 0;
  for (const slot of page.slots) {
    recognizedCount += slot.species ? 1 : 0;
    if (page.sceneType === 'OWN_TEAM_STATS') recognizedCount += Object.keys(slot.actualStats).length;
    else recognizedCount += (slot.ability ? 1 : 0) + (slot.item ? 1 : 0) + slot.moves.length;
  }
  page.recognition = { recognized: recognizedCount, total: 42, rate: recognizedCount / 42 };
}

export class OwnTeamRecognitionService {
  private resolver: OwnTeamEntityResolver;

  constructor(catalog: RuntimeDataRepository) {
    this.resolver = new CatalogEntityResolver(catalog);
  }

  private async refineMoveItemSlot(frame: StableCaptureFrame, card: CaptureRect,
    slot: RecognizedOwnTeamSlot): Promise<void> {
    const speciesCrop = slot.species?.confidence === 1 ? undefined :
      await recognizeEntityCrop(frame, card, 'species', 'species', this.resolver);
    slot.species = selectUnambiguousRecognitionEntity(definedEntities(slot.species, speciesCrop));
    const abilityCrop = slot.ability?.confidence === 1 ? undefined :
      await recognizeEntityCrop(frame, card, 'ability', 'ability', this.resolver);
    slot.ability = selectUnambiguousRecognitionEntity(definedEntities(slot.ability, abilityCrop));
    const itemCrop = slot.item?.confidence === 1 ? undefined :
      await recognizeEntityCrop(frame, card, 'item', 'item', this.resolver);
    slot.item = selectUnambiguousRecognitionEntity(definedEntities(slot.item, itemCrop));
    const currentByIndex: Array<RecognizedEntity | undefined> = [undefined, undefined, undefined, undefined];
    slot.moveSlotIndexes.forEach((moveIndex: number, listIndex: number): void => {
      currentByIndex[moveIndex] = slot.moves[listIndex];
    });
    const moves: RecognizedEntity[] = [];
    const indexes: number[] = [];
    for (let moveIndex = 0; moveIndex < 4; moveIndex += 1) {
      const current = currentByIndex[moveIndex];
      const crop = current?.confidence === 1 ? undefined :
        await recognizeEntityCrop(frame, card, `move${moveIndex}`, 'move', this.resolver);
      const selected = selectUnambiguousRecognitionEntity(definedEntities(current, crop));
      if (selected) {
        moves.push(selected);
        indexes.push(moveIndex);
      }
    }
    slot.moves = moves;
    slot.moveSlotIndexes = indexes;
  }

  private async refineStatsSlot(frame: StableCaptureFrame, card: CaptureRect,
    slot: RecognizedOwnTeamSlot): Promise<void> {
    const speciesCrop = slot.species?.confidence === 1 ? undefined :
      await recognizeEntityCrop(frame, card, 'species', 'species', this.resolver);
    slot.species = selectUnambiguousRecognitionEntity(definedEntities(slot.species, speciesCrop));
    const stats: StatValues = {};
    for (const stat of STAT_IDS) {
      const value = await recognizeStatValue(frame, card, stat, slot.actualStats[stat as keyof StatValues]);
      if (value !== undefined) stats[stat as keyof StatValues] = value;
    }
    slot.actualStats = stats;
  }

  async recognize(frame: StableCaptureFrame, expectedType?: OwnTeamPageType): Promise<RecognizedOwnTeamPage> {
    if (frame.cards.length !== 6) throw new Error(`应检测到 6 个队伍卡片，实际检测到 ${frame.cards.length} 个`);
    const cards: OcrCard[] = [];
    for (const card of frame.cards) {
      cards.push(await recognizeCrop(cropRgba(frame.data, frame.width, frame.height, card), 2));
    }
    const page = parseOwnTeamCards(cards, this.resolver, frame.width, frame.height,
      new Date().toISOString(), frame.hash, expectedType);
    for (let index = 0; index < page.slots.length; index += 1) {
      if (page.sceneType === 'OWN_TEAM_STATS') {
        await this.refineStatsSlot(frame, frame.cards[index], page.slots[index]);
      } else {
        await this.refineMoveItemSlot(frame, frame.cards[index], page.slots[index]);
      }
    }
    recalculateRecognition(page);
    return page;
  }
}
