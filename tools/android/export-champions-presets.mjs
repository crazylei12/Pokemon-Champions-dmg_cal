import {mkdir, readFile, writeFile} from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const sourcePath = path.join(
  repoRoot,
  'external',
  'smogon-damage-calc',
  'src',
  'js',
  'data',
  'sets',
  'champions.js'
);
const localizationPath = path.join(repoRoot, 'src', 'data', 'localization', 'zh-Hans.json');
const outputPath = path.join(repoRoot, 'src', 'data', 'damage', 'champions-presets.json');
const packagePath = path.join(repoRoot, 'package.json');
const require = createRequire(import.meta.url);
const {Generations, Pokemon} = require(path.join(repoRoot, 'external', 'smogon-damage-calc', 'calc', 'dist'));
const {Dex} = require('@pkmn/dex');
const championsMod = require('@pkmn/mods/champions');
const championsGeneration = Generations.get(0);
const learnsetDex = Dex.mod('champions', championsMod);

const [source, localizationSource, packageSource] = await Promise.all([
  readFile(sourcePath, 'utf8'),
  readFile(localizationPath, 'utf8'),
  readFile(packagePath, 'utf8'),
]);
const packageManifest = JSON.parse(packageSource);
const pkmnDexVersion = packageManifest.devDependencies?.['@pkmn/dex'];
const pkmnModsVersion = packageManifest.devDependencies?.['@pkmn/mods'];
if (
  !pkmnDexVersion ||
  pkmnDexVersion !== pkmnModsVersion ||
  !/^\d+\.\d+\.\d+$/.test(pkmnModsVersion)
) {
  throw new Error('@pkmn/dex and @pkmn/mods must be pinned to the same exact version');
}
const learnsetRulesetVersion = `pkmn-mods-champions-${pkmnModsVersion}`;
const learnsetDataDate = {
  '0.10.11': '2026-06-18',
}[pkmnModsVersion];
if (!learnsetDataDate) {
  throw new Error(`Missing Champions learnset data date for @pkmn/mods ${pkmnModsVersion}`);
}
const sandbox = {};
vm.runInNewContext(source, sandbox, {filename: sourcePath});
const setDex = sandbox.SETDEX_CHAMPIONS;
if (!setDex || typeof setDex !== 'object') {
  throw new Error('Unable to read SETDEX_CHAMPIONS');
}

const localization = JSON.parse(localizationSource);
const entityLookup = new Map();
for (const entry of localization) {
  entityLookup.set(`${entry.entityType}:${normalize(entry.showdownId)}`, entry);
  entityLookup.set(`${entry.entityType}:${normalize(entry.englishName || '')}`, entry);
}

function normalize(value) {
  return String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function entity(entityType, showdownId) {
  const known = entityLookup.get(`${entityType}:${normalize(showdownId)}`);
  return {
    entityType,
    canonicalId: known?.canonicalId || `${entityType}.${normalize(showdownId)}`,
    showdownId: known?.showdownId || showdownId,
    displayName: known?.localizedNames?.['zh-Hans']?.[0] || known?.englishName || showdownId,
    source: 'preset',
  };
}

function statPoints(input = {}) {
  const aliases = {hp: 'hp', at: 'atk', atk: 'atk', df: 'def', def: 'def', sa: 'spa', spa: 'spa', sd: 'spd', spd: 'spd', sp: 'spe', spe: 'spe'};
  return Object.fromEntries(
    Object.entries(input)
      .map(([key, value]) => [aliases[key], value])
      .filter(([key, value]) => key && Number.isFinite(value))
  );
}

function actualStats(speciesName, profile) {
  try {
    const pokemon = new Pokemon(0, speciesName, {
      level: 50,
      evs: statPoints(profile.sps || profile.evs),
      ...(profile.nature ? {nature: profile.nature} : {}),
      ...(profile.ability ? {ability: profile.ability} : {}),
      ...(profile.item ? {item: profile.item} : {}),
    });
    return pokemon.rawStats;
  } catch {
    return undefined;
  }
}

const formGroupsByFamily = new Map();
const speciesForms = [];
for (const entry of localization.filter(item => item.entityType === 'species')) {
  const speciesData = championsGeneration.species.get(normalize(entry.showdownId));
  if (!speciesData) continue;
  const dexSpecies = learnsetDex.species.get(speciesData.name);
  const familyName = speciesData.baseSpecies || speciesData.name;
  const familyId = normalize(familyName);
  const familyDexSpecies = learnsetDex.species.get(familyName);
  const resolvedDexSpecies = dexSpecies?.exists ? dexSpecies : familyDexSpecies?.exists ? familyDexSpecies : null;
  const learnsetCandidates = [
    resolvedDexSpecies?.name,
    resolvedDexSpecies?.baseSpecies,
    familyName,
  ].filter((value, index, values) => value && values.indexOf(value) === index);
  let learnset;
  for (const learnsetSpecies of learnsetCandidates) {
    const candidate = await learnsetDex.learnsets.get(learnsetSpecies);
    if (Object.keys(candidate?.learnset || {}).length > 0) {
      learnset = candidate;
      break;
    }
  }
  const learnableMoves = Object.keys(learnset?.learnset || {}).map(moveId => {
    const moveData = championsGeneration.moves.get(moveId);
    if (!moveData) return null;
    return {
      move: entity('move', moveData.name),
      source: 'CHAMPIONS_SNAPSHOT',
      basePower: moveData.basePower || 0,
      category: moveData.category || 'Status',
    };
  }).filter(Boolean).sort((left, right) => left.move.displayName.localeCompare(right.move.displayName, 'zh-Hans'));
  const abilityNames = [
    ...Object.values(speciesData.abilities || {}),
    ...(dexSpecies?.exists && dexSpecies.isNonstandard !== 'Future'
      ? Object.values(dexSpecies.abilities || {})
      : []),
  ].filter(Boolean);
  const abilities = [...new Map(abilityNames.map(name => [normalize(name), entity('ability', name)])).values()];
  const forms = formGroupsByFamily.get(familyId) || [];
  const form = {
    familyId,
    species: entity('species', speciesData.name),
    baseStats: speciesData.baseStats,
    abilities,
    learnableMoves,
    ...(speciesData.abilities?.['0'] ? {defaultAbility: entity('ability', speciesData.abilities['0'])} : {}),
  };
  forms.push(form);
  speciesForms.push(form);
  formGroupsByFamily.set(familyId, forms);
}

const formGroups = [...formGroupsByFamily.entries()]
  .filter(([, forms]) => forms.length > 1)
  .map(([familyId, forms]) => ({
    familyId,
    forms: forms.sort((left, right) => {
      const leftMega = /mega/i.test(left.species.showdownId) ? 1 : 0;
      const rightMega = /mega/i.test(right.species.showdownId) ? 1 : 0;
      return leftMega - rightMega || left.species.showdownId.localeCompare(right.species.showdownId);
    }),
  }))
  .sort((left, right) => left.familyId.localeCompare(right.familyId));

const species = Object.entries(setDex).map(([speciesName, profiles]) => {
  const speciesEntity = entity('species', speciesName);
  return {
    species: speciesEntity,
    profiles: Object.entries(profiles).map(([profileName, profile], index) => ({
      profileId: `smogon.${normalize(speciesEntity.showdownId)}.${index + 1}`,
      profileName,
      source: 'OPEN_SOURCE_PRESET',
      level: 50,
      statPoints: statPoints(profile.sps || profile.evs),
      ...(actualStats(speciesEntity.showdownId, profile)
        ? {actualStats: actualStats(speciesEntity.showdownId, profile)}
        : {}),
      ...(profile.nature ? {statAlignment: entity('nature', profile.nature)} : {}),
      ...(profile.ability ? {ability: entity('ability', profile.ability)} : {}),
      ...(profile.item ? {item: entity('item', profile.item)} : {}),
      moves: (profile.moves || []).map(moveName => {
        const moveData = championsGeneration.moves.get(normalize(moveName));
        return {
          move: entity('move', moveName),
          source: 'PROFILE_PRESET',
          basePower: moveData?.basePower || 0,
          category: moveData?.category || 'Status',
        };
      }),
      notes: ['Smogon Champions preset; this is a calculation assumption, not a confirmed opponent build.'],
    })),
  };
}).sort((left, right) => left.species.showdownId.localeCompare(right.species.showdownId));

const output = {
  schemaVersion: 6,
  source: 'external/smogon-damage-calc/src/js/data/sets/champions.js',
  learnsetSource: '@pkmn/mods/champions',
  learnsetVersion: pkmnModsVersion,
  learnsetRulesetVersion,
  learnsetPoolSource: 'CHAMPIONS_SNAPSHOT',
  learnsetDataDate,
  learnsetPolicy: 'Pokemon Showdown Champions learnset intersected with moves supported by the bundled Champions generation.',
  licenseAssets: [
    'licenses/smogon-damage-calc-MIT.txt',
    'licenses/pkmn-ps-MIT.txt',
  ],
  speciesCount: species.length,
  profileCount: species.reduce((count, entry) => count + entry.profiles.length, 0),
  formGroupCount: formGroups.length,
  speciesFormCount: speciesForms.length,
  moveTypes: Object.fromEntries(
    [...championsGeneration.moves]
      .map(move => [normalize(move.name), move.type || '???'])
      .sort(([left], [right]) => left.localeCompare(right))
  ),
  movePriorities: Object.fromEntries(
    [...championsGeneration.moves]
      .filter(move => move.priority)
      .map(move => [normalize(move.name), move.priority])
      .sort(([left], [right]) => left.localeCompare(right))
  ),
  natures: [...championsGeneration.natures].map(nature => ({
    nature: entity('nature', nature.name),
    plus: nature.plus,
    minus: nature.minus,
  })).sort((left, right) => left.nature.displayName.localeCompare(right.nature.displayName, 'zh-Hans')),
  speciesForms: speciesForms.sort((left, right) => left.species.showdownId.localeCompare(right.species.showdownId)),
  species,
};

await mkdir(path.dirname(outputPath), {recursive: true});
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, 'utf8');
console.log(`Wrote ${output.speciesCount} species and ${output.profileCount} profiles to ${outputPath}`);
