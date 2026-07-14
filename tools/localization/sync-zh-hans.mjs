import fs from 'node:fs';
import https from 'node:https';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const LOCALIZATION_DIR = path.join(ROOT, 'src', 'data', 'localization');
const SOURCE_DIR = path.join(LOCALIZATION_DIR, 'sources', '42arch-pokemon-dataset-zh');
const SMOGON_DATA_DIR = path.join(ROOT, 'external', 'smogon-damage-calc', 'calc', 'src', 'data');
const OVERRIDES_PATH = path.join(LOCALIZATION_DIR, 'zh-Hans.overrides.json');
const OUTPUT_PATH = path.join(LOCALIZATION_DIR, 'zh-Hans.json');
const COVERAGE_PATH = path.join(LOCALIZATION_DIR, 'coverage.zh-Hans.json');

const SOURCE_FILES = {
  'simple_pokedex.json': 'https://raw.githubusercontent.com/42arch/pokemon-dataset-zh/main/data/simple_pokedex.json',
  'move_list.json': 'https://raw.githubusercontent.com/42arch/pokemon-dataset-zh/main/data/move_list.json',
  'ability_list.json': 'https://raw.githubusercontent.com/42arch/pokemon-dataset-zh/main/data/ability_list.json',
  'item_list.json': 'https://raw.githubusercontent.com/42arch/pokemon-dataset-zh/main/data/item_list.json',
  'LICENSE': 'https://raw.githubusercontent.com/42arch/pokemon-dataset-zh/main/LICENSE',
  'README.md': 'https://raw.githubusercontent.com/42arch/pokemon-dataset-zh/main/README.md',
};

const ENTITY_ORDER = ['species', 'move', 'ability', 'item', 'nature', 'type'];

const NATURE_NAMES = {
  Adamant: '固执',
  Bashful: '害羞',
  Bold: '大胆',
  Brave: '勇敢',
  Calm: '温和',
  Careful: '慎重',
  Docile: '坦率',
  Gentle: '温顺',
  Hardy: '勤奋',
  Hasty: '急躁',
  Impish: '淘气',
  Jolly: '爽朗',
  Lax: '乐天',
  Lonely: '怕寂寞',
  Mild: '慢吞吞',
  Modest: '内敛',
  Naive: '天真',
  Naughty: '顽皮',
  Quiet: '冷静',
  Quirky: '浮躁',
  Rash: '马虎',
  Relaxed: '悠闲',
  Sassy: '自大',
  Serious: '认真',
  Timid: '胆小',
};

const TYPE_NAMES = {
  Normal: '一般',
  Fighting: '格斗',
  Flying: '飞行',
  Poison: '毒',
  Ground: '地面',
  Rock: '岩石',
  Bug: '虫',
  Ghost: '幽灵',
  Steel: '钢',
  Fire: '火',
  Water: '水',
  Grass: '草',
  Electric: '电',
  Psychic: '超能力',
  Ice: '冰',
  Dragon: '龙',
  Dark: '恶',
  Fairy: '妖精',
  Stellar: '星晶',
  '???': '无属性',
};

const FORM_SUFFIX_TEMPLATES = {
  Mega: ['超级{base}'],
  'Mega-X': ['超级{base}Ｘ', '超级{base}X'],
  'Mega-Y': ['超级{base}Ｙ', '超级{base}Y'],
  Alola: ['{base}-阿罗拉', '阿罗拉{base}'],
  Galar: ['{base}-伽勒尔', '伽勒尔{base}'],
  Hisui: ['{base}-洗翠', '洗翠{base}'],
  Paldea: ['{base}-帕底亚', '帕底亚{base}'],
  Blade: ['{base}-刀剑形态'],
  Shield: ['{base}-盾牌形态'],
  Both: ['{base}-双形态'],
  F: ['{base}-雌性'],
  M: ['{base}-雄性'],
  Rainy: ['{base}-雨水的样子'],
  Snowy: ['{base}-雪云的样子'],
  Sunny: ['{base}-太阳的样子'],
  Zen: ['{base}-达摩模式'],
  Origin: ['{base}-起源形态'],
  Therian: ['{base}-灵兽形态'],
  Incarnate: ['{base}-化身形态'],
  Altered: ['{base}-别种形态'],
  Hero: ['{base}-全能形态', '{base}-英雄形态'],
  School: ['{base}-鱼群的样子'],
  Noice: ['{base}-解冻头'],
  Small: ['{base}-小尺寸'],
  Large: ['{base}-大尺寸'],
  Super: ['{base}-特大尺寸'],
  PomPom: ['{base}-啪滋啪滋风格'],
  Pau: ['{base}-呼拉呼拉风格'],
  Sensu: ['{base}-轻盈轻盈风格'],
  Aqua: ['{base}-水澜种'],
  Blaze: ['{base}-火炽种'],
  Combat: ['{base}-斗战种'],
  White: ['{base}-白条纹'],
  Bloodmoon: ['{base}-赫月'],
  Eternal: ['{base}-永恒之花'],
};

const MEGA_STONE_BASE_OVERRIDES = {
  Abomasite: 'Abomasnow',
  Absolite: 'Absol',
  Aerodactylite: 'Aerodactyl',
  Aggronite: 'Aggron',
  Alakazite: 'Alakazam',
  Altarianite: 'Altaria',
  Ampharosite: 'Ampharos',
  Audinite: 'Audino',
  Banettite: 'Banette',
  Barbaracite: 'Barbaracle',
  Beedrillite: 'Beedrill',
  Blastoisinite: 'Blastoise',
  Blazikenite: 'Blaziken',
  Cameruptite: 'Camerupt',
  Chandelurite: 'Chandelure',
  Chesnaughtite: 'Chesnaught',
  Chimechite: 'Chimecho',
  Clefablite: 'Clefable',
  Crabominite: 'Crabominable',
  Delphoxite: 'Delphox',
  Dragalgite: 'Dragalge',
  Dragoninite: 'Dragonite',
  Drampanite: 'Drampa',
  Eelektrossite: 'Eelektross',
  Emboarite: 'Emboar',
  Excadrite: 'Excadrill',
  Falinksite: 'Falinks',
  Feraligite: 'Feraligatr',
  Floettite: 'Floette',
  Froslassite: 'Froslass',
  Galladite: 'Gallade',
  Garchompite: 'Garchomp',
  Gardevoirite: 'Gardevoir',
  Gengarite: 'Gengar',
  Glalitite: 'Glalie',
  Glimmoranite: 'Glimmora',
  Golurkite: 'Golurk',
  Greninjite: 'Greninja',
  Gyaradosite: 'Gyarados',
  Hawluchanite: 'Hawlucha',
  Heracronite: 'Heracross',
  Houndoominite: 'Houndoom',
  Kangaskhanite: 'Kangaskhan',
  Lopunnite: 'Lopunny',
  Lucarionite: 'Lucario',
  Malamarite: 'Malamar',
  Manectite: 'Manectric',
  Mawilite: 'Mawile',
  Medichamite: 'Medicham',
  Meganiumite: 'Meganium',
  Meowsticite: 'Meowstic',
  Metagrossite: 'Metagross',
  Pidgeotite: 'Pidgeot',
  Pinsirite: 'Pinsir',
  Pyroarite: 'Pyroar',
  'Raichunite X': 'Raichu',
  'Raichunite Y': 'Raichu',
  Sablenite: 'Sableye',
  Sceptilite: 'Sceptile',
  Scizorite: 'Scizor',
  Scolipite: 'Scolipede',
  Scovillainite: 'Scovillain',
  Scraftinite: 'Scrafty',
  Sharpedonite: 'Sharpedo',
  Skarmorite: 'Skarmory',
  Slowbronite: 'Slowbro',
  Staraptite: 'Staraptor',
  Starminite: 'Starmie',
  Steelixite: 'Steelix',
  Swampertite: 'Swampert',
  Tyranitarite: 'Tyranitar',
  Venusaurite: 'Venusaur',
  Victreebelite: 'Victreebel',
};

const args = new Set(process.argv.slice(2));

await main();

async function main() {
  fs.mkdirSync(SOURCE_DIR, {recursive: true});
  const refreshSources = args.has('--refresh-sources');
  await ensureSourceFiles(refreshSources);

  const champions = loadChampionsData();
  const sources = loadSourceMaps();
  const overrides = loadOverrides();
  const entries = [];
  const coverage = {
    generatedAt: new Date().toISOString(),
    smogonDataDir: path.relative(ROOT, SMOGON_DATA_DIR).replaceAll(path.sep, '/'),
    sourceRepository: 'https://github.com/42arch/pokemon-dataset-zh',
    sourceFiles: Object.keys(SOURCE_FILES),
    counts: {},
    missing: {},
    filledBy: {},
    ruleFallbacks: {},
  };

  for (const entityType of ENTITY_ORDER) {
    coverage.counts[entityType] = champions[entityType].length;
    coverage.missing[entityType] = [];
    coverage.filledBy[entityType] = {source: 0, override: 0, builtin: 0, rule: 0};
    coverage.ruleFallbacks[entityType] = [];

    for (const showdownId of champions[entityType]) {
      const resolved = resolveLocalizedNames(entityType, showdownId, sources, overrides);
      if (!resolved) {
        coverage.missing[entityType].push(showdownId);
        continue;
      }
      coverage.filledBy[entityType][resolved.source]++;
      if (resolved.fallback) coverage.ruleFallbacks[entityType].push(showdownId);
      entries.push(createEntry(entityType, showdownId, resolved));
    }
  }

  entries.sort((a, b) => {
    const typeDelta = ENTITY_ORDER.indexOf(a.entityType) - ENTITY_ORDER.indexOf(b.entityType);
    if (typeDelta) return typeDelta;
    return a.showdownId.localeCompare(b.showdownId, 'en');
  });

  writeJson(OUTPUT_PATH, entries);
  writeJson(COVERAGE_PATH, coverage);

  const totalMissing = Object.values(coverage.missing).reduce((sum, names) => sum + names.length, 0);
  console.log(`Generated ${path.relative(ROOT, OUTPUT_PATH)} with ${entries.length} entries.`);
  console.log(`Coverage report: ${path.relative(ROOT, COVERAGE_PATH)}.`);
  console.log(`Missing mappings after overrides: ${totalMissing}.`);
  if (totalMissing > 0) {
    for (const entityType of ENTITY_ORDER) {
      const missing = coverage.missing[entityType];
      if (missing.length) console.log(`  ${entityType}: ${missing.length}`);
    }
    process.exitCode = 1;
  }
}

async function ensureSourceFiles(refreshSources) {
  for (const [fileName, url] of Object.entries(SOURCE_FILES)) {
    const outputPath = path.join(SOURCE_DIR, fileName);
    if (!refreshSources && fs.existsSync(outputPath)) continue;
    const content = await fetchTextWithRetry(url);
    fs.writeFileSync(outputPath, content, 'utf8');
  }
}

async function fetchTextWithRetry(url) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      return await fetchText(url);
    } catch (error) {
      lastError = error;
      if (attempt < 3) await new Promise(resolve => setTimeout(resolve, attempt * 1000));
    }
  }
  throw lastError;
}

function fetchText(url) {
  return new Promise((resolve, reject) => {
    https.get(url, {headers: {'User-Agent': 'pokemon-champion-localization-sync'}}, response => {
      if (response.statusCode !== 200) {
        reject(new Error(`Failed to fetch ${url}: HTTP ${response.statusCode}`));
        response.resume();
        return;
      }
      response.setEncoding('utf8');
      let data = '';
      response.on('data', chunk => {
        data += chunk;
      });
      response.on('end', () => resolve(data));
    }).on('error', reject);
  });
}

function loadChampionsData() {
  return {
    species: extractStringArray(path.join(SMOGON_DATA_DIR, 'species.ts'), 'CHAMPIONS_LIST'),
    move: extractStringArray(path.join(SMOGON_DATA_DIR, 'moves.ts'), 'CHAMPIONS_LIST'),
    ability: extractStringArray(path.join(SMOGON_DATA_DIR, 'abilities.ts'), 'CHAMPIONS'),
    item: extractStringArray(path.join(SMOGON_DATA_DIR, 'items.ts'), 'CHAMPIONS'),
    nature: extractNatureNames(path.join(SMOGON_DATA_DIR, 'natures.ts')),
    type: extractTypeNames(path.join(SMOGON_DATA_DIR, 'interface.ts')),
  };
}

function extractStringArray(filePath, constName) {
  const text = fs.readFileSync(filePath, 'utf8');
  const marker = `const ${constName}`;
  const markerIndex = text.indexOf(marker);
  if (markerIndex < 0) throw new Error(`Cannot find ${marker} in ${filePath}`);
  const arrayStart = text.indexOf('[', markerIndex);
  const arrayEnd = findMatchingBracket(text, arrayStart, '[', ']');
  const arrayBody = text.slice(arrayStart + 1, arrayEnd);
  const names = [];
  const stringPattern = /'((?:\\'|[^'])*)'/g;
  let match;
  while ((match = stringPattern.exec(arrayBody))) {
    names.push(match[1].replace(/\\'/g, "'"));
  }
  return [...new Set(names)].sort((a, b) => a.localeCompare(b, 'en'));
}

function extractNatureNames(filePath) {
  const text = fs.readFileSync(filePath, 'utf8');
  const marker = 'export const NATURES';
  const markerIndex = text.indexOf(marker);
  if (markerIndex < 0) throw new Error(`Cannot find ${marker} in ${filePath}`);
  const assignmentIndex = text.indexOf('=', markerIndex);
  const objectStart = text.indexOf('{', assignmentIndex);
  const objectEnd = findMatchingBracket(text, objectStart, '{', '}');
  const body = text.slice(objectStart + 1, objectEnd);
  const names = [];
  const keyPattern = /^\s*([A-Za-z]+):/gm;
  let match;
  while ((match = keyPattern.exec(body))) names.push(match[1]);
  return names.sort((a, b) => a.localeCompare(b, 'en'));
}

function extractTypeNames(filePath) {
  const text = fs.readFileSync(filePath, 'utf8');
  const marker = 'export type TypeName';
  const markerIndex = text.indexOf(marker);
  if (markerIndex < 0) throw new Error(`Cannot find ${marker} in ${filePath}`);
  const end = text.indexOf(';', markerIndex);
  const body = text.slice(markerIndex, end);
  return [...body.matchAll(/'([^']+)'/g)].map(match => match[1]).sort((a, b) => a.localeCompare(b, 'en'));
}

function findMatchingBracket(text, start, open, close) {
  let depth = 0;
  let quote = '';
  let escape = false;
  for (let index = start; index < text.length; index++) {
    const char = text[index];
    if (quote) {
      if (escape) {
        escape = false;
      } else if (char === '\\') {
        escape = true;
      } else if (char === quote) {
        quote = '';
      }
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }
    if (char === open) depth++;
    if (char === close) {
      depth--;
      if (depth === 0) return index;
    }
  }
  throw new Error(`Cannot find matching ${close}`);
}

function loadSourceMaps() {
  const sourceMaps = {
    species: new Map(),
    move: new Map(),
    ability: new Map(),
    item: new Map(),
  };

  const pokedex = readSourceJson('simple_pokedex.json');
  for (const row of pokedex) addSourceName(sourceMaps.species, row.name_en, row.name_zh);

  const moves = readSourceJson('move_list.json');
  for (const row of moves) addSourceName(sourceMaps.move, row.name_en, row.name_zh);

  const abilities = readSourceJson('ability_list.json');
  for (const row of abilities) addSourceName(sourceMaps.ability, row.name_en, row.name_zh);

  const items = readSourceJson('item_list.json');
  for (const row of flattenItemTree(items)) addSourceName(sourceMaps.item, row.name_en, row.name_zh);

  return sourceMaps;
}

function readSourceJson(fileName) {
  return JSON.parse(fs.readFileSync(path.join(SOURCE_DIR, fileName), 'utf8'));
}

function addSourceName(map, englishName, zhName) {
  if (!englishName || !zhName) return;
  const id = toID(englishName);
  const existing = map.get(id);
  if (existing) {
    existing.zhNames = unique([...existing.zhNames, zhName]);
  } else {
    map.set(id, {englishName, zhNames: [zhName]});
  }
}

function flattenItemTree(nodes) {
  const result = [];
  const visit = node => {
    if (node.name_en || node.name_zh) result.push(node);
    if (Array.isArray(node.children)) node.children.forEach(visit);
  };
  nodes.forEach(visit);
  return result;
}

function loadOverrides() {
  if (!fs.existsSync(OVERRIDES_PATH)) return new Map();
  const json = JSON.parse(fs.readFileSync(OVERRIDES_PATH, 'utf8'));
  const overrides = new Map();
  for (const entry of json.entries || []) {
    const key = `${entry.entityType}:${toID(entry.showdownId)}`;
    overrides.set(key, {
      zhNames: entry.zhHans || [],
      aliases: entry.aliases || [],
      note: entry.note,
    });
  }
  return overrides;
}

function resolveLocalizedNames(entityType, showdownId, sources, overrides) {
  const override = overrides.get(`${entityType}:${toID(showdownId)}`);
  if (override) return {...override, source: 'override'};

  if (entityType === 'nature') {
    const zhName = NATURE_NAMES[showdownId];
    return zhName ? {zhNames: [zhName], aliases: [], source: 'builtin'} : undefined;
  }

  if (entityType === 'type') {
    const zhName = TYPE_NAMES[showdownId];
    return zhName ? {zhNames: [zhName], aliases: [], source: 'builtin'} : undefined;
  }

  const sourceMap = sources[entityType];
  const sourceHit = sourceMap?.get(toID(showdownId));
  if (sourceHit) return {zhNames: sourceHit.zhNames, aliases: [], source: 'source'};

  if (entityType === 'species') return resolveSpeciesForm(showdownId, sources.species);
  if (entityType === 'item') return resolveMegaStone(showdownId, sources.species);

  return undefined;
}

function resolveSpeciesForm(showdownId, speciesMap) {
  const parts = showdownId.split('-');
  if (parts.length < 2) return undefined;

  for (let length = parts.length - 1; length >= 1; length--) {
    const baseEnglish = parts.slice(0, length).join('-');
    const base = speciesMap.get(toID(baseEnglish));
    if (!base) continue;

    const suffix = parts.slice(length).join('-');
    const exact = FORM_SUFFIX_TEMPLATES[suffix];
    const zhNames = exact
      ? exact.flatMap(template => base.zhNames.map(name => template.replace('{base}', name)))
      : createFallbackFormNames(base.zhNames, suffix);

    return {
      zhNames: unique(zhNames),
      aliases: [],
      source: 'rule',
      fallback: !exact,
    };
  }

  return undefined;
}

function createFallbackFormNames(baseZhNames, suffix) {
  const suffixZh = suffix
    .split('-')
    .map(part => FORM_SUFFIX_TEMPLATES[part]?.[0]?.replace('{base}-', '').replace('{base}', '') || part)
    .join('-');
  return baseZhNames.map(baseName => `${baseName}-${suffixZh}`);
}

function resolveMegaStone(showdownId, speciesMap) {
  const baseEnglish = MEGA_STONE_BASE_OVERRIDES[showdownId];
  if (!baseEnglish) return undefined;
  const base = speciesMap.get(toID(baseEnglish));
  if (!base) return undefined;
  const suffix = showdownId.endsWith(' X') ? 'Ｘ' : showdownId.endsWith(' Y') ? 'Ｙ' : '';
  return {
    zhNames: unique([
      ...base.zhNames.map(name => `${name}进化石${suffix}`),
      ...(suffix === 'Ｘ' ? base.zhNames.map(name => `${name}进化石X`) : []),
      ...(suffix === 'Ｙ' ? base.zhNames.map(name => `${name}进化石Y`) : []),
    ]),
    aliases: [],
    source: 'rule',
  };
}

function createEntry(entityType, showdownId, resolved) {
  return {
    entityType,
    canonicalId: `${entityType}.${toID(showdownId)}`,
    showdownId,
    englishName: showdownId,
    localizedNames: {
      'zh-Hans': unique(resolved.zhNames),
      en: [showdownId],
    },
    aliases: unique([...(resolved.aliases || []), showdownId]),
  };
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), {recursive: true});
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function unique(values) {
  return [...new Set(values.filter(Boolean))];
}

function toID(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9]+/g, '');
}
