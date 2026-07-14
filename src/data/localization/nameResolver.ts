import type {
  EntityRef,
  EntityType,
  InputSource,
  LocalizedNameEntry,
  OcrEntityCandidate,
} from '../../damage/types';

export interface NameResolutionOptions {
  language?: string;
  source?: InputSource;
  maxCandidates?: number;
}

interface IndexedName {
  entry: LocalizedNameEntry;
  displayName: string;
  normalizedName: string;
  exact: boolean;
}

export function resolveLocalizedName(
  text: string,
  entityType: EntityType,
  entries: LocalizedNameEntry[],
  options: NameResolutionOptions = {}
): OcrEntityCandidate[] {
  const language = options.language || 'zh-Hans';
  const normalizedText = normalizeLookupText(text);
  if (!normalizedText) return [];

  const candidates: OcrEntityCandidate[] = [];
  for (const item of createLookupIndex(entries, entityType, language)) {
    const score = scoreCandidate(normalizedText, item);
    if (score <= 0) continue;
    candidates.push({
      entityType,
      text,
      confidence: score,
      source: 'ocr',
      canonicalId: item.entry.canonicalId,
      showdownId: item.entry.showdownId,
      displayName: item.displayName,
      originalText: text,
    });
  }

  return dedupeCandidates(candidates)
    .sort((left, right) => right.confidence - left.confidence)
    .slice(0, options.maxCandidates ?? 3);
}

export function toEntityRef(
  candidate: OcrEntityCandidate,
  source: InputSource = 'ocr'
): EntityRef | undefined {
  if (!candidate.canonicalId || !candidate.showdownId) return undefined;
  return {
    entityType: candidate.entityType,
    canonicalId: candidate.canonicalId,
    showdownId: candidate.showdownId,
    displayName: candidate.displayName,
    originalText: candidate.originalText || candidate.text,
    confidence: candidate.confidence,
    source,
  };
}

export function normalizeLookupText(value: string) {
  return value
    .normalize('NFKC')
    .replace(/\s+/g, '')
    .replace(/[·・]/g, '')
    .replace(/[’']/g, '')
    .toLowerCase();
}

function createLookupIndex(
  entries: LocalizedNameEntry[],
  entityType: EntityType,
  language: string
) {
  const index: IndexedName[] = [];
  for (const entry of entries) {
    if (entry.entityType !== entityType) continue;
    const localizedNames = entry.localizedNames?.[language] || [];
    const names = [
      ...localizedNames,
      ...(entry.aliases || []),
      entry.englishName,
      entry.showdownId,
    ].filter(Boolean) as string[];

    for (const name of names) {
      index.push({
        entry,
        displayName: localizedNames[0] || entry.englishName || entry.showdownId,
        normalizedName: normalizeLookupText(name),
        exact: localizedNames.includes(name),
      });
    }
  }
  return index;
}

function scoreCandidate(input: string, item: IndexedName) {
  if (input === item.normalizedName) return item.exact ? 1 : 0.96;
  if (item.normalizedName.includes(input) || input.includes(item.normalizedName)) return 0.82;
  return levenshteinWithinOne(input, item.normalizedName) ? 0.74 : 0;
}

function dedupeCandidates(candidates: OcrEntityCandidate[]) {
  const byCanonicalId = new Map<string, OcrEntityCandidate>();
  for (const candidate of candidates) {
    const key = candidate.canonicalId || candidate.showdownId || candidate.text;
    const existing = byCanonicalId.get(key);
    if (!existing || candidate.confidence > existing.confidence) {
      byCanonicalId.set(key, candidate);
    }
  }
  return [...byCanonicalId.values()];
}

function levenshteinWithinOne(left: string, right: string) {
  if (Math.abs(left.length - right.length) > 1) return false;
  let edits = 0;
  let leftIndex = 0;
  let rightIndex = 0;
  while (leftIndex < left.length && rightIndex < right.length) {
    if (left[leftIndex] === right[rightIndex]) {
      leftIndex++;
      rightIndex++;
      continue;
    }
    edits++;
    if (edits > 1) return false;
    if (left.length > right.length) leftIndex++;
    else if (right.length > left.length) rightIndex++;
    else {
      leftIndex++;
      rightIndex++;
    }
  }
  return true;
}
