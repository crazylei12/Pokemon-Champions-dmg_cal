#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const DEFAULT_BASE = 'src/data/recognition/roi.pokemon-champions.zh-Hans.v1.json';

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.input || !args.scene) {
    printHelp();
    process.exit(args.help ? 0 : 1);
  }

  const basePath = args.base || DEFAULT_BASE;
  const outputPath = args.output || basePath;
  const labelme = readJson(args.input);
  const config = readJson(basePath);

  if (!config.scenes?.[args.scene]) {
    throw new Error(`Scene ${args.scene} does not exist in ${basePath}`);
  }

  const viewport = findViewport(labelme);
  const regions = [];
  for (const shape of labelme.shapes || []) {
    if (!shape.label || shape.label === 'viewport.game') continue;
    const rect = toNormalizedRect(toBounds(shape.points), viewport);
    regions.push({
      id: normalizeRegionId(shape.label),
      role: inferRole(shape.label),
      entityType: inferEntityType(shape.label),
      rect,
      preprocess: inferPreprocess(shape.label),
      match: inferMatch(shape.label),
    });
  }

  regions.sort((left, right) => left.id.localeCompare(right.id));
  config.scenes[args.scene].regions = regions;
  config.scenes[args.scene].status = 'draft';

  writeJson(outputPath, config);
  console.log(
    `Converted ${regions.length} ROI regions for ${args.scene} from ${args.input} -> ${outputPath}`
  );
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i];
    if (value === '--help' || value === '-h') args.help = true;
    else if (value === '--input') args.input = argv[++i];
    else if (value === '--scene') args.scene = argv[++i];
    else if (value === '--base') args.base = argv[++i];
    else if (value === '--output') args.output = argv[++i];
    else throw new Error(`Unknown argument: ${value}`);
  }
  return args;
}

function printHelp() {
  console.log(`Usage:
  node tools/recognition/convert-labelme-roi.mjs --scene TEAM_PREVIEW --input path/to/labelme.json

Options:
  --scene   One of OWN_TEAM_MOVE_ITEM, OWN_TEAM_STATS, TEAM_PREVIEW, BATTLE_FIELD
  --input   labelme JSON file with a viewport.game shape
  --base    Existing ROI JSON to update, default ${DEFAULT_BASE}
  --output  Output ROI JSON, default overwrites --base
`);
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function findViewport(labelme) {
  const shape = (labelme.shapes || []).find((item) => item.label === 'viewport.game');
  if (shape) return toBounds(shape.points);

  if (!labelme.imageWidth || !labelme.imageHeight) {
    throw new Error('labelme JSON has no viewport.game shape and no imageWidth/imageHeight fallback.');
  }
  return {
    x1: 0,
    y1: 0,
    x2: labelme.imageWidth,
    y2: labelme.imageHeight,
  };
}

function toBounds(points = []) {
  if (!points.length) throw new Error('Shape has no points.');
  const xs = points.map((point) => point[0]);
  const ys = points.map((point) => point[1]);
  return {
    x1: Math.min(...xs),
    y1: Math.min(...ys),
    x2: Math.max(...xs),
    y2: Math.max(...ys),
  };
}

function toNormalizedRect(bounds, viewport) {
  const viewportWidth = viewport.x2 - viewport.x1;
  const viewportHeight = viewport.y2 - viewport.y1;
  if (viewportWidth <= 0 || viewportHeight <= 0) {
    throw new Error(`Invalid viewport bounds: ${JSON.stringify(viewport)}`);
  }

  return {
    x: round6((bounds.x1 - viewport.x1) / viewportWidth),
    y: round6((bounds.y1 - viewport.y1) / viewportHeight),
    width: round6((bounds.x2 - bounds.x1) / viewportWidth),
    height: round6((bounds.y2 - bounds.y1) / viewportHeight),
  };
}

function normalizeRegionId(label) {
  return label.trim().replace(/\s+/g, '_').toLowerCase();
}

function inferRole(label) {
  if (label.includes('pokemon_icon')) return 'pokemon_icon';
  if (label.includes('move') && label.includes('type_icon')) return 'move_type_icon';
  if (label.includes('type_icon') || label.includes('type_icons')) return 'type_icon';
  if (label.includes('gender_icon')) return 'gender_icon';
  if (label.includes('hp_bar')) return 'hp_bar';
  if (label.includes('level') || label.includes('stat_') || label.includes('actual_stats')) {
    return 'number_text';
  }
  if (
    label.includes('anchor') ||
    label.includes('hint') ||
    label.includes('team_header') ||
    label.includes('battle_type')
  ) {
    return 'scene_marker';
  }
  return 'ocr_text';
}

function inferEntityType(label) {
  if (label.includes('species_name') || label.includes('pokemon_icon')) return 'SPECIES';
  if (label.includes('move') && label.includes('name')) return 'MOVE';
  if (label.includes('item_name')) return 'ITEM';
  if (label.includes('ability_name')) return 'ABILITY';
  if (label.includes('type_icon') || label.includes('type_icons')) return 'TYPE';
  if (label.includes('stat_') || label.includes('actual_stats') || label.includes('level')) return 'STAT';
  return 'UNKNOWN';
}

function inferPreprocess(label) {
  if (inferRole(label).includes('icon')) {
    return {
      colorMode: 'keep_color',
      scale: 1,
      sharpen: false,
    };
  }

  if (inferRole(label) === 'ocr_text' || inferRole(label) === 'number_text') {
    return {
      colorMode: 'grayscale',
      scale: 2,
      threshold: 'adaptive',
      sharpen: true,
    };
  }

  return {
    colorMode: 'keep_color',
    scale: 1,
    sharpen: false,
  };
}

function inferMatch(label) {
  const role = inferRole(label);
  if (role === 'pokemon_icon' || role === 'type_icon' || role === 'move_type_icon') {
    return {
      engine: 'template_match',
      topK: role === 'pokemon_icon' ? 3 : 2,
      minConfidence: role === 'pokemon_icon' ? 0.6 : 0.75,
    };
  }

  if (role === 'ocr_text' || role === 'number_text') {
    return {
      engine: 'ocr',
      topK: 3,
      minConfidence: 0.6,
    };
  }

  return {
    engine: 'manual',
    topK: 1,
    minConfidence: 0.6,
  };
}

function round6(value) {
  return Number(value.toFixed(6));
}

main();
