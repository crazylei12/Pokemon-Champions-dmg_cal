import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createEntityMapAsset, entityMapMetadata } from "./entity-map.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const outputPath = path.join(
  root,
  "tools",
  "team-code-resolver",
  "data",
  "champions-entity-map.v17.json",
);
const asset = createEntityMapAsset();

if (asset.masterDataVersion !== 17 || Object.keys(asset.species).length !== 361) {
  throw new Error(`Unexpected entity-map coverage: ${JSON.stringify(entityMapMetadata)}`);
}

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(asset)}\n`);
process.stdout.write(`Wrote Android team-code entity map to ${outputPath}\n`);
