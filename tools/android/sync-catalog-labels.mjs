import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {readTemplateCatalog} from './validate-champions-catalogs.mjs';

const file = name => new URL(`../../src/data/${name}`, import.meta.url);
const read = name => JSON.parse(fs.readFileSync(file(name), 'utf8'));
const write = (name, value) => fs.writeFileSync(file(name), `${JSON.stringify(value, null, 2)}\n`);
const labels = new Map(read('localization/zh-Hans.json').filter(row => row.entityType === 'species')
  .map(row => [row.canonicalId, row.localizedNames['zh-Hans'][0]]));
const icons = read('pokemon-icons/catalog.pokeapi-composite.json');
const metadata = read('recognition/android/team-preview-templates-v2.json');
const binary = fs.readFileSync(file('recognition/android/team-preview-templates-v2.bin'));
const records = readTemplateCatalog(binary, metadata);
const chunks = [binary.subarray(0, 64)];
let offset = 64;
let changed = 0;
for (const record of records) {
  const label = labels.get(record.canonicalId);
  if (!label) throw new Error(`Missing recognition label: ${record.canonicalId}`);
  for (let field = 0; field < 9; field++) {
    const start = offset;
    const length = binary.readUInt16BE(offset);
    offset += 2 + length;
    if (field === 2 && binary.subarray(start + 2, offset).toString('utf8') !== label) {
      // Labels contain BMP characters only, so UTF-8 equals Java modified UTF-8.
      if (/[\u0000\ud800-\udfff]/u.test(label)) throw new Error('Unsupported modified UTF-8 label');
      const text = Buffer.from(label, 'utf8');
      const size = Buffer.alloc(2);
      size.writeUInt16BE(text.length);
      chunks.push(size, text);
      changed++;
    } else chunks.push(binary.subarray(start, offset));
  }
  const size = 5 + 96 * 96 + 16 * 16 + 96 * 96 / 8 + 384 * 4 + 8;
  // Preserve every image feature and private labeled record payload byte for byte.
  chunks.push(binary.subarray(offset, offset + size));
  offset += size;
}
if (changed) {
  const output = Buffer.concat(chunks);
  metadata.binary.sha256 = createHash('sha256').update(output).digest('hex');
  if ('bytes' in metadata.binary) metadata.binary.bytes = output.length;
  readTemplateCatalog(output, metadata);
  fs.writeFileSync(file('recognition/android/team-preview-templates-v2.bin'), output);
  write('recognition/android/team-preview-templates-v2.json', metadata);
}
let iconsChanged = false;
for (const row of icons.entries) {
  const label = labels.get(row.canonicalId);
  if (!label) throw new Error(`Missing icon label: ${row.canonicalId}`);
  if (row.displayName !== label) { row.displayName = label; iconsChanged = true; }
}
if (iconsChanged) write('pokemon-icons/catalog.pokeapi-composite.json', icons);
console.log(`Synchronized ${changed} recognition labels; feature payloads preserved.`);
