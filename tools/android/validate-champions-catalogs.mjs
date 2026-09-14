import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {loadMoveCoverageInputs, validateMoveCoverage} from './validate-champions-moves.mjs';

const normalize = name => name.toLowerCase().replace(/[^a-z0-9]/g, '');
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const sorted = values => [...new Set(values)].sort();

export function readTemplateCatalog(binary, metadata) {
  if (createHash('sha256').update(binary).digest('hex') !== metadata.binary.sha256) throw new Error('Recognition binary hash mismatch');
  if (binary.subarray(0, 8).toString() !== 'PTVFEAT2' || binary.readUInt32BE(8) !== 2 ||
      binary.readUInt32BE(12) !== 96 || binary.readUInt32BE(16) !== 16 || binary.readUInt32BE(20) !== 384) {
    throw new Error('Unsupported recognition binary layout');
  }
  const count = binary.readUInt32BE(24);
  if (count !== metadata.templateCount) throw new Error('Recognition template count mismatch');
  let offset = 64;
  const records = [];
  for (let index = 0; index < count; index++) {
    const fields = [];
    for (let field = 0; field < 9; field++) {
      const length = binary.readUInt16BE(offset);
      offset += 2;
      fields.push(binary.subarray(offset, offset + length).toString('utf8'));
      offset += length;
    }
    offset += 5 + 96 * 96 + 16 * 16 + 96 * 96 / 8 + 384 * 4 + 8;
    if (offset > binary.length) throw new Error('Truncated recognition features');
    records.push({canonicalId: fields[0], showdownId: fields[1], displayName: fields[2], source: fields[6]});
  }
  if (offset !== binary.length) throw new Error('Trailing recognition binary bytes');
  return records;
}

export function validateCatalogCoverage(input) {
  const {roster, items, generation, localization, presets, numericMap, icons, templates} = input;
  const moveCounts = validateMoveCoverage(input);
  const errors = [];
  const index = new Map(localization.map(row => [`${row.entityType}:${normalize(row.showdownId)}`, row]));
  const localized = (kind, name) => {
    const row = index.get(`${kind}:${normalize(name)}`);
    if (!row?.localizedNames?.['zh-Hans']?.some(text => /[\u4e00-\u9fff]/.test(text))) errors.push(`Missing Chinese ${kind}: ${name}`);
    return row;
  };
  for (const [kind, collection, rows] of [['ability', 'abilities', roster.abilities], ['item', 'items', items.entries]]) {
    for (const {number, showdownId} of rows) {
      localized(kind, showdownId);
      if (!generation[collection].get(normalize(showdownId))) errors.push(`Missing calculator ${kind}: ${showdownId}`);
      if (numericMap[collection][number] !== showdownId) errors.push(`Missing numeric ${kind}: ${number} ${showdownId}`);
    }
  }
  const abilityNames = new Map(roster.abilities.map(row => [row.number, row.showdownId]));
  const forms = new Map(presets.speciesForms.map(row => [normalize(row.species.showdownId), row]));
  for (const client of roster.forms) {
    const name = client.showdownId;
    const id = normalize(name);
    const form = forms.get(id);
    const species = generation.species.get(id);
    localized('species', name);
    if (numericMap.species[`${client.pokemonNumber}:${client.formNumber}`] !== name) errors.push(`Wrong numeric species: ${client.clientFormId}`);
    if (!form || !species) { errors.push(`Missing calculator/UI species: ${name}`); continue; }
    for (const [stat, expected] of Object.entries(client.baseStats)) {
      if (form.baseStats[stat] !== expected || species.baseStats[stat] !== expected) errors.push(`Wrong species stat: ${name} ${stat}`);
    }
    if (!same(form.types, client.types) || !same(species.types, client.types)) errors.push(`Wrong species typing: ${name}`);
    if (species.weightkg !== client.weightkg) errors.push(`Wrong species weight: ${name}`);
    const expectedAbilities = client.abilityNumbers.map(number => abilityNames.get(number));
    if (expectedAbilities.some(name => !name) || !same(sorted(form.abilities.map(row => row.showdownId)), sorted(expectedAbilities))) errors.push(`Wrong selectable abilities: ${name}`);
    if (form.defaultAbility?.showdownId !== expectedAbilities[0] || species.abilities?.[0] !== expectedAbilities[0]) errors.push(`Wrong default ability: ${name}`);
  }
  for (const group of presets.species) {
    const form = forms.get(normalize(group.species.showdownId));
    if (!form) { errors.push(`Missing preset form: ${group.species.showdownId}`); continue; }
    for (const profile of group.profiles) {
      if (profile.ability && !form.abilities.some(row => row.showdownId === profile.ability.showdownId)) errors.push(`Invalid preset ability: ${group.species.showdownId} ${profile.profileName}`);
      if (profile.item && !items.entries.some(row => row.showdownId === profile.item.showdownId)) errors.push(`Invalid preset item: ${profile.item.showdownId}`);
      if (profile.statAlignment && !numericMap.natures.includes(profile.statAlignment.showdownId)) errors.push(`Invalid preset nature: ${profile.statAlignment.showdownId}`);
    }
  }
  if (numericMap.natures.length !== 25 || new Set(numericMap.natures).size !== 25) errors.push('Incomplete numeric natures');
  for (const name of numericMap.natures) {
    localized('nature', name);
    const nature = generation.natures.get(normalize(name));
    const ui = presets.natures.find(row => row.nature.showdownId === name);
    if (!nature || !ui || ui.plus !== nature.plus || ui.minus !== nature.minus) errors.push(`Missing or incorrect nature: ${name}`);
  }
  const battleTypes = sorted(roster.forms.flatMap(row => row.types));
  for (const type of battleTypes) {
    localized('type', type);
    if (!generation.types.get(normalize(type))) errors.push(`Missing battle type: ${type}`);
  }
  const speciesEntries = localization.filter(row => row.entityType === 'species');
  const iconMap = new Map(icons.entries.map(row => [row.canonicalId, row]));
  const templateMap = new Map(templates.filter(row => row.source === 'CATALOG_REFERENCE').map(row => [row.canonicalId, row]));
  for (const row of speciesEntries) {
    const label = row.localizedNames?.['zh-Hans']?.[0] || '';
    if (!label || /[a-z]/i.test(label.replace(/[XYZ]/g, ''))) errors.push(`Incomplete Chinese species: ${row.showdownId}`);
    if (iconMap.get(row.canonicalId)?.showdownId !== row.showdownId) errors.push(`Missing/mismatched icon: ${row.showdownId}`);
    if (iconMap.get(row.canonicalId)?.displayName !== label) errors.push(`Stale icon label: ${row.showdownId}`);
    if (templateMap.get(row.canonicalId)?.showdownId !== row.showdownId) errors.push(`Missing/mismatched recognition template: ${row.showdownId}`);
    if (!forms.has(normalize(row.showdownId))) errors.push(`Missing selectable species: ${row.showdownId}`);
  }
  for (const template of templates) {
    const label = index.get(`species:${normalize(template.showdownId)}`)?.localizedNames?.['zh-Hans']?.[0];
    if (template.displayName !== label) errors.push(`Stale recognition label: ${template.showdownId}`);
  }
  // Aegislash-Both remains an explicitly virtual calculation option, never a
  // replacement for the client's Shield-form parameters or numeric identity.
  if (Object.values(numericMap.species).includes('Aegislash-Both')) errors.push('Client species maps to virtual Aegislash-Both');
  if (errors.length) throw new Error(`Champions catalog coverage failed (${errors.length}):\n${errors.join('\n')}`);
  return {...moveCounts, assignedAbilities: roster.abilities.length, items: items.entries.length, natures: numericMap.natures.length, battleTypes: battleTypes.length, iconSpecies: speciesEntries.length};
}

export function loadCatalogCoverageInputs() {
  const read = file => JSON.parse(fs.readFileSync(new URL(`../../${file}`, import.meta.url), 'utf8'));
  const metadata = read('src/data/recognition/android/team-preview-templates-v2.json');
  return {
    ...loadMoveCoverageInputs(),
    roster: read('src/data/damage/champions-roster.v18.json'),
    items: read('src/data/damage/champions-items.v18.json'),
    icons: read('src/data/pokemon-icons/catalog.pokeapi-composite.json'),
    templates: readTemplateCatalog(fs.readFileSync(new URL('../../src/data/recognition/android/team-preview-templates-v2.bin', import.meta.url)), metadata),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log('Verified all client catalogs:', validateCatalogCoverage(loadCatalogCoverageInputs()));
}
