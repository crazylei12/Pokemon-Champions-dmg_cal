#!/usr/bin/env node

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import sharp from 'sharp';

const DEFAULT_ROI_CONFIG = 'src/data/recognition/roi.pokemon-champions.zh-Hans.v1.json';
const DEFAULT_ICON_CATALOG = 'src/data/pokemon-icons/catalog.pokeapi-composite.json';
const FEATURE_SIZE = 64;
const TEXTURE_BG_THRESHOLD = 58;

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.image || !args.scene) {
    printHelp();
    process.exit(args.help ? 0 : 1);
  }

  const roiConfigPath = args.roiConfig || DEFAULT_ROI_CONFIG;
  const iconCatalogPath = args.iconCatalog || DEFAULT_ICON_CATALOG;
  const config = await readJson(roiConfigPath);
  const catalog = await readJson(iconCatalogPath);
  const scene = config.scenes?.[args.scene];
  if (!scene) throw new Error(`Scene ${args.scene} does not exist in ${roiConfigPath}`);

  const image = sharp(args.image, { limitInputPixels: false });
  const metadata = await image.metadata();
  if (!metadata.width || !metadata.height) {
    throw new Error(`Cannot read image size: ${args.image}`);
  }

  const viewport = detectGameViewport(
    { width: metadata.width, height: metadata.height },
    config.viewportDetection?.targetAspectRatio
  );
  const regions = selectIconRegions(scene.regions || [], args);
  const templates = await loadTemplateFeatures(catalog, {
    limit: args.maxTemplates,
    rootDir: process.cwd(),
  });
  if (!templates.length) {
    throw new Error(`No local icon templates found from ${iconCatalogPath}. Run pokemon icon sync with --download first.`);
  }

  const iconResults = [];
  const warnings = [];
  for (const region of regions) {
    const rect = roiRegionToPixelRect(config, region, viewport);
    const crop = await sharp(args.image, { limitInputPixels: false }).extract(rect).png().toBuffer();
    if (args.cropDir) await writeCrop(args.cropDir, region.id, crop);

    const cropFeature = await createCropFeature(crop);
    const candidates = templates
      .map((template) => ({
        canonicalId: template.canonicalId,
        showdownId: template.showdownId,
        displayName: template.displayName,
        entityType: 'species',
        roiId: region.id,
        confidence: compareFeatures(cropFeature, template.feature),
        source: 'POKEMON_ICON_MATCH',
        visualVariant: template.visualVariant,
        isShiny: template.isShiny,
        iconSourceId: template.iconSourceId,
        templatePath: template.localPath,
      }))
      .sort((left, right) => right.confidence - left.confidence)
      .slice(0, args.topK || region.match?.topK || 3);

    const best = candidates[0];
    if (!best || best.confidence < (region.match?.minConfidence || 0)) {
      warnings.push(`Low icon-match confidence for ${region.id}.`);
    }

    iconResults.push({
      roiId: region.id,
      role: region.role,
      entityType: 'species',
      side: parseSide(region.id),
      slotIndex: parseSlotIndex(region.id),
      rect,
      candidates,
    });
  }

  const result = {
    sceneType: args.scene,
    language: config.language || 'zh-Hans',
    image: {
      path: args.image,
      width: metadata.width,
      height: metadata.height,
    },
    viewport,
    iconCatalog: {
      path: iconCatalogPath,
      templatesLoaded: templates.length,
    },
    iconResults,
    warnings,
  };

  const json = `${JSON.stringify(result, null, args.pretty === false ? 0 : 2)}\n`;
  if (args.output) {
    await fs.mkdir(path.dirname(args.output), { recursive: true });
    await fs.writeFile(args.output, json, 'utf8');
  } else {
    process.stdout.write(json);
  }
}

function parseArgs(argv) {
  const args = { scene: 'TEAM_PREVIEW', pretty: true };
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i];
    if (value === '--help' || value === '-h') args.help = true;
    else if (value === '--image') args.image = argv[++i];
    else if (value === '--scene') args.scene = argv[++i];
    else if (value === '--roi-config') args.roiConfig = argv[++i];
    else if (value === '--icon-catalog') args.iconCatalog = argv[++i];
    else if (value === '--output') args.output = argv[++i];
    else if (value === '--crop-dir') args.cropDir = argv[++i];
    else if (value === '--top-k') args.topK = Number(argv[++i]);
    else if (value === '--max-templates') args.maxTemplates = Number(argv[++i]);
    else if (value === '--compact') args.pretty = false;
    else throw new Error(`Unknown argument: ${value}`);
  }
  return args;
}

function printHelp() {
  console.log(`Usage:
  npm run recognition:icons -- --image docs/pic/team_preview/sample.jpg --scene TEAM_PREVIEW --output .tmp/icons.json

Options:
  --image          Screenshot path.
  --scene          TEAM_PREVIEW by default; any scene with pokemon_icon ROI is accepted.
  --roi-config     ROI JSON path, default ${DEFAULT_ROI_CONFIG}.
  --icon-catalog   Pokemon icon catalog path, default ${DEFAULT_ICON_CATALOG}.
  --crop-dir       Optional directory for writing pokemon_icon crops.
  --top-k          Candidate count per slot, default ROI topK or 3.
  --max-templates  Limit local templates for quick smoke tests.
  --output         Optional JSON output path.
  --compact        Write compact JSON.
`);
}

async function readJson(filePath) {
  return JSON.parse(await fs.readFile(filePath, 'utf8'));
}

function selectIconRegions(regions) {
  return regions.filter((region) => region.role === 'pokemon_icon');
}

async function loadTemplateFeatures(catalog, options) {
  const templates = [];
  for (const entry of catalog.entries || []) {
    for (const template of catalogTemplateIcons(entry)) {
      const localPath = template.icon?.localPath;
      if (!localPath) continue;
      const absolutePath = path.resolve(options.rootDir, localPath);
      try {
        await fs.access(absolutePath);
        templates.push({
          canonicalId: entry.canonicalId,
          showdownId: entry.showdownId,
          displayName: entry.displayName,
          localPath,
          visualVariant: template.variantId,
          isShiny: template.isShiny,
          iconSourceId: template.icon.sourceId,
          feature: await createTemplateFeature(absolutePath),
        });
      } catch {
        continue;
      }
    }
    if (Number.isInteger(options.limit) && templates.length >= options.limit) break;
  }
  return templates;
}

function catalogTemplateIcons(entry) {
  const templates = [];
  if (entry.icon) {
    templates.push({
      variantId: 'normal',
      isShiny: false,
      icon: entry.icon,
    });
  }
  for (const icon of entry.iconVariants || []) {
    templates.push({
      variantId: icon.variantId || 'variant',
      isShiny: Boolean(icon.isShiny),
      icon,
    });
  }
  return templates;
}

async function createTemplateFeature(imagePath) {
  const input = await sharp(imagePath, { limitInputPixels: false })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const bbox = alphaBoundingBox(input.data, input.info.width, input.info.height) || {
    left: 0,
    top: 0,
    width: input.info.width,
    height: input.info.height,
  };
  return rgbaFeatureFromSharp(
    sharp(imagePath, { limitInputPixels: false }).ensureAlpha().extract(bbox)
  );
}

async function createCropFeature(crop) {
  const input = await sharp(crop, { limitInputPixels: false })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const foreground = detectForeground(input.data, input.info.width, input.info.height);
  if (!foreground.bbox) {
    return rgbaFeatureFromSharp(sharp(crop, { limitInputPixels: false }).ensureAlpha());
  }

  const masked = Buffer.from(input.data);
  for (let index = 0; index < foreground.mask.length; index++) {
    masked[index * 4 + 3] = foreground.mask[index] ? 255 : 0;
  }

  return rgbaFeatureFromSharp(
    sharp(masked, {
      raw: {
        width: input.info.width,
        height: input.info.height,
        channels: 4,
      },
    }).extract(foreground.bbox)
  );
}

async function rgbaFeatureFromSharp(pipeline) {
  const result = await pipeline
    .resize({
      width: FEATURE_SIZE,
      height: FEATURE_SIZE,
      fit: 'contain',
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  return {
    width: result.info.width,
    height: result.info.height,
    data: result.data,
  };
}

function detectForeground(data, width, height) {
  const prototypes = sampleBackgroundPrototypes(data, width, height);
  const baseMask = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const offset = (y * width + x) * 4;
      if (data[offset + 3] < 8) continue;
      const distance = minColorDistance(data[offset], data[offset + 1], data[offset + 2], prototypes);
      if (distance > TEXTURE_BG_THRESHOLD) baseMask[y * width + x] = 1;
    }
  }

  const components = connectedComponents(baseMask, width, height)
    .filter((component) => component.area >= Math.max(18, width * height * 0.002))
    .filter((component) => component.width >= width * 0.08 && component.height >= height * 0.08)
    .map((component) => ({
      ...component,
      score: scoreComponent(component, width, height),
    }))
    .sort((left, right) => right.score - left.score);

  if (!components.length) return { mask: baseMask, bbox: undefined };
  const bestScore = components[0].score;
  const selected = components.filter((component) => component.score >= bestScore * 0.22);
  const mask = new Uint8Array(width * height);
  let left = width;
  let top = height;
  let right = 0;
  let bottom = 0;
  for (const component of selected) {
    for (const index of component.indices) mask[index] = 1;
    left = Math.min(left, component.left);
    top = Math.min(top, component.top);
    right = Math.max(right, component.right);
    bottom = Math.max(bottom, component.bottom);
  }

  const padding = 2;
  left = Math.max(0, left - padding);
  top = Math.max(0, top - padding);
  right = Math.min(width - 1, right + padding);
  bottom = Math.min(height - 1, bottom + padding);

  return {
    mask,
    bbox: {
      left,
      top,
      width: right - left + 1,
      height: bottom - top + 1,
    },
  };
}

function sampleBackgroundPrototypes(data, width, height) {
  const boxes = [
    { left: 0, top: 0, width: Math.max(1, Math.floor(width * 0.18)), height: Math.max(1, Math.floor(height * 0.18)) },
    { left: Math.floor(width * 0.82), top: 0, width: Math.max(1, Math.ceil(width * 0.18)), height: Math.max(1, Math.floor(height * 0.18)) },
    { left: 0, top: Math.floor(height * 0.82), width: Math.max(1, Math.floor(width * 0.18)), height: Math.max(1, Math.ceil(height * 0.18)) },
    { left: Math.floor(width * 0.82), top: Math.floor(height * 0.82), width: Math.max(1, Math.ceil(width * 0.18)), height: Math.max(1, Math.ceil(height * 0.18)) },
    { left: 0, top: 0, width, height: Math.max(1, Math.floor(height * 0.05)) },
    { left: 0, top: Math.floor(height * 0.95), width, height: Math.max(1, Math.ceil(height * 0.05)) },
  ];
  return boxes.map((box) => averageColor(data, width, height, box));
}

function averageColor(data, width, height, box) {
  let r = 0;
  let g = 0;
  let b = 0;
  let count = 0;
  for (let y = box.top; y < Math.min(height, box.top + box.height); y++) {
    for (let x = box.left; x < Math.min(width, box.left + box.width); x++) {
      const offset = (y * width + x) * 4;
      if (data[offset + 3] < 8) continue;
      r += data[offset];
      g += data[offset + 1];
      b += data[offset + 2];
      count++;
    }
  }
  if (!count) return { r: 0, g: 0, b: 0 };
  return { r: r / count, g: g / count, b: b / count };
}

function minColorDistance(r, g, b, prototypes) {
  let min = Infinity;
  for (const prototype of prototypes) {
    const dr = r - prototype.r;
    const dg = g - prototype.g;
    const db = b - prototype.b;
    min = Math.min(min, Math.sqrt(dr * dr + dg * dg + db * db));
  }
  return min;
}

function connectedComponents(mask, width, height) {
  const visited = new Uint8Array(mask.length);
  const components = [];
  const queue = [];
  for (let start = 0; start < mask.length; start++) {
    if (!mask[start] || visited[start]) continue;
    queue.length = 0;
    queue.push(start);
    visited[start] = 1;
    let area = 0;
    let left = width;
    let top = height;
    let right = 0;
    let bottom = 0;
    const indices = [];
    for (let cursor = 0; cursor < queue.length; cursor++) {
      const index = queue[cursor];
      const x = index % width;
      const y = Math.floor(index / width);
      indices.push(index);
      area++;
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x);
      bottom = Math.max(bottom, y);
      pushNeighbor(index - 1, x > 0);
      pushNeighbor(index + 1, x < width - 1);
      pushNeighbor(index - width, y > 0);
      pushNeighbor(index + width, y < height - 1);
    }
    components.push({
      area,
      left,
      top,
      right,
      bottom,
      width: right - left + 1,
      height: bottom - top + 1,
      indices,
      touchesBorder: left === 0 || top === 0 || right === width - 1 || bottom === height - 1,
    });
  }
  return components;

  function pushNeighbor(index, valid) {
    if (!valid || !mask[index] || visited[index]) return;
    visited[index] = 1;
    queue.push(index);
  }
}

function scoreComponent(component, width, height) {
  const centerX = (component.left + component.right) / 2;
  const centerY = (component.top + component.bottom) / 2;
  const dx = (centerX - width / 2) / width;
  const dy = (centerY - height / 2) / height;
  const centerWeight = 1 / (1 + Math.sqrt(dx * dx + dy * dy) * 2.4);
  const touchWeight = component.touchesBorder ? 0.62 : 1;
  const thinness = Math.min(component.width / width, component.height / height);
  const shapeWeight = thinness < 0.12 ? 0.35 : 1;
  return component.area * centerWeight * touchWeight * shapeWeight;
}

function alphaBoundingBox(data, width, height) {
  let left = width;
  let top = height;
  let right = -1;
  let bottom = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const alpha = data[(y * width + x) * 4 + 3];
      if (alpha <= 8) continue;
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x);
      bottom = Math.max(bottom, y);
    }
  }
  if (right < left || bottom < top) return undefined;
  return { left, top, width: right - left + 1, height: bottom - top + 1 };
}

function compareFeatures(left, right) {
  let intersection = 0;
  let union = 0;
  let colorDistance = 0;
  let colorCount = 0;
  for (let i = 0; i < left.data.length; i += 4) {
    const leftMask = left.data[i + 3] > 16;
    const rightMask = right.data[i + 3] > 16;
    if (leftMask || rightMask) union++;
    if (!leftMask || !rightMask) continue;
    intersection++;
    const dr = left.data[i] - right.data[i];
    const dg = left.data[i + 1] - right.data[i + 1];
    const db = left.data[i + 2] - right.data[i + 2];
    colorDistance += dr * dr + dg * dg + db * db;
    colorCount++;
  }
  if (!union) return 0;
  const shapeScore = intersection / union;
  const colorScore = colorCount
    ? 1 - Math.sqrt(colorDistance / colorCount / (255 * 255 * 3))
    : 0;
  return clamp01(shapeScore * 0.38 + colorScore * 0.62);
}

async function writeCrop(cropDir, roiId, crop) {
  await fs.mkdir(cropDir, { recursive: true });
  const fileName = `${roiId.replace(/[^a-z0-9_.-]+/gi, '_')}.png`;
  await fs.writeFile(path.join(cropDir, fileName), crop);
}

function detectGameViewport(imageSize, targetAspectRatioText) {
  const targetRatio = parseAspectRatio(targetAspectRatioText) || 16 / 9;
  const ratio = imageSize.width / imageSize.height;
  if (Math.abs(ratio - targetRatio) < 0.001) {
    return {
      left: 0,
      top: 0,
      width: imageSize.width,
      height: imageSize.height,
      source: 'full_image',
    };
  }
  if (ratio > targetRatio) {
    const width = Math.round(imageSize.height * targetRatio);
    return {
      left: Math.max(0, Math.round((imageSize.width - width) / 2)),
      top: 0,
      width,
      height: imageSize.height,
      source: 'largest_16_9_game_content_area',
    };
  }
  const height = Math.round(imageSize.width / targetRatio);
  return {
    left: 0,
    top: Math.max(0, Math.round((imageSize.height - height) / 2)),
    width: imageSize.width,
    height,
    source: 'largest_16_9_game_content_area',
  };
}

function parseAspectRatio(value) {
  const match = value?.match(/^(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$/);
  if (!match) return undefined;
  const width = Number(match[1]);
  const height = Number(match[2]);
  return width > 0 && height > 0 ? width / height : undefined;
}

function roiRegionToPixelRect(config, region, viewport) {
  const unit = config.coordinateSpace?.unit || 'normalized';
  const canonical = config.canonicalViewport || viewport;
  const left =
    unit === 'normalized'
      ? viewport.left + region.rect.x * viewport.width
      : viewport.left + region.rect.x * (viewport.width / canonical.width);
  const top =
    unit === 'normalized'
      ? viewport.top + region.rect.y * viewport.height
      : viewport.top + region.rect.y * (viewport.height / canonical.height);
  const width =
    unit === 'normalized'
      ? region.rect.width * viewport.width
      : region.rect.width * (viewport.width / canonical.width);
  const height =
    unit === 'normalized'
      ? region.rect.height * viewport.height
      : region.rect.height * (viewport.height / canonical.height);

  return clampRect(
    {
      left: Math.floor(left),
      top: Math.floor(top),
      width: Math.max(1, Math.ceil(width)),
      height: Math.max(1, Math.ceil(height)),
    },
    viewport
  );
}

function clampRect(rect, viewport) {
  const left = clamp(rect.left, viewport.left, viewport.left + viewport.width - 1);
  const top = clamp(rect.top, viewport.top, viewport.top + viewport.height - 1);
  const right = clamp(rect.left + rect.width, left + 1, viewport.left + viewport.width);
  const bottom = clamp(rect.top + rect.height, top + 1, viewport.top + viewport.height);
  return {
    left,
    top,
    width: right - left,
    height: bottom - top,
  };
}

function parseSide(roiId) {
  if (roiId.includes('.own.')) return 'OWN';
  if (roiId.includes('.opponent.')) return 'OPPONENT';
  return 'UNKNOWN';
}

function parseSlotIndex(roiId) {
  const match = roiId.match(/\.slot(\d+)\./);
  return match ? Number(match[1]) : undefined;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function clamp01(value) {
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
