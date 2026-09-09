import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
// Protocol IDs belong to a captured master-data version. Updating calculator
// names must not silently add/remove IDs from the previously verified v18 map.
const verifiedMap = JSON.parse(fs.readFileSync(
  path.join(root, "tools", "team-code-resolver", "data", "champions-entity-map.v18.json"),
  "utf8",
));
const speciesForms = JSON.parse(fs.readFileSync(
  path.join(root, "tools", "team-code-resolver", "data", "champions-species-forms.v18.json"),
  "utf8",
));

if (verifiedMap.masterDataVersion !== speciesForms.masterDataVersion) {
  throw new Error("Champions species and numeric entity maps have different master-data versions");
}

const speciesByGameForm = new Map(
  speciesForms.entries.map((entry) => [
    `${Number(entry.pokemonNumber)}:${Number(entry.formNumber)}`,
    entry.speciesId,
  ]),
);
const numericMap = rows => new Map(Object.entries(rows).map(([number, name]) => [Number(number), name]));
const movesByNumber = numericMap(verifiedMap.moves);
const abilitiesByNumber = numericMap(verifiedMap.abilities);
const itemsByNumber = numericMap(verifiedMap.items);

const NATURES_BY_NUMBER = [
  "Hardy", "Lonely", "Brave", "Adamant", "Naughty",
  "Bold", "Docile", "Relaxed", "Impish", "Lax",
  "Timid", "Hasty", "Serious", "Jolly", "Naive",
  "Modest", "Mild", "Quiet", "Bashful", "Rash",
  "Calm", "Gentle", "Sassy", "Careful", "Quirky",
];

export const entityMapMetadata = Object.freeze({
  masterDataVersion: speciesForms.masterDataVersion,
  speciesForms: speciesByGameForm.size,
  moves: movesByNumber.size,
  abilities: abilitiesByNumber.size,
  items: itemsByNumber.size,
});

export function createEntityMapAsset() {
  return {
    schemaVersion: 1,
    masterDataVersion: speciesForms.masterDataVersion,
    species: sortedObject(speciesByGameForm),
    moves: sortedObject(movesByNumber),
    abilities: sortedObject(abilitiesByNumber),
    items: sortedObject(itemsByNumber),
    natures: [...NATURES_BY_NUMBER],
  };
}

export function resolveSpecies(pokemonNumber, formNumber) {
  return required(speciesByGameForm, `${Number(pokemonNumber)}:${Number(formNumber)}`, "species form");
}

export function resolveMove(number) {
  return required(movesByNumber, Number(number), "move");
}

export function resolveAbility(number) {
  return required(abilitiesByNumber, Number(number), "ability");
}

export function resolveItem(number) {
  return required(itemsByNumber, Number(number), "item");
}

export function resolveNature(number) {
  const nature = NATURES_BY_NUMBER[Number(number)];
  if (!nature) throw new Error(`Unknown Champions nature number: ${number}`);
  return nature;
}

function required(map, key, label) {
  const value = map.get(key);
  if (!value) throw new Error(`Unknown Champions ${label}: ${key}`);
  return value;
}

function sortedObject(map) {
  return Object.fromEntries(
    [...map.entries()].sort(([left], [right]) => String(left).localeCompare(String(right), "en", {
      numeric: true,
    })),
  );
}
