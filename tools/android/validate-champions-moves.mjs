import fs from 'node:fs';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';

const require = createRequire(import.meta.url);
const normalize = name => name.toLowerCase().replace(/[^a-z0-9]/g, '');

export function validateMoveCoverage({official, generation, localization, presets, numericMap}) {
  const errors = [];
  const names = new Map(localization.filter(row => row.entityType === 'move').map(row => [normalize(row.showdownId), row]));
  const clientNames = new Map(official.entries.map(row => [row.number, row.showdownId]));
  const forms = new Map(presets.speciesForms.map(row => [normalize(row.species.showdownId), row]));
  for (const {number, showdownId} of official.entries) {
    const id = normalize(showdownId);
    const move = generation.moves.get(id);
    if (!move) errors.push(`Missing calculator move: ${showdownId}`);
    if (!names.get(id)?.localizedNames?.['zh-Hans']?.some(name => /[\u4e00-\u9fff]/.test(name))) errors.push(`Missing Chinese move: ${showdownId}`);
    if (numericMap.moves[number] !== showdownId) errors.push(`Missing numeric move: ${number} ${showdownId}`);
    const metadata = presets.moveMetadata[id];
    if (!metadata || metadata.basePower !== move?.basePower || metadata.category !== move?.category || presets.moveTypes[id] !== move?.type) {
      errors.push(`Missing or stale UI move metadata: ${showdownId}`);
    }
  }
  for (const client of official.forms) {
    const form = forms.get(normalize(client.showdownId));
    if (!form) { errors.push(`Missing selectable client form: ${client.clientFormId} ${client.showdownId}`); continue; }
    const expected = client.moveNumbers.map(number => {
      const name = clientNames.get(number);
      if (!name) errors.push(`Unavailable client move: ${client.clientFormId} ${number}`);
      return name && normalize(name);
    });
    const actual = form.learnableMoves.map(row => normalize(row.move.showdownId));
    for (const id of expected) if (!actual.includes(id)) errors.push(`Missing learnable move: ${client.showdownId} ${id}`);
    for (const id of actual) if (!expected.includes(id)) errors.push(`Unexpected learnable move: ${client.showdownId} ${id}`);
    if (new Set(actual).size !== actual.length) errors.push(`Duplicate learnable moves: ${client.showdownId}`);
    for (const row of form.learnableMoves) {
      const move = generation.moves.get(normalize(row.move.showdownId));
      const name = names.get(normalize(row.move.showdownId));
      if (row.basePower !== move?.basePower || row.category !== move?.category || row.move.displayName !== name?.localizedNames?.['zh-Hans']?.[0]) {
        errors.push(`Stale selectable move: ${client.showdownId} ${row.move.showdownId}`);
      }
    }
  }
  if (errors.length) throw new Error(`Champions move coverage failed (${errors.length}):\n${errors.join('\n')}`);
  return {availableMoves: official.entries.length, clientForms: official.forms.length};
}

export function loadMoveCoverageInputs() {
  const read = file => JSON.parse(fs.readFileSync(new URL(`../../${file}`, import.meta.url), 'utf8'));
  return {
    official: read('src/data/damage/champions-moves.v18.json'),
    generation: require('../../external/smogon-damage-calc/calc/dist').Generations.get(0),
    localization: read('src/data/localization/zh-Hans.json'),
    presets: read('src/data/damage/champions-presets.json'),
    numericMap: read('tools/team-code-resolver/data/champions-entity-map.v18.json'),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log('Verified complete client move coverage:', validateMoveCoverage(loadMoveCoverageInputs()));
}
