import fs from 'node:fs';
import https from 'node:https';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ICON_DIR = path.join(ROOT, 'src', 'data', 'pokemon-icons');
const LOCALIZATION_PATH = path.join(ROOT, 'src', 'data', 'localization', 'zh-Hans.json');
const MANIFEST_PATH = path.join(ICON_DIR, 'source.manifest.json');
const OVERRIDES_PATH = path.join(ICON_DIR, 'showdown-pokeapi-overrides.json');
const CATALOG_PATH = path.join(ICON_DIR, 'catalog.pokeapi-composite.json');
const COVERAGE_PATH = path.join(ICON_DIR, 'coverage.pokeapi-composite.json');
const ASSET_DIR = path.join(ICON_DIR, 'assets');

const DOWNLOAD = process.argv.includes('--download');
const FORCE = process.argv.includes('--force');
const LIMIT_ARG = process.argv.find(arg => arg.startsWith('--limit='));
const DOWNLOAD_LIMIT = LIMIT_ARG ? Number(LIMIT_ARG.slice('--limit='.length)) : Infinity;

const SOURCE_ID = 'pokeapi-composite';
const BULBAGARDEN_CHAMPIONS_SHINY_SOURCE_ID = 'bulbagarden-champions-shiny-menu-sprites';

const WIKI52POKE_CHAMPIONS_FORM_SUFFIX_OVERRIDES = {
  'Aegislash-Blade': 'B',
  'Arcanine-Hisui': 'H',
  'Avalugg-Hisui': 'H',
  'Basculegion-F': 'F',
  'Castform-Rainy': 'R',
  'Castform-Snowy': 'S',
  'Castform-Sunny': 'H',
  'Decidueye-Hisui': 'H',
  'Floette-Eternal': 'E',
  'Goodra-Hisui': 'H',
  'Gourgeist-Large': 'L',
  'Gourgeist-Small': 'S',
  'Gourgeist-Super': 'J',
  'Lycanroc-Dusk': 'D',
  'Lycanroc-Midnight': 'Mn',
  'Maushold': 'T',
  'Meowstic-F': 'F',
  'Morpeko-Hangry': 'H',
  'Ninetales-Alola': 'A',
  'Palafin-Hero': 'H',
  'Raichu-Alola': 'A',
  'Rotom-Fan': 'Fa',
  'Rotom-Frost': 'F',
  'Rotom-Heat': 'H',
  'Rotom-Mow': 'M',
  'Rotom-Wash': 'W',
  'Samurott-Hisui': 'H',
  'Slowbro-Galar': 'G',
  'Slowking-Galar': 'G',
  'Stunfisk-Galar': 'G',
  'Tauros-Paldea-Aqua': 'PA',
  'Tauros-Paldea-Blaze': 'PB',
  'Tauros-Paldea-Combat': 'PC',
  'Typhlosion-Hisui': 'H',
  'Vivillon-Fancy': 'Fan',
  'Vivillon-Pokeball': 'Pok',
  'Zoroark-Hisui': 'H',
};

const BULBAGARDEN_FORM_SUFFIX_OVERRIDES = {
  'Aegislash-Both': '',
  'Aegislash-Shield': '',
  'Aegislash-Blade': '-Blade',
  'Basculegion-F': '-Female',
  'Indeedee-F': '-Female',
  'Meowstic-F': '-Female',
  'Oinkologne-F': '-Female',
  'Pyroar-F': '-Female',
  'Raichu-Mega-X': '-Mega X',
  'Raichu-Mega-Y': '-Mega Y',
  'Vivillon-Pokeball': '-Poke Ball',
};

await main();

async function main() {
  const manifest = readJson(MANIFEST_PATH);
  const overrides = readJson(OVERRIDES_PATH);
  const localizedEntries = readJson(LOCALIZATION_PATH);
  const speciesEntries = localizedEntries.filter(entry => entry.entityType === 'species');

  const pokemonCsv = await fetchText(manifest.metadataSources.pokemonCsv);
  const spritesTree = await fetchJson(manifest.metadataSources.spritesGitTree);

  const normalSources = manifest.sources || [];
  const shinySources = manifest.shinySources || [];
  const pokemonByIdentifier = parsePokemonCsv(pokemonCsv);
  const availableBySource = indexAvailableSprites(spritesTree.tree, normalSources);
  const availableByShinySource = indexAvailableSprites(spritesTree.tree, shinySources);
  const wikiChampionsByFileName = await fetchWikiChampionsImageInfos(
    speciesEntries,
    pokemonByIdentifier,
    overrides,
    manifest.metadataSources.wiki52pokeImageInfoApi
  );
  const bulbagardenChampionsShinyByFileName = await fetchBulbagardenChampionsShinyImageInfos(
    speciesEntries,
    pokemonByIdentifier,
    overrides,
    manifest.metadataSources.bulbagardenImageInfoApi
  );

  const catalogEntries = [];
  const coverage = {
    generatedAt: new Date().toISOString(),
    sourceId: SOURCE_ID,
    sourcePriority: normalSources.map(source => source.sourceId),
    shinySourcePriority: shinySources.map(source => source.sourceId),
    counts: {
      totalSpecies: speciesEntries.length,
      remoteAvailable: 0,
      primaryBoxSprite: 0,
      fallbackSprite: 0,
      fallbackArtwork: 0,
      shinyRemoteAvailable: 0,
      shinyTemplateMissing: 0,
      userTemplateRequired: 0,
      unmapped: 0,
    },
    bySource: Object.fromEntries(normalSources.map(source => [source.sourceId, 0])),
    byShinySource: Object.fromEntries(shinySources.map(source => [source.sourceId, 0])),
    userTemplateRequired: [],
    unmapped: [],
    shinyMissing: [],
    fallbackUsed: [],
  };

  for (const entry of speciesEntries) {
    const mapped = mapShowdownToPokeApi(entry, pokemonByIdentifier, overrides);
    if (!mapped) {
      const catalogEntry = createMissingEntry(entry, 'unmapped', undefined);
      catalogEntries.push(catalogEntry);
      coverage.counts.unmapped++;
      coverage.unmapped.push(toCoverageItem(catalogEntry));
      continue;
    }

    const shinySelected = selectBestShinySource(
      entry,
      mapped,
      shinySources,
      availableByShinySource,
      bulbagardenChampionsShinyByFileName
    );
    const shinyVariants = shinySelected ? [createIconVariant('shiny', shinySelected)] : [];
    if (shinySelected) {
      coverage.counts.shinyRemoteAvailable++;
      coverage.byShinySource[shinySelected.source.sourceId]++;
    } else {
      coverage.counts.shinyTemplateMissing++;
      coverage.shinyMissing.push({
        showdownId: entry.showdownId,
        displayName: entry.localizedNames?.['zh-Hans']?.[0] || entry.englishName || entry.showdownId,
        pokeapiIdentifier: mapped.pokeapiIdentifier,
        pokemonId: mapped.pokemonId,
      });
    }

    const selected = selectBestSource(entry, mapped, normalSources, availableBySource, wikiChampionsByFileName);
    if (!selected) {
      const catalogEntry = createMissingEntry(entry, 'user_template_required', mapped);
      if (shinyVariants.length) catalogEntry.iconVariants = shinyVariants;
      catalogEntries.push(catalogEntry);
      coverage.counts.userTemplateRequired++;
      coverage.userTemplateRequired.push(toCoverageItem(catalogEntry));
      continue;
    }

    const catalogEntry = createRemoteEntry(entry, mapped, selected, shinyVariants);
    catalogEntries.push(catalogEntry);
    coverage.counts.remoteAvailable++;
    coverage.bySource[selected.source.sourceId]++;
    if (selected.source.role === 'champions_sprite') {
      coverage.counts.primaryBoxSprite++;
    } else if (selected.source.role === 'primary_box_sprite') {
      coverage.counts.primaryBoxSprite++;
    } else if (selected.source.role === 'fallback_sprite') {
      coverage.counts.fallbackSprite++;
      coverage.fallbackUsed.push(toCoverageItem(catalogEntry));
    } else if (selected.source.role === 'fallback_artwork') {
      coverage.counts.fallbackArtwork++;
      coverage.fallbackUsed.push(toCoverageItem(catalogEntry));
    }
  }

  const catalog = {
    generatedAt: coverage.generatedAt,
    sourceId: SOURCE_ID,
    selectedSource: manifest.selectedSource,
    summary: manifest.summary,
    licenseNotes: manifest.licenseNotes,
    entries: catalogEntries,
  };

  writeJson(CATALOG_PATH, catalog);
  writeJson(COVERAGE_PATH, coverage);

  if (DOWNLOAD) {
    await downloadAssets(catalogEntries);
  }

  printSummary(coverage);
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), {recursive: true});
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`);
}

function parsePokemonCsv(csvText) {
  const rows = csvText.trim().split(/\r?\n/);
  const byIdentifier = new Map();
  for (const row of rows.slice(1)) {
    if (!row.trim()) continue;
    const [id, identifier, speciesId, height, weight, baseExperience, order, isDefault] = row.split(',');
    byIdentifier.set(identifier, {
      pokemonId: id,
      identifier,
      speciesId,
      height: Number(height),
      weight: Number(weight),
      baseExperience: Number(baseExperience),
      order: order ? Number(order) : undefined,
      isDefault: isDefault === '1',
    });
  }
  return byIdentifier;
}

function indexAvailableSprites(tree, sources) {
  const bySource = new Map(sources.map(source => [source.sourceId, new Map()]));
  for (const item of tree) {
    if (item.type !== 'blob' || !item.path.endsWith('.png')) continue;
    for (const source of sources) {
      const prefix = `${source.pathPrefix}/`;
      if (!item.path.startsWith(prefix)) continue;
      const fileName = item.path.slice(prefix.length);
      const match = fileName.match(/^(\d+)\.png$/);
      if (!match) continue;
      bySource.get(source.sourceId).set(match[1], item.path);
    }
  }
  return bySource;
}

function mapShowdownToPokeApi(entry, pokemonByIdentifier, overrides) {
  const override = overrides.identifierOverrides?.[entry.showdownId];
  const identifier = override?.pokeapiIdentifier || toPokeApiIdentifier(entry.showdownId);
  const pokemon = pokemonByIdentifier.get(identifier);
  if (!pokemon) return undefined;
  return {
    pokemonId: pokemon.pokemonId,
    speciesId: pokemon.speciesId,
    pokeapiIdentifier: identifier,
    mappingSource: override ? 'override' : 'direct',
    mappingReason: override?.reason,
  };
}

async function fetchWikiChampionsImageInfos(speciesEntries, pokemonByIdentifier, overrides, apiPrefix) {
  const fileNames = new Set();
  for (const entry of speciesEntries) {
    const mapped = mapShowdownToPokeApi(entry, pokemonByIdentifier, overrides);
    if (!mapped) continue;
    const fileName = getChampionsSpriteFileName(entry.showdownId, mapped.speciesId);
    if (fileName) fileNames.add(fileName);
  }

  const byFileName = new Map();
  const files = [...fileNames].sort();
  const batchSize = 40;
  for (let index = 0; index < files.length; index += batchSize) {
    const batch = files.slice(index, index + batchSize);
    const titles = batch.map(fileName => `File:${fileName}`).join('|');
    const result = await fetchJson(`${apiPrefix}${encodeURIComponent(titles)}`);
    for (const page of Object.values(result.query?.pages || {})) {
      const imageInfo = page.imageinfo?.[0];
      if (!imageInfo?.url) continue;
      const fileName = titleToFileName(page.title);
      byFileName.set(fileName, {
        url: imageInfo.url,
        width: imageInfo.width,
        height: imageInfo.height,
        size: imageInfo.size,
        pageTitle: page.title,
      });
    }
  }
  return byFileName;
}

async function fetchBulbagardenChampionsShinyImageInfos(speciesEntries, pokemonByIdentifier, overrides, apiPrefix) {
  if (!apiPrefix) return new Map();

  const fileNames = new Set();
  for (const entry of speciesEntries) {
    const mapped = mapShowdownToPokeApi(entry, pokemonByIdentifier, overrides);
    if (!mapped) continue;
    for (const fileName of getBulbagardenChampionsShinyFileNameCandidates(entry.showdownId, mapped.speciesId)) {
      fileNames.add(fileName);
    }
  }

  const byFileName = new Map();
  const files = [...fileNames].sort();
  const batchSize = 40;
  for (let index = 0; index < files.length; index += batchSize) {
    const batch = files.slice(index, index + batchSize);
    const titles = batch.map(fileName => `File:${fileName}`).join('|');
    const result = await fetchJson(`${apiPrefix}${encodeURIComponent(titles)}`);
    for (const page of Object.values(result.query?.pages || {})) {
      const imageInfo = page.imageinfo?.[0];
      if (!imageInfo?.url) continue;
      const fileName = titleToFileName(page.title);
      byFileName.set(fileName, {
        url: imageInfo.url,
        width: imageInfo.width,
        height: imageInfo.height,
        size: imageInfo.size,
        pageTitle: page.title,
      });
    }
  }
  return byFileName;
}

function selectBestSource(entry, mapped, sources, availableBySource, wikiChampionsByFileName) {
  for (const source of sources) {
    if (source.sourceId === 'wiki52poke-champions-sprites') {
      const fileName = getChampionsSpriteFileName(entry.showdownId, mapped.speciesId);
      const imageInfo = fileName ? wikiChampionsByFileName.get(fileName) : undefined;
      if (!imageInfo) continue;
      return {
        source,
        remotePath: `File:${fileName}`,
        remoteUrl: imageInfo.url,
        localPath: `src/data/pokemon-icons/assets/${source.sourceId}/${fileName}`,
        width: imageInfo.width,
        height: imageInfo.height,
      };
    }

    const selected = selectSpriteSource(source, mapped, availableBySource);
    if (selected) return selected;
  }
  return undefined;
}

function selectBestShinySource(
  entry,
  mapped,
  sources,
  availableBySource,
  bulbagardenChampionsShinyByFileName
) {
  for (const source of sources) {
    if (source.sourceId === BULBAGARDEN_CHAMPIONS_SHINY_SOURCE_ID) {
      for (const fileName of getBulbagardenChampionsShinyFileNameCandidates(entry.showdownId, mapped.speciesId)) {
        const imageInfo = bulbagardenChampionsShinyByFileName.get(fileName);
        if (!imageInfo) continue;
        return {
          source,
          remotePath: `File:${fileName}`,
          remoteUrl: imageInfo.url,
          localPath: `src/data/pokemon-icons/assets/${source.sourceId}/${fileName}`,
          width: imageInfo.width,
          height: imageInfo.height,
        };
      }
      continue;
    }

    const selected = selectSpriteSource(source, mapped, availableBySource);
    if (selected) return selected;
  }
  return undefined;
}

function selectBestSpriteSource(mapped, sources, availableBySource) {
  for (const source of sources) {
    const selected = selectSpriteSource(source, mapped, availableBySource);
    if (selected) return selected;
  }
  return undefined;
}

function selectSpriteSource(source, mapped, availableBySource) {
  const remotePath = availableBySource.get(source.sourceId)?.get(mapped.pokemonId);
  if (!remotePath) return undefined;
  return {
    source,
    remotePath,
    remoteUrl: `${source.rawBaseUrl}/${remotePath}`,
    localPath: `src/data/pokemon-icons/assets/${source.sourceId}/${mapped.pokemonId}.png`,
  };
}

function getChampionsSpriteFileName(showdownId, speciesId) {
  if (!speciesId) return undefined;
  const suffix = getChampionsFormSuffix(showdownId);
  return `Champions_${String(speciesId).padStart(4, '0')}${suffix}_Sprite.png`;
}

function getBulbagardenChampionsShinyFileNameCandidates(showdownId, speciesId) {
  if (!speciesId) return [];
  const baseName = `Menu_CP_${String(speciesId).padStart(4, '0')}`;
  return getBulbagardenChampionsFormSuffixCandidates(showdownId).map(
    suffix => `${baseName}${suffix}_shiny.png`
  );
}

function getBulbagardenChampionsFormSuffixCandidates(showdownId) {
  const suffixes = [];
  if (Object.hasOwn(BULBAGARDEN_FORM_SUFFIX_OVERRIDES, showdownId)) {
    suffixes.push(BULBAGARDEN_FORM_SUFFIX_OVERRIDES[showdownId]);
  }

  const formParts = showdownId.split('-').slice(1);
  if (formParts.length) {
    if (formParts[0] === 'Mega') {
      suffixes.push(['-Mega', ...formParts.slice(1)].join(' '));
    } else {
      const readableForm = formParts.map(toBulbagardenFormPart).join(' ');
      suffixes.push(`-${readableForm}`);
      suffixes.push(`-${formParts.join('-')}`);
    }
  }

  suffixes.push('');
  return [...new Set(suffixes)];
}

function toBulbagardenFormPart(part) {
  if (part === 'F') return 'Female';
  if (part === 'M') return 'Male';
  return part;
}

function getChampionsFormSuffix(showdownId) {
  if (Object.hasOwn(WIKI52POKE_CHAMPIONS_FORM_SUFFIX_OVERRIDES, showdownId)) {
    return WIKI52POKE_CHAMPIONS_FORM_SUFFIX_OVERRIDES[showdownId];
  }
  if (/-Mega-X$/i.test(showdownId)) return 'MX';
  if (/-Mega-Y$/i.test(showdownId)) return 'MY';
  if (/-Mega$/i.test(showdownId)) return 'M';
  return '';
}

function titleToFileName(title) {
  return title.replace(/^File:/, '').replace(/ /g, '_');
}

function createRemoteEntry(entry, mapped, selected, iconVariants = []) {
  const catalogEntry = {
    canonicalId: entry.canonicalId,
    showdownId: entry.showdownId,
    displayName: entry.localizedNames?.['zh-Hans']?.[0] || entry.englishName || entry.showdownId,
    englishName: entry.englishName,
    pokemonApi: mapped,
    icon: {
      status: 'remote_available',
      sourceId: selected.source.sourceId,
      role: selected.source.role,
      matchSuitability: selected.source.matchSuitability,
      nominalWidth: selected.width || selected.source.nominalWidth,
      nominalHeight: selected.height || selected.source.nominalHeight,
      remotePath: selected.remotePath,
      remoteUrl: selected.remoteUrl,
      localPath: selected.localPath,
    },
  };
  if (iconVariants.length) catalogEntry.iconVariants = iconVariants;
  return catalogEntry;
}

function createIconVariant(variantId, selected) {
  return {
    variantId,
    isShiny: variantId === 'shiny',
    status: 'remote_available',
    sourceId: selected.source.sourceId,
    role: selected.source.role,
    matchSuitability: selected.source.matchSuitability,
    nominalWidth: selected.width || selected.source.nominalWidth,
    nominalHeight: selected.height || selected.source.nominalHeight,
    remotePath: selected.remotePath,
    remoteUrl: selected.remoteUrl,
    localPath: selected.localPath,
  };
}

function createMissingEntry(entry, status, mapped) {
  return {
    canonicalId: entry.canonicalId,
    showdownId: entry.showdownId,
    displayName: entry.localizedNames?.['zh-Hans']?.[0] || entry.englishName || entry.showdownId,
    englishName: entry.englishName,
    pokemonApi: mapped,
    icon: {
      status,
      sourceId: 'user-labeled-team-preview',
      role: 'local_roi_template',
      matchSuitability: 'requires_user_confirmed_team_preview_crop',
      localPath: `src/data/pokemon-icons/assets/user-labeled-team-preview/${toPokeApiIdentifier(entry.showdownId)}.png`,
    },
  };
}

function toCoverageItem(entry) {
  return {
    showdownId: entry.showdownId,
    displayName: entry.displayName,
    pokeapiIdentifier: entry.pokemonApi?.pokeapiIdentifier,
    pokemonId: entry.pokemonApi?.pokemonId,
    status: entry.icon.status,
    sourceId: entry.icon.sourceId,
    role: entry.icon.role,
  };
}

async function downloadAssets(entries) {
  const jobsByTarget = new Map();
  for (const entry of entries) {
    for (const icon of [entry.icon, ...(entry.iconVariants || [])]) {
      if (icon?.status !== 'remote_available') continue;
      const targetPath = path.join(ROOT, icon.localPath);
      if (!FORCE && fs.existsSync(targetPath)) continue;
      if (!jobsByTarget.has(targetPath)) {
        jobsByTarget.set(targetPath, {
          url: icon.remoteUrl,
          targetPath,
        });
      }
    }
  }
  const jobs = [...jobsByTarget.values()].slice(0, DOWNLOAD_LIMIT);

  if (process.platform === 'win32' && !process.argv.includes('--node-download')) {
    downloadAssetsWithPowerShell(jobs);
    console.log(`Downloaded ${jobs.length} icon assets.`);
    return;
  }

  let downloaded = 0;
  for (const job of jobs) {
    fs.mkdirSync(path.dirname(job.targetPath), {recursive: true});
    await downloadFile(job.url, job.targetPath);
    downloaded++;
  }
  console.log(`Downloaded ${downloaded} icon assets.`);
}

function printSummary(coverage) {
  console.log(`Generated ${path.relative(ROOT, CATALOG_PATH)}`);
  console.log(`Generated ${path.relative(ROOT, COVERAGE_PATH)}`);
  console.log(
    [
      `species=${coverage.counts.totalSpecies}`,
      `remote=${coverage.counts.remoteAvailable}`,
      `box=${coverage.counts.primaryBoxSprite}`,
      `fallbackSprite=${coverage.counts.fallbackSprite}`,
      `fallbackArtwork=${coverage.counts.fallbackArtwork}`,
      `shiny=${coverage.counts.shinyRemoteAvailable}`,
      `shinyMissing=${coverage.counts.shinyTemplateMissing}`,
      `templateRequired=${coverage.counts.userTemplateRequired}`,
      `unmapped=${coverage.counts.unmapped}`,
    ].join(' ')
  );
}

function toPokeApiIdentifier(value) {
  return value
    .normalize('NFKC')
    .toLowerCase()
    .replace(/♀/g, '-f')
    .replace(/♂/g, '-m')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

function fetchText(url) {
  if (process.platform === 'win32') return fetchTextWithPowerShell(url);
  return request(url, 'utf8').catch(error => {
    if (process.platform === 'win32') return fetchTextWithPowerShell(url);
    throw error;
  });
}

async function fetchJson(url) {
  return JSON.parse(await fetchText(url));
}

async function remoteExists(url) {
  const status = await requestStatus(url);
  return status >= 200 && status < 300;
}

function fetchTextWithPowerShell(url) {
  const script = [
    "$ProgressPreference = 'SilentlyContinue'",
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12",
    `(Invoke-WebRequest -UseBasicParsing -Uri ${toPowerShellString(url)} -Headers @{'User-Agent'='pokemon-champion-icon-sync'}).Content`,
  ].join('; ');
  return execFileSync('powershell.exe', ['-NoProfile', '-Command', script], {
    encoding: 'utf8',
    maxBuffer: 128 * 1024 * 1024,
  });
}

function toPowerShellString(value) {
  return `'${String(value).replace(/'/g, "''")}'`;
}

function downloadFile(url, filePath) {
  return request(url, undefined).then(buffer => {
    fs.writeFileSync(filePath, buffer);
  }).catch(error => {
    if (process.platform === 'win32') {
      downloadFileWithPowerShell(url, filePath);
      return;
    }
    throw error;
  });
}

function downloadFileWithPowerShell(url, filePath) {
  const script = [
    "$ProgressPreference = 'SilentlyContinue'",
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12",
    `Invoke-WebRequest -UseBasicParsing -Uri ${toPowerShellString(url)} -OutFile ${toPowerShellString(filePath)} -Headers @{'User-Agent'='pokemon-champion-icon-sync'}`,
  ].join('; ');
  execFileSync('powershell.exe', ['-NoProfile', '-Command', script], {
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
  });
}

function downloadAssetsWithPowerShell(jobs) {
  if (!jobs.length) return;
  fs.mkdirSync(path.join(ROOT, '.tmp'), {recursive: true});
  const tempDir = fs.mkdtempSync(path.join(ROOT, '.tmp', 'pokemon-icons-'));
  const jobPath = path.join(tempDir, 'download-jobs.json');
  fs.writeFileSync(jobPath, JSON.stringify(jobs, null, 2));
  const script = [
    "$ProgressPreference = 'SilentlyContinue'",
    "$ErrorActionPreference = 'Stop'",
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12",
    `$jobs = Get-Content -LiteralPath ${toPowerShellString(jobPath)} -Raw | ConvertFrom-Json`,
    "foreach ($job in $jobs) {",
    "  $dir = Split-Path -Parent $job.targetPath",
    "  New-Item -ItemType Directory -Force -Path $dir | Out-Null",
    "  Invoke-WebRequest -UseBasicParsing -Uri $job.url -OutFile $job.targetPath -Headers @{'User-Agent'='pokemon-champion-icon-sync'}",
    "}",
  ].join('; ');
  try {
    execFileSync('powershell.exe', ['-NoProfile', '-Command', script], {
      encoding: 'utf8',
      maxBuffer: 16 * 1024 * 1024,
    });
  } finally {
    fs.rmSync(tempDir, {recursive: true, force: true});
  }
}

function request(url, encoding, redirectCount = 0, attempt = 1) {
  return new Promise((resolve, reject) => {
    const retryOrReject = error => {
      if (attempt < 6 && ['ECONNRESET', 'ETIMEDOUT', 'EAI_AGAIN'].includes(error.code)) {
        setTimeout(() => {
          resolve(request(url, encoding, redirectCount, attempt + 1));
        }, 500 * attempt);
        return;
      }
      reject(error);
    };

    https
      .get(url, {family: 4, headers: {'Connection': 'close', 'User-Agent': 'pokemon-champion-icon-sync'}}, response => {
        const status = response.statusCode || 0;
        if (status >= 300 && status < 400 && response.headers.location) {
          response.resume();
          if (redirectCount > 5) {
            reject(new Error(`Too many redirects for ${url}`));
            return;
          }
          resolve(request(new URL(response.headers.location, url).toString(), encoding, redirectCount + 1, attempt));
          return;
        }
        if (status < 200 || status >= 300) {
          response.resume();
          reject(new Error(`Request failed ${status}: ${url}`));
          return;
        }
        const chunks = [];
        response.on('data', chunk => chunks.push(chunk));
        response.on('error', retryOrReject);
        response.on('end', () => {
          const buffer = Buffer.concat(chunks);
          resolve(encoding ? buffer.toString(encoding) : buffer);
        });
      })
      .on('error', retryOrReject);
  });
}

function requestStatus(url, redirectCount = 0, attempt = 1) {
  return new Promise((resolve, reject) => {
    const retryOrReject = error => {
      if (attempt < 6 && ['ECONNRESET', 'ETIMEDOUT', 'EAI_AGAIN'].includes(error.code)) {
        setTimeout(() => {
          resolve(requestStatus(url, redirectCount, attempt + 1));
        }, 500 * attempt);
        return;
      }
      reject(error);
    };

    const requestOptions = new URL(url);
    requestOptions.family = 4;
    requestOptions.method = 'HEAD';
    requestOptions.headers = {'Connection': 'close', 'User-Agent': 'pokemon-champion-icon-sync'};

    const req = https.request(requestOptions, response => {
      const status = response.statusCode || 0;
      response.on('error', retryOrReject);
      if (status >= 300 && status < 400 && response.headers.location) {
        response.resume();
        if (redirectCount > 5) {
          reject(new Error(`Too many redirects for ${url}`));
          return;
        }
        resolve(requestStatus(new URL(response.headers.location, url).toString(), redirectCount + 1, attempt));
        return;
      }
      response.resume();
      resolve(status);
    });
    req.on('error', retryOrReject);
    req.end();
  });
}
