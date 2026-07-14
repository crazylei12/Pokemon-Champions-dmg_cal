import type {
  OcrBackend,
  OcrTextCandidate,
  RecognitionEntityCandidate,
  RecognitionEntityType,
  RoiEntityType,
  RoiRegion,
  RoiTextRecognition,
  RoiTextRecognitionResult,
} from './types';
import type { EntityType, LocalizedNameEntry } from '../damage/types';
import { normalizeLookupText, resolveLocalizedName } from '../data/localization';

const ROI_ENTITY_TYPE_TO_DAMAGE: Partial<Record<RoiEntityType, EntityType>> = {
  SPECIES: 'species',
  MOVE: 'move',
  ITEM: 'item',
  ABILITY: 'ability',
  TYPE: 'type',
  NATURE: 'nature',
};

const ROI_ENTITY_TYPE_TO_RECOGNITION: Record<RoiEntityType, RecognitionEntityType> = {
  SPECIES: 'species',
  MOVE: 'move',
  ITEM: 'item',
  ABILITY: 'ability',
  TYPE: 'type',
  NATURE: 'nature',
  STAT: 'stat',
  UNKNOWN: 'unknown',
};

export interface ResolveOcrTextOptions {
  language?: string;
  maxCandidates?: number;
}

export interface RecognizeTextRegionsOptions<TImage> extends ResolveOcrTextOptions {
  backend: OcrBackend<TImage>;
  crops: RoiTextRecognition<TImage>[];
  nameEntries: LocalizedNameEntry[];
}

export async function recognizeTextRegions<TImage>(
  options: RecognizeTextRegionsOptions<TImage>
): Promise<RoiTextRecognitionResult[]> {
  const language = options.language || 'zh-Hans';
  const results: RoiTextRecognitionResult[] = [];

  for (const crop of options.crops) {
    const rawTextCandidates = await options.backend.recognize(crop.image, {
      language,
      region: crop.region,
      preprocess: crop.region.preprocess,
    });
    const entityCandidates = rawTextCandidates.flatMap((raw) =>
      resolveOcrTextCandidate(crop.region, raw, options.nameEntries, {
        language,
        maxCandidates: options.maxCandidates ?? crop.region.match?.topK,
      })
    );

    results.push({
      roiId: crop.region.id,
      entityType: roiEntityTypeToRecognition(crop.region.entityType),
      rawTextCandidates,
      entityCandidates: dedupeEntityCandidates(entityCandidates),
      warnings: rawTextCandidates.length ? [] : [`No OCR text returned for ${crop.region.id}.`],
    });
  }

  return results;
}

export function resolveOcrTextCandidate(
  region: RoiRegion,
  raw: OcrTextCandidate,
  entries: LocalizedNameEntry[],
  options: ResolveOcrTextOptions = {}
): RecognitionEntityCandidate[] {
  const language = options.language || raw.language || 'zh-Hans';
  const recognitionType = roiEntityTypeToRecognition(region.entityType);
  const text = raw.text.trim();
  const normalizedText = normalizeLookupText(text);
  if (!normalizedText) return [];

  const damageEntityType = roiEntityTypeToDamage(region.entityType);
  if (!damageEntityType) {
    return [
      createRawTextCandidate({
        rawText: text,
        normalizedText,
        language,
        entityType: recognitionType,
        roiId: region.id,
        confidence: raw.confidence,
      }),
    ];
  }

  const resolved = resolveLocalizedName(text, damageEntityType, entries, {
    language,
    maxCandidates: options.maxCandidates,
  });

  if (!resolved.length) {
    return [
      createRawTextCandidate({
        rawText: text,
        normalizedText,
        language,
        entityType: recognitionType,
        roiId: region.id,
        confidence: raw.confidence,
      }),
    ];
  }

  return resolved.map((candidate) => ({
    rawText: text,
    normalizedText,
    language,
    entityType: recognitionType,
    canonicalId: candidate.canonicalId,
    showdownId: candidate.showdownId,
    displayName: candidate.displayName,
    roiId: region.id,
    confidence: clamp01(raw.confidence * candidate.confidence),
    source: 'OCR_TEXT',
  }));
}

export function roiEntityTypeToRecognition(entityType?: RoiEntityType): RecognitionEntityType {
  if (!entityType) return 'unknown';
  return ROI_ENTITY_TYPE_TO_RECOGNITION[entityType] || 'unknown';
}

export function roiEntityTypeToDamage(entityType?: RoiEntityType): EntityType | undefined {
  if (!entityType) return undefined;
  return ROI_ENTITY_TYPE_TO_DAMAGE[entityType];
}

function createRawTextCandidate(input: {
  rawText: string;
  normalizedText: string;
  language: string;
  entityType: RecognitionEntityType;
  roiId: string;
  confidence: number;
}): RecognitionEntityCandidate {
  return {
    rawText: input.rawText,
    normalizedText: input.normalizedText,
    language: input.language,
    entityType: input.entityType,
    roiId: input.roiId,
    displayName: input.rawText,
    confidence: clamp01(input.confidence),
    source: 'OCR_TEXT',
  };
}

function dedupeEntityCandidates(candidates: RecognitionEntityCandidate[]) {
  const byKey = new Map<string, RecognitionEntityCandidate>();
  for (const candidate of candidates) {
    const key = [
      candidate.roiId,
      candidate.canonicalId,
      candidate.showdownId,
      candidate.normalizedText,
      candidate.displayName,
    ]
      .filter(Boolean)
      .join('|');
    const existing = byKey.get(key);
    if (!existing || candidate.confidence > existing.confidence) {
      byKey.set(key, candidate);
    }
  }
  return [...byKey.values()].sort((left, right) => right.confidence - left.confidence);
}

function clamp01(value: number) {
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}
