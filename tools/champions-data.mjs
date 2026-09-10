import {createRequire} from 'node:module';
const require = createRequire(import.meta.url);
export const snapshot = require('../src/data/damage/champions-showdown-snapshot.json');
const packages = require('../package.json').devDependencies;
if (packages['@pkmn/dex'] !== snapshot.basePackages || packages['@pkmn/mods'] !== snapshot.basePackages) {
  throw new Error('Regenerate the Champions snapshot against the updated @pkmn package baseline.');
}
const {Dex} = require('@pkmn/dex');
const baseline = require('@pkmn/mods/champions');
const mod = {...baseline};
for (const [sourceKey, rows] of Object.entries(snapshot.mod)) {
  // @pkmn/dex calls Showdown's Pokedex table Species.
  const key = sourceKey === 'Pokedex' ? 'Species' : sourceKey;
  mod[key] = {...baseline[key], ...structuredClone(rows)};
}
// The client table includes held items still marked Past by the pinned upstream
// snapshot. Keep this verified source separate from the Showdown snapshot.
export const officialItems = require('../src/data/damage/champions-items.v18.json');
for (const {showdownId} of officialItems.entries) {
  const id = showdownId.toLowerCase().replace(/[^a-z0-9]/g, '');
  mod.Items[id] = {...mod.Items[id], inherit: true, isNonstandard: null};
}
// Verified against the 1.2.0 client's Master Data v18 waza_learn row 0865000.
// Keep this separately sourced addition out of the pinned Showdown snapshot.
mod.Learnsets.sirfetchd = structuredClone(mod.Learnsets.sirfetchd);
mod.Learnsets.sirfetchd.learnset.meteorassault = ["champions-master-data-v18"];
export const championsDex = Dex.mod('champions', mod);
