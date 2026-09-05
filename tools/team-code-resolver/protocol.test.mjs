import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  createApiRequestHash,
  decryptApiPayload,
  encryptApiPayload,
  mapOfficialTeam,
  normalizeCode,
} from "./protocol.mjs";
import { createEntityMapAsset, entityMapMetadata, resolveSpecies } from "./entity-map.mjs";

const directory = path.dirname(fileURLToPath(import.meta.url));

test("normalizes any syntactically valid public team code", () => {
  assert.equal(normalizeCode(" 61v6 v4s9rx\n"), "61V6V4S9RX");
  assert.equal(normalizeCode("A4RBRNN9YE"), "A4RBRNN9YE");
  assert.equal(normalizeCode("too-short"), undefined);
});

test("matches the reverse-engineered request hash vector", () => {
  const pmc = encryptApiPayload(
    '{"ok":true}',
    "1234567890123456789",
    "csrf",
    "session",
    77,
  );
  assert.equal(
    createApiRequestHash(pmc, "1234567890123456789", "csrf", "session", 0x1234),
    "1234cc65ecbbc6daaab55873dc928b27cd3a",
  );
  assert.deepEqual(
    decryptApiPayload(pmc, "1234567890123456789", "csrf", "session", 77),
    { text: '{"ok":true}', udVer: 77 },
  );
});

test("maps a second independently captured official team response", () => {
  const expected = JSON.parse(fs.readFileSync(
    path.join(directory, "fixtures", "61V6V4S9RX.json"),
    "utf8",
  ));
  assert.deepEqual(mapOfficialTeam("61V6V4S9RX", secondOfficialPayload()), expected);
});

test("maps the first verified public team response without code-specific branches", () => {
  const expected = JSON.parse(fs.readFileSync(
    path.join(directory, "fixtures", "A4RBRNN9YE.json"),
    "utf8",
  ));
  assert.deepEqual(mapOfficialTeam("A4RBRNN9YE", firstOfficialPayload()), expected);
});

test("covers every official master-data species form instead of sample-code exceptions", () => {
  assert.equal(entityMapMetadata.masterDataVersion, 17);
  assert.equal(entityMapMetadata.speciesForms, 361);
  assert.equal(resolveSpecies(3, 1), "Venusaur-Mega");
  assert.equal(resolveSpecies(479, 1), "Rotom-Heat");
  assert.equal(resolveSpecies(666, 18), "Vivillon-Fancy");
  assert.equal(resolveSpecies(678, 2), "Meowstic-M-Mega");
  assert.equal(resolveSpecies(1013, 1), "Sinistcha-Masterpiece");
  assert.deepEqual(
    JSON.parse(fs.readFileSync(path.join(directory, "data", "champions-entity-map.v17.json"), "utf8")),
    createEntityMapAsset(),
  );
});

function secondOfficialPayload() {
  return { tng: {
    unam: "ytess",
    mem: [
      { b0: 6, b1: 0, b2: 0, b5: 66, b8: 3, b9: 2, ba: 32, bb: 0, bc: 32, bd: 0, be: 0, bf: [394, 200, 488, 14] },
      { b0: 184, b1: 0, b2: 0, b5: 37, b8: 3, b9: 32, ba: 32, bb: 0, bc: 2, bd: 0, be: 0, bf: [583, 453, 276, 187] },
      { b0: 208, b1: 0, b2: 0, b5: 5, b8: 3, b9: 32, ba: 32, bb: 0, bc: 2, bd: 0, be: 0, bf: [484, 89, 776, 446] },
      { b0: 547, b1: 0, b2: 1, b5: 158, b8: 10, b9: 2, ba: 0, bb: 0, bc: 32, bd: 32, be: 0, bf: [585, 202, 73, 262] },
      { b0: 94, b1: 0, b2: 1, b5: 130, b8: 10, b9: 2, ba: 0, bb: 0, bc: 32, bd: 32, be: 0, bf: [247, 482, 196, 194] },
      { b0: 780, b1: 0, b2: 1, b5: 201, b8: 15, b9: 32, ba: 0, bb: 0, bc: 2, bd: 32, be: 0, bf: [434, 304, 53, 85] },
    ],
    itms: [
      { idx: 0, i: 760 }, { idx: 1, i: 158 }, { idx: 2, i: 234 },
      { idx: 3, i: 214 }, { idx: 4, i: 275 }, { idx: 5, i: 217 },
    ],
  } };
}

function firstOfficialPayload() {
  return { tng: {
    unam: "ワトソン",
    mem: [
      { b0: 149, b1: 0, b2: 1, b5: 136, b8: 15, b9: 2, ba: 0, bb: 0, bc: 32, bd: 32, be: 0, bf: [406, 257, 245, 182] },
      { b0: 903, b1: 0, b2: 0, b5: 143, b8: 13, b9: 2, ba: 32, bb: 0, bc: 32, bd: 0, be: 0, bf: [370, 827, 252, 364] },
      { b0: 902, b1: 0, b2: 0, b5: 91, b8: 3, b9: 4, ba: 18, bb: 4, bc: 25, bd: 0, be: 15, bf: [834, 854, 453, 182] },
      { b0: 983, b1: 0, b2: 0, b5: 128, b8: 3, b9: 32, ba: 15, bb: 0, bc: 0, bd: 0, be: 19, bf: [389, 869, 67, 442] },
      { b0: 445, b1: 0, b2: 1, b5: 24, b8: 3, b9: 10, ba: 20, bb: 9, bc: 27, bd: 0, be: 0, bf: [337, 707, 89, 157] },
      { b0: 670, b1: 5, b2: 1, b5: 166, b8: 10, b9: 4, ba: 0, bb: 8, bc: 22, bd: 32, be: 0, bf: [585, 605, 617, 182] },
    ],
    itms: [
      { idx: 0, i: 2562 }, { idx: 1, i: 275 }, { idx: 2, i: 270 },
      { idx: 3, i: 189 }, { idx: 4, i: 287 }, { idx: 5, i: 2579 },
    ],
  } };
}
