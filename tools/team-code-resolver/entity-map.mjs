import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Dex } from "@pkmn/dex";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const localization = JSON.parse(fs.readFileSync(
  path.join(root, "src", "data", "localization", "zh-Hans.json"),
  "utf8",
));
const speciesForms = JSON.parse(fs.readFileSync(
  path.join(root, "tools", "team-code-resolver", "data", "champions-species-forms.v17.json"),
  "utf8",
));

const allowedByType = new Map();
for (const entry of localization) {
  if (!allowedByType.has(entry.entityType)) allowedByType.set(entry.entityType, new Set());
  allowedByType.get(entry.entityType).add(normalizeId(entry.showdownId));
}

const speciesByGameForm = new Map(
  speciesForms.entries.map((entry) => [
    `${Number(entry.pokemonNumber)}:${Number(entry.formNumber)}`,
    entry.speciesId,
  ]),
);
const movesByNumber = buildNumberMap(Dex.moves, "move");
const abilitiesByNumber = buildNumberMap(Dex.abilities, "ability");
const itemsByNumber = buildNumberMap(Dex.items, "item");

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

function buildNumberMap(table, entityType) {
  const allowed = allowedByType.get(entityType) || new Set();
  const result = new Map();
  for (const entry of table.all()) {
    if (!entry.exists || entry.num <= 0 || !allowed.has(normalizeId(entry.name))) continue;
    if (!result.has(entry.num)) result.set(entry.num, entry.name);
  }
  return result;
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

function normalizeId(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9]+/g, "");
}
