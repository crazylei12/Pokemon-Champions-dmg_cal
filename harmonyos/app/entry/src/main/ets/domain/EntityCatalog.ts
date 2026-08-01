import { EntityRef, LocalizedNameEntry } from './Models';

export function normalizeShowdownId(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '');
}

export function normalizeCanonicalId(entityType: string, value: string): string {
  const prefix = `${entityType.toLowerCase()}.`;
  const withoutPrefix = value.toLowerCase().startsWith(prefix) ? value.slice(prefix.length) : value;
  const normalized = normalizeShowdownId(withoutPrefix);
  return `${prefix}${normalized}`;
}

export function entityMatches(entry: LocalizedNameEntry, value: string): boolean {
  const normalized = normalizeShowdownId(value);
  const text = value.trim().toLocaleLowerCase();
  if (normalized.length > 0 &&
    (normalizeShowdownId(entry.canonicalId) === normalized || normalizeShowdownId(entry.showdownId) === normalized)) {
    return true;
  }
  if (entry.englishName && (entry.englishName.trim().toLocaleLowerCase() === text ||
    (normalized.length > 0 && normalizeShowdownId(entry.englishName) === normalized))) {
    return true;
  }
  for (const alias of entry.aliases ?? []) {
    if (alias.trim().toLocaleLowerCase() === text ||
      (normalized.length > 0 && normalizeShowdownId(alias) === normalized)) {
      return true;
    }
  }
  const localizedNames = entry.localizedNames ?? {};
  for (const names of Object.values(localizedNames)) {
    if (names.some((name: string) => name.trim().toLocaleLowerCase() === text)) {
      return true;
    }
  }
  return false;
}

export function entitySearchMatches(entry: LocalizedNameEntry, value: string): boolean {
  const text = value.trim().toLocaleLowerCase();
  if (text.length === 0) return true;
  const normalized = normalizeShowdownId(value);
  const candidates: string[] = [entry.canonicalId, entry.showdownId, entry.englishName ?? '',
    ...(entry.aliases ?? [])];
  for (const names of Object.values(entry.localizedNames ?? {})) candidates.push(...names);
  return candidates.some((candidate: string) => {
    const candidateText = candidate.toLocaleLowerCase();
    return candidateText.includes(text) || (normalized.length > 0 && normalizeShowdownId(candidate).includes(normalized));
  });
}

export function displayNameFor(entry: LocalizedNameEntry, language: string): string {
  const localized = entry.localizedNames?.[language];
  return localized?.[0] ?? entry.englishName ?? entry.showdownId;
}

export function toEntityRef(entry: LocalizedNameEntry, language: string): EntityRef {
  return {
    entityType: entry.entityType,
    canonicalId: entry.canonicalId,
    showdownId: entry.showdownId,
    displayName: displayNameFor(entry, language),
    source: 'system'
  };
}

export function findEntity(
  entries: LocalizedNameEntry[],
  entityType: string,
  value: string,
  language: string
): EntityRef | undefined {
  const entry = entries.find((candidate: LocalizedNameEntry) =>
    candidate.entityType === entityType && entityMatches(candidate, value));
  return entry ? toEntityRef(entry, language) : undefined;
}
