#!/usr/bin/env node

import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { promisify } from 'node:util';
import sharp from 'sharp';

const execFileAsync = promisify(execFile);

const DEFAULT_MOVE_ITEM_DIR = 'docs/pic/own_team_move_item';
const DEFAULT_STATS_DIR = 'docs/pic/own_team_stats';
const DEFAULT_OUTPUT_DIR = '.tmp/rapidocr-own-team';
const DEFAULT_SAVED_TEAM_DIR = 'data/saved-teams';
const DEFAULT_NAME_ENTRIES = 'src/data/localization/zh-Hans.json';
const RAPIDOCR_SCRIPT = 'tools/recognition/rapidocr-json.py';

const SLOT_COUNT = 6;
const DEFAULT_LEVEL = 50;
const STAT_IDS = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];
const STAT_CELLS = {
  hp: [0.24, 0.39, 0.21, 0.41],
  atk: [0.24, 0.39, 0.43, 0.64],
  def: [0.24, 0.39, 0.66, 0.9],
  spa: [0.71, 0.86, 0.21, 0.41],
  spd: [0.71, 0.86, 0.43, 0.64],
  spe: [0.71, 0.86, 0.66, 0.9],
};

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const outputDir = args.outputDir || DEFAULT_OUTPUT_DIR;
  const savedTeamDir = args.savedTeamDir || DEFAULT_SAVED_TEAM_DIR;
  const generatedAt = new Date().toISOString();
  const python = args.python || (await findRapidOcrPython());
  const nameEntries = JSON.parse(await fs.readFile(args.nameEntries || DEFAULT_NAME_ENTRIES, 'utf8'));
  const lookup = createLookup(nameEntries);
  const moveItemFiles = await listImages(args.moveItemDir || DEFAULT_MOVE_ITEM_DIR);
  const statsFiles = await listImages(args.statsDir || DEFAULT_STATS_DIR);
  const allFiles = [...moveItemFiles, ...statsFiles];
  if (!allFiles.length) throw new Error('No images found.');

  await fs.mkdir(outputDir, { recursive: true });
  const rapid = await runRapidOcrBatch(python, allFiles);
  const byPath = new Map(rapid.images.map((image) => [path.resolve(image.path).toLowerCase(), image]));

  const moveItemResults = [];
  for (const file of moveItemFiles) {
    const ocr = byPath.get(path.resolve(file).toLowerCase());
    const result = await extractMoveItemImage(file, ocr, lookup, {
      python,
      outputDir,
    });
    moveItemResults.push(result);
    await writeJson(path.join(outputDir, 'move_item', `${path.parse(file).name}.json`), result);
  }

  const statsResults = [];
  for (const file of statsFiles) {
    const ocr = byPath.get(path.resolve(file).toLowerCase());
    const result = await extractStatsImage(file, ocr, lookup, {
      python,
      outputDir,
    });
    statsResults.push(result);
    await writeJson(path.join(outputDir, 'stats', `${path.parse(file).name}.json`), result);
  }

  const savedTeam = args.noSaveTeam || !statsResults.length
    ? undefined
    : createSavedOwnTeam({
      teamName: args.teamName,
      generatedAt,
      moveItemResults,
      statsResults,
    });
  const savedTeamFile = savedTeam
    ? args.teamFile || path.join(savedTeamDir, `${savedTeam.savedTeamId}.json`)
    : undefined;
  if (savedTeam && savedTeamFile) await writeJson(savedTeamFile, savedTeam);

  const summary = {
    backend: 'rapidocr',
    python,
    generatedAt,
    counts: {
      moveItemImages: moveItemResults.length,
      statsImages: statsResults.length,
    },
    moveItem: summarizeMoveItem(moveItemResults),
    stats: summarizeStats(statsResults),
    outputs: {
      outputDir,
      savedTeamDir,
      savedTeamFile,
    },
    savedTeam,
    moveItemResults,
    statsResults,
  };
  await writeJson(path.join(outputDir, 'summary.json'), summary);

  if (args.compact) {
    process.stdout.write(`${JSON.stringify(summary)}\n`);
  } else {
    printSummary(summary);
  }
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i];
    if (value === '--move-item-dir') args.moveItemDir = argv[++i];
    else if (value === '--stats-dir') args.statsDir = argv[++i];
    else if (value === '--name-entries') args.nameEntries = argv[++i];
    else if (value === '--output-dir') args.outputDir = argv[++i];
    else if (value === '--saved-team-dir') args.savedTeamDir = argv[++i];
    else if (value === '--team-file') args.teamFile = argv[++i];
    else if (value === '--team-name') args.teamName = argv[++i];
    else if (value === '--python') args.python = argv[++i];
    else if (value === '--no-save-team') args.noSaveTeam = true;
    else if (value === '--compact') args.compact = true;
    else throw new Error(`Unknown argument: ${value}`);
  }
  return args;
}

async function findRapidOcrPython() {
  const candidates = [
    path.resolve('.tmp/rapidocr-venv/Scripts/python.exe'),
    process.env.RAPIDOCR_PYTHON,
    'python',
  ].filter(Boolean);

  for (const candidate of candidates) {
    try {
      await execFileAsync(candidate, ['-c', 'import rapidocr, onnxruntime'], {
        cwd: process.cwd(),
        windowsHide: true,
      });
      return candidate;
    } catch {
      // Try the next candidate.
    }
  }
  throw new Error(
    'RapidOCR Python environment not found. Create one with: python -m venv .tmp\\rapidocr-venv; .\\.tmp\\rapidocr-venv\\Scripts\\python.exe -m pip install rapidocr onnxruntime'
  );
}

async function listImages(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  return entries
    .filter((entry) => entry.isFile() && /\.(png|jpe?g|webp|bmp)$/i.test(entry.name))
    .map((entry) => path.join(dir, entry.name))
    .sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'));
}

async function runRapidOcrBatch(python, files) {
  const args = [RAPIDOCR_SCRIPT, '--return-word-box'];
  for (const file of files) args.push('--image', file);
  const { stdout, stderr } = await execFileAsync(python, args, {
    cwd: process.cwd(),
    windowsHide: true,
    maxBuffer: 64 * 1024 * 1024,
    env: {
      ...process.env,
      PYTHONIOENCODING: 'utf-8',
    },
  });
  if (stderr && process.env.DEBUG_RAPIDOCR) process.stderr.write(stderr);
  return JSON.parse(stdout);
}

async function extractMoveItemImage(file, ocr, lookup, fallbackOptions = {}) {
  const cards = await detectTeamCards(file);
  const lines = normalizeOcrLines(ocr?.lines || []);
  const slots = cards.map((card, slotIndex) => {
    const cardLines = linesInCard(lines, card);
    const slot = {
      slotIndex,
      card,
      species: pickField(cardLines, card, 'species', lookup),
      ability: pickField(cardLines, card, 'ability', lookup),
      item: pickField(cardLines, card, 'item', lookup),
      moves: [0, 1, 2, 3].map((moveIndex) => pickField(cardLines, card, `move${moveIndex}`, lookup)),
      ocrFallbacks: [],
      warnings: [],
    };
    updateMoveItemWarnings(slot);
    return slot;
  });

  await repairMoveItemSlotsWithCardOcr(file, slots, lookup, fallbackOptions);

  return {
    sceneType: 'OWN_TEAM_MOVE_ITEM',
    image: await imageInfo(file),
    teamId: extractTeamId(lines),
    cards,
    slots,
    recognition: countMoveItemFields(slots),
  };
}

function updateMoveItemWarnings(slot) {
  slot.warnings = [];
  if (!slot.species) slot.warnings.push('Missing species.');
  if (!slot.ability) slot.warnings.push('Missing ability.');
  if (!slot.item) slot.warnings.push('Missing item.');
  for (let index = 0; index < slot.moves.length; index++) {
    if (!slot.moves[index]) slot.warnings.push(`Missing move${index}.`);
  }
}

function missingMoveItemFields(slot) {
  const fields = [];
  if (!slot.species) fields.push('species');
  if (!slot.ability) fields.push('ability');
  if (!slot.item) fields.push('item');
  for (let index = 0; index < slot.moves.length; index++) {
    if (!slot.moves[index]) fields.push(`move${index}`);
  }
  return fields;
}

async function repairMoveItemSlotsWithCardOcr(file, slots, lookup, options) {
  if (!options.python) return;
  const targets = slots
    .map((slot) => ({ slot, fields: missingMoveItemFields(slot) }))
    .filter((target) => target.fields.length);
  if (!targets.length) return;

  const cropDir = path.join(options.outputDir || DEFAULT_OUTPUT_DIR, 'fallback_crops', 'move_item', path.parse(file).name);
  await fs.mkdir(cropDir, { recursive: true });
  const scale = 3;
  const cropJobs = [];
  for (const target of targets) {
    const cropFile = path.join(cropDir, `slot${target.slot.slotIndex}.card.png`);
    await writeCardCrop(file, target.slot.card, cropFile, scale);
    cropJobs.push({ ...target, cropFile, cropCard: scaledCard(target.slot.card, scale) });
  }

  const rapid = await runRapidOcrBatch(options.python, cropJobs.map((job) => job.cropFile));
  for (let index = 0; index < cropJobs.length; index++) {
    const job = cropJobs[index];
    const cropLines = normalizeOcrLines(rapid.images[index]?.lines || []);
    for (const field of job.fields) {
      const replacement = pickField(cropLines, job.cropCard, field, lookup);
      if (!replacement) continue;
      if (field === 'species') job.slot.species = replacement;
      else if (field === 'ability') job.slot.ability = replacement;
      else if (field === 'item') job.slot.item = replacement;
      else if (field.startsWith('move')) job.slot.moves[Number(field.replace('move', ''))] = replacement;
      job.slot.ocrFallbacks.push({
        field,
        rawText: replacement.rawText,
        source: 'rapidocr_card_crop_3x',
      });
    }
    updateMoveItemWarnings(job.slot);
  }
}

async function writeCardCrop(file, card, cropFile, scale) {
  await sharp(file, { limitInputPixels: false })
    .extract({
      left: Math.max(0, Math.round(card.left)),
      top: Math.max(0, Math.round(card.top)),
      width: Math.round(card.width),
      height: Math.round(card.height),
    })
    .resize({
      width: Math.round(card.width * scale),
      height: Math.round(card.height * scale),
      fit: 'fill',
      kernel: 'lanczos3',
    })
    .png()
    .toFile(cropFile);
}

function scaledCard(card, scale) {
  return {
    left: 0,
    top: 0,
    width: Math.round(card.width * scale),
    height: Math.round(card.height * scale),
  };
}

async function extractStatsImage(file, ocr, lookup, fallbackOptions = {}) {
  const cards = await detectTeamCards(file);
  const lines = normalizeOcrLines(ocr?.lines || []);
  const slots = cards.map((card, slotIndex) => {
    const cardLines = linesInCard(lines, card);
    const slot = {
      slotIndex,
      card,
      species: pickStatsSpecies(cardLines, card, lookup),
      actualStats: {},
      ocrFallbacks: [],
      warnings: [],
    };
    for (const stat of STAT_IDS) {
      slot.actualStats[stat] = pickNumber(cardLines, card, STAT_CELLS[stat], { kind: 'actual', stat });
    }
    updateStatsWarnings(slot);
    return slot;
  });

  await repairStatsSlotsWithStatOcr(file, slots, fallbackOptions);

  return {
    sceneType: 'OWN_TEAM_STATS',
    image: await imageInfo(file),
    teamId: extractTeamId(lines),
    cards,
    slots,
    recognition: countStatsFields(slots),
  };
}

function updateStatsWarnings(slot) {
  slot.warnings = [];
  if (!slot.species) slot.warnings.push('Missing species.');
  for (const stat of STAT_IDS) {
    if (!Number.isFinite(slot.actualStats[stat])) slot.warnings.push(`Missing actualStats.${stat}.`);
  }
}

function missingActualStats(slot) {
  return STAT_IDS.filter((stat) => !Number.isFinite(slot.actualStats[stat]));
}

async function repairStatsSlotsWithStatOcr(file, slots, options) {
  if (!options.python) return;
  const jobs = [];
  const cropDir = path.join(options.outputDir || DEFAULT_OUTPUT_DIR, 'fallback_crops', 'stats', path.parse(file).name);
  for (const slot of slots) {
    for (const stat of missingActualStats(slot)) {
      const cropFile = path.join(cropDir, `slot${slot.slotIndex}.${stat}.actual.png`);
      await writeStatCrop(file, slot.card, STAT_CELLS[stat], cropFile, 6);
      jobs.push({ slot, stat, cropFile });
    }
  }
  if (!jobs.length) return;

  const rapid = await runRapidOcrBatch(options.python, jobs.map((job) => job.cropFile));
  const missedJobs = [];
  for (let index = 0; index < jobs.length; index++) {
    const job = jobs[index];
    const cropLines = normalizeOcrLines(rapid.images[index]?.lines || []);
    const value = pickActualNumberFromCrop(cropLines, job.stat);
    if (!Number.isFinite(value)) {
      missedJobs.push(job);
      continue;
    }
    applyRecoveredActualStat(job, value, 'rapidocr_stat_crop_6x');
  }

  if (!missedJobs.length) return;

  const thresholdJobs = [];
  for (const job of missedJobs) {
    const cropFile = job.cropFile.replace(/\.png$/i, '.threshold120.png');
    await sharp(job.cropFile, { limitInputPixels: false })
      .threshold(120)
      .png()
      .toFile(cropFile);
    thresholdJobs.push({ ...job, cropFile });
  }

  const thresholdRapid = await runRapidOcrBatch(options.python, thresholdJobs.map((job) => job.cropFile));
  for (let index = 0; index < thresholdJobs.length; index++) {
    const job = thresholdJobs[index];
    const cropLines = normalizeOcrLines(thresholdRapid.images[index]?.lines || []);
    const value = pickActualNumberFromCrop(cropLines, job.stat);
    if (!Number.isFinite(value)) continue;
    applyRecoveredActualStat(job, value, 'rapidocr_stat_crop_6x_threshold120');
  }
}

function applyRecoveredActualStat(job, value, source) {
  if (!Number.isFinite(value)) return;
    job.slot.actualStats[job.stat] = value;
    job.slot.ocrFallbacks.push({
      field: `actualStats.${job.stat}`,
      value,
      source,
    });
    updateStatsWarnings(job.slot);
}

async function writeStatCrop(file, card, region, cropFile, scale) {
  const expanded = expandRegion(region, 0.02, 0.02);
  const left = Math.max(0, Math.round(card.left + card.width * expanded[0]));
  const top = Math.max(0, Math.round(card.top + card.height * expanded[2]));
  const right = Math.round(card.left + card.width * expanded[1]);
  const bottom = Math.round(card.top + card.height * expanded[3]);
  const width = Math.max(1, right - left);
  const height = Math.max(1, bottom - top);

  await fs.mkdir(path.dirname(cropFile), { recursive: true });
  await sharp(file, { limitInputPixels: false })
    .extract({ left, top, width, height })
    .resize({
      width: width * scale,
      height: height * scale,
      fit: 'fill',
      kernel: 'lanczos3',
    })
    .grayscale()
    .normalize()
    .png()
    .toFile(cropFile);
}

function pickActualNumberFromCrop(lines, stat) {
  const candidates = numericCandidates(lines)
    .map((token) => ({ value: token.absValue, score: token.score }))
    .filter(({ value }) => isPlausibleActualStat(value, stat));
  const splitDigits = numericCandidates(lines)
    .filter((token) => /^\d$/.test(token.text))
    .sort((left, right) => left.cx - right.cx);
  if (splitDigits.length >= 2) {
    const value = Number(splitDigits.map((token) => token.text).join(''));
    if (isPlausibleActualStat(value, stat)) {
      candidates.push({
        value,
        score: splitDigits.reduce((sum, token) => sum + token.score, 0) / splitDigits.length,
      });
    }
  }
  candidates.sort((a, b) => Number(b.value > 32) - Number(a.value > 32) || b.score - a.score || b.value - a.value);
  return candidates[0]?.value;
}

function isPlausibleActualStat(value, stat) {
  if (!Number.isFinite(value)) return false;
  if (value > 999) return false;
  return stat === 'hp' ? value >= 1 : value >= 10;
}

function expandRegion(region, x, y) {
  return [
    Math.max(0, region[0] - x),
    Math.min(1, region[1] + x),
    Math.max(0, region[2] - y),
    Math.min(1, region[3] + y),
  ];
}

function pickField(lines, card, field, lookup) {
  const entityType = field === 'species'
    ? 'species'
    : field === 'ability'
      ? 'ability'
      : field === 'item'
        ? 'item'
        : 'move';
  const candidates = lines
    .map((line) => ({ line, pos: relativeCenter(line, card), resolved: resolveEntity(line.text, entityType, lookup) }))
    .filter(({ pos, resolved }) => resolved && isMoveItemFieldPosition(field, pos))
    .sort((a, b) => b.resolved.confidence * b.line.score - a.resolved.confidence * a.line.score);
  return candidates[0] ? toFieldCandidate(candidates[0]) : undefined;
}

function pickStatsSpecies(lines, card, lookup) {
  const candidates = lines
    .map((line) => ({ line, pos: relativeCenter(line, card), resolved: resolveEntity(line.text, 'species', lookup) }))
    .filter(({ pos, resolved }) => resolved && pos.rx < 0.55 && pos.ry < 0.25)
    .sort((a, b) => b.resolved.confidence * b.line.score - a.resolved.confidence * a.line.score);
  return candidates[0] ? toFieldCandidate(candidates[0]) : undefined;
}

function isMoveItemFieldPosition(field, pos) {
  if (field === 'species') return pos.rx < 0.58 && pos.ry < 0.27;
  if (field === 'ability') return pos.rx < 0.58 && pos.ry >= 0.25 && pos.ry < 0.52;
  if (field === 'item') return pos.rx < 0.58 && pos.ry >= 0.5 && pos.ry < 0.78;
  if (field.startsWith('move')) {
    if (pos.rx < 0.55) return false;
    const moveIndex = Number(field.replace('move', ''));
    const row = pos.ry < 0.29 ? 0 : pos.ry < 0.52 ? 1 : pos.ry < 0.75 ? 2 : 3;
    return row === moveIndex;
  }
  return false;
}

function pickNumber(lines, card, region, options = {}) {
  const [minRx, maxRx, minRy, maxRy] = region;
  const candidates = [];
  for (const token of numericCandidates(lines)) {
    const pos = relativeCenter(token, card);
    if (pos.rx < minRx || pos.rx > maxRx || pos.ry < minRy || pos.ry > maxRy) continue;
    const value = token.absValue;
    if (options.kind === 'points' && (value < 0 || value > 32)) continue;
    if (options.kind === 'actual' && (value > 999 || (options.stat === 'hp' ? value < 1 : value < 10))) continue;
    candidates.push({ value, score: token.score, distance: distanceToRegionCenter(pos, region) });
  }
  candidates.sort((a, b) => b.score - a.score || a.distance - b.distance);
  return candidates[0]?.value;
}

function numericCandidates(lines) {
  const tokens = [];
  for (const line of lines) {
    const sourceTokens = line.words?.length ? line.words : [line];
    for (const token of sourceTokens) {
      for (const candidate of splitNumericToken(token)) tokens.push(candidate);
    }
  }
  return tokens;
}

function splitNumericToken(token) {
  const matches = [...token.text.matchAll(/[+-]?\d+/g)];
  if (!matches.length) return [];

  return matches.map((match) => {
    const rawText = match[0];
    const value = Number(rawText);
    const splitCx = estimateSplitCenter(token, match, matches.length);
    return {
      ...token,
      text: rawText,
      rawTokenText: token.text,
      signedValue: value,
      absValue: Math.abs(value),
      cx: splitCx,
      cy: token.cy,
      score: token.score,
    };
  }).filter((candidate) => Number.isFinite(candidate.signedValue));
}

function estimateSplitCenter(token, match, matchCount) {
  if (matchCount <= 1 || !token.rect?.width || !token.text.length) return token.cx;
  const start = match.index ?? 0;
  const end = start + match[0].length;
  const ratio = clamp01((start + end) / (2 * token.text.length));
  return token.rect.left + token.rect.width * ratio;
}

function distanceToRegionCenter(pos, region) {
  const cx = (region[0] + region[1]) / 2;
  const cy = (region[2] + region[3]) / 2;
  return Math.hypot(pos.rx - cx, pos.ry - cy);
}

function toFieldCandidate({ line, resolved }) {
  return {
    rawText: line.text,
    score: line.score,
    entityType: resolved.entityType,
    canonicalId: resolved.canonicalId,
    showdownId: resolved.showdownId,
    displayName: resolved.displayName,
    confidence: clamp01(line.score * resolved.confidence),
  };
}

function normalizeOcrLines(lines) {
  return lines.map((line) => {
    const rect = boxToRect(line.box);
    const words = (line.words || [])
      .filter((word) => word.box)
      .map((word) => {
        const wordRect = boxToRect(word.box);
        return {
          text: normalizeText(word.text),
          score: word.score,
          box: word.box,
          rect: wordRect,
          cx: wordRect.left + wordRect.width / 2,
          cy: wordRect.top + wordRect.height / 2,
        };
      })
      .filter((word) => word.text);
    return {
      text: normalizeText(line.text),
      score: line.score,
      box: line.box,
      rect,
      cx: rect.left + rect.width / 2,
      cy: rect.top + rect.height / 2,
      words,
    };
  }).filter((line) => line.text);
}

function normalizeText(text) {
  return String(text || '').normalize('NFKC').replace(/\s+/g, '');
}

function linesInCard(lines, card) {
  const padX = card.width * 0.02;
  const padY = card.height * 0.04;
  return lines.filter(
    (line) =>
      line.cx >= card.left - padX &&
      line.cx <= card.left + card.width + padX &&
      line.cy >= card.top - padY &&
      line.cy <= card.top + card.height + padY
  );
}

function relativeCenter(line, card) {
  return {
    rx: (line.cx - card.left) / card.width,
    ry: (line.cy - card.top) / card.height,
  };
}

function boxToRect(box) {
  const xs = box.map((point) => point[0]);
  const ys = box.map((point) => point[1]);
  const left = Math.min(...xs);
  const top = Math.min(...ys);
  const right = Math.max(...xs);
  const bottom = Math.max(...ys);
  return { left, top, width: right - left, height: bottom - top };
}

async function detectTeamCards(file) {
  const maxWidth = 900;
  const metadata = await sharp(file, { limitInputPixels: false }).metadata();
  const scale = Math.min(1, maxWidth / metadata.width);
  const { data, info } = await sharp(file, { limitInputPixels: false })
    .resize({ width: Math.round(metadata.width * scale) })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const width = info.width;
  const height = info.height;
  const mask = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const offset = (y * width + x) * 4;
      const [hue, saturation, value] = rgbToHsv(data[offset], data[offset + 1], data[offset + 2]);
      if (data[offset + 3] >= 8 && hue >= 220 && hue <= 280 && saturation >= 0.18 && value >= 0.2) {
        mask[y * width + x] = 1;
      }
    }
  }

  const components = connectedComponents(mask, width, height)
    .map((component) => ({
      left: Math.round(component.left / scale),
      top: Math.round(component.top / scale),
      width: Math.round(component.width / scale),
      height: Math.round(component.height / scale),
      area: Math.round(component.area / (scale * scale)),
    }))
    .filter(
      (component) =>
        component.area > metadata.width * metadata.height * 0.003 &&
        component.width > metadata.width * 0.15 &&
        component.width < metadata.width * 0.48 &&
        component.height > metadata.height * 0.05 &&
        component.height < metadata.height * 0.25
    )
    .sort((a, b) => b.area - a.area)
    .slice(0, SLOT_COUNT);

  if (components.length !== SLOT_COUNT) {
    throw new Error(`Expected 6 team cards in ${file}, found ${components.length}.`);
  }
  return sortCardsBySlot(components);
}

function sortCardsBySlot(cards) {
  const medianHeight = [...cards].sort((a, b) => a.height - b.height)[Math.floor(cards.length / 2)].height;
  const rows = [];
  for (const card of [...cards].sort((a, b) => (a.top + a.height / 2) - (b.top + b.height / 2))) {
    const centerY = card.top + card.height / 2;
    let row = rows.find((candidate) => Math.abs(candidate.centerY - centerY) < medianHeight * 0.45);
    if (!row) {
      row = { centerY, cards: [] };
      rows.push(row);
    }
    row.cards.push(card);
    row.centerY = row.cards.reduce((sum, item) => sum + item.top + item.height / 2, 0) / row.cards.length;
  }
  return rows
    .sort((a, b) => a.centerY - b.centerY)
    .flatMap((row) => row.cards.sort((a, b) => a.left - b.left));
}

function connectedComponents(mask, width, height) {
  const visited = new Uint8Array(mask.length);
  const queue = [];
  const components = [];
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
    for (let cursor = 0; cursor < queue.length; cursor++) {
      const index = queue[cursor];
      const x = index % width;
      const y = Math.floor(index / width);
      area++;
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x);
      bottom = Math.max(bottom, y);
      push(index - 1, x > 0);
      push(index + 1, x < width - 1);
      push(index - width, y > 0);
      push(index + width, y < height - 1);
    }
    components.push({
      area,
      left,
      top,
      width: right - left + 1,
      height: bottom - top + 1,
    });
  }
  return components;

  function push(index, valid) {
    if (!valid || !mask[index] || visited[index]) return;
    visited[index] = 1;
    queue.push(index);
  }
}

function rgbToHsv(r, g, b) {
  r /= 255;
  g /= 255;
  b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  let hue = 0;
  const delta = max - min;
  if (delta) {
    if (max === r) hue = (g - b) / delta + (g < b ? 6 : 0);
    else if (max === g) hue = (b - r) / delta + 2;
    else hue = (r - g) / delta + 4;
    hue *= 60;
  }
  const saturation = max === 0 ? 0 : delta / max;
  return [hue, saturation, max];
}

function extractTeamId(lines) {
  const line = lines.find((item) => /队伍ID[:：]/.test(item.text));
  return line?.text.replace(/^.*队伍ID[:：]/, '') || undefined;
}

function createLookup(entries) {
  const byType = new Map();
  for (const entry of entries) {
    const localized = entry.localizedNames?.['zh-Hans'] || [];
    const names = [...localized, ...(entry.aliases || []), entry.englishName, entry.showdownId].filter(Boolean);
    for (const name of names) {
      const entityType = entry.entityType;
      if (!byType.has(entityType)) byType.set(entityType, []);
      byType.get(entityType).push({
        normalizedName: normalizeLookupText(name),
        exact: localized.includes(name),
        entry,
        displayName: localized[0] || entry.englishName || entry.showdownId,
      });
    }
  }
  return byType;
}

function resolveEntity(text, entityType, lookup) {
  const normalized = normalizeLookupText(text);
  if (!normalized) return undefined;
  const candidates = [];
  for (const item of lookup.get(entityType) || []) {
    const confidence = scoreCandidate(normalized, item);
    if (confidence <= 0) continue;
    candidates.push({
      entityType,
      canonicalId: item.entry.canonicalId,
      showdownId: item.entry.showdownId,
      displayName: item.displayName,
      confidence,
    });
  }
  candidates.sort((a, b) => b.confidence - a.confidence);
  return candidates[0];
}

function normalizeLookupText(value) {
  return String(value || '')
    .normalize('NFKC')
    .replace(/\s+/g, '')
    .replace(/[·・]/g, '')
    .replace(/[’']/g, '')
    .toLowerCase();
}

function scoreCandidate(input, item) {
  if (input === item.normalizedName) return item.exact ? 1 : 0.96;
  if (item.normalizedName.includes(input) || input.includes(item.normalizedName)) return 0.82;
  return levenshteinWithinOne(input, item.normalizedName) ? 0.74 : 0;
}

function levenshteinWithinOne(left, right) {
  if (Math.abs(left.length - right.length) > 1) return false;
  let edits = 0;
  let leftIndex = 0;
  let rightIndex = 0;
  while (leftIndex < left.length && rightIndex < right.length) {
    if (left[leftIndex] === right[rightIndex]) {
      leftIndex++;
      rightIndex++;
      continue;
    }
    edits++;
    if (edits > 1) return false;
    if (left.length > right.length) leftIndex++;
    else if (right.length > left.length) rightIndex++;
    else {
      leftIndex++;
      rightIndex++;
    }
  }
  return true;
}

function createSavedOwnTeam({ teamName, generatedAt, moveItemResults, statsResults }) {
  const statsResult = selectBestExtraction(statsResults);
  const moveItemResult = selectBestMoveItemExtraction(moveItemResults, statsResult?.teamId);
  if (!statsResult) return undefined;

  const resolvedTeamName = teamName || statsResult.teamId || path.parse(statsResult.image.path).name;
  const savedTeamId = createSavedTeamId(resolvedTeamName, generatedAt);
  const members = statsResult.slots.map((statsSlot) => {
    const moveItemSlot = findMoveItemSlot(moveItemResult, statsSlot);
    return createSavedTeamMember(statsSlot, moveItemSlot, statsResult, moveItemResult);
  });
  const warnings = members.flatMap((member) =>
    member.warnings.map((warning) => `slot${member.slotIndex}: ${warning}`)
  );
  const hasCoreStats = members.length === SLOT_COUNT && members.every((member) =>
    member.species && hasCompleteStats(member.actualStats)
  );
  const damageReady = hasCoreStats && members.every((member) => member.moves.length > 0);

  if (!moveItemResult) {
    warnings.push('No move/item page was merged; saved builds contain species and actualStats only.');
  }

  return {
    schemaVersion: 1,
    kind: 'SavedOwnTeam',
    savedTeamId,
    teamName: resolvedTeamName,
    gameTeamId: statsResult.teamId || moveItemResult?.teamId,
    status: hasCoreStats ? 'COMPLETE_ACTUAL_STATS' : 'INCOMPLETE',
    damageReady,
    generatedAt,
    source: {
      backend: 'rapidocr',
      statsImage: statsResult.image.path,
      moveItemImage: moveItemResult?.image.path,
    },
    members,
    warnings,
  };
}

function createSavedTeamMember(statsSlot, moveItemSlot, statsResult, moveItemResult) {
  const warnings = [];
  const species = toEntityRef(statsSlot.species || moveItemSlot?.species, 'species');
  const ability = toEntityRef(moveItemSlot?.ability, 'ability');
  const item = toEntityRef(moveItemSlot?.item, 'item');
  const moves = (moveItemSlot?.moves || [])
    .map((move) => toEntityRef(move, 'move'))
    .filter(Boolean);
  const actualStats = cleanStats(statsSlot.actualStats);

  if (!species) warnings.push('Missing species.');
  for (const stat of STAT_IDS) {
    if (!Number.isFinite(actualStats[stat])) warnings.push(`Missing actualStats.${stat}.`);
  }
  if (moveItemSlot?.species && statsSlot.species && !sameEntity(moveItemSlot.species, statsSlot.species)) {
    warnings.push(`Move/item species '${moveItemSlot.species.displayName}' does not match stats species '${statsSlot.species.displayName}'.`);
  }
  if (!moves.length) warnings.push('No moves were merged for this slot.');

  return {
    slotIndex: statsSlot.slotIndex,
    species,
    level: DEFAULT_LEVEL,
    actualStats,
    ability,
    item,
    moves,
    build: {
      species,
      level: DEFAULT_LEVEL,
      actualStats,
      ability,
      item,
      moves: moves.map((move) => ({ move, source: 'OWN_BUILD' })),
    },
    source: {
      statsImage: statsResult.image.path,
      moveItemImage: moveItemResult?.image.path,
    },
    warnings,
  };
}

function selectBestExtraction(results) {
  return [...results].sort((a, b) =>
    b.recognition.rate - a.recognition.rate ||
    b.recognition.recognized - a.recognition.recognized ||
    a.image.path.localeCompare(b.image.path, 'zh-Hans-CN')
  )[0];
}

function selectBestMoveItemExtraction(results, teamId) {
  const candidates = teamId
    ? results.filter((result) => result.teamId === teamId)
    : results;
  return selectBestExtraction(candidates.length ? candidates : results);
}

function findMoveItemSlot(moveItemResult, statsSlot) {
  if (!moveItemResult) return undefined;
  if (statsSlot.species) {
    const speciesMatch = moveItemResult.slots.find((slot) =>
      slot.species && sameEntity(slot.species, statsSlot.species)
    );
    if (speciesMatch) return speciesMatch;
  }
  return moveItemResult.slots.find((slot) => slot.slotIndex === statsSlot.slotIndex);
}

function sameEntity(left, right) {
  return Boolean(
    left &&
    right &&
    (left.canonicalId && left.canonicalId === right.canonicalId ||
      left.showdownId && left.showdownId === right.showdownId)
  );
}

function toEntityRef(candidate, entityType) {
  if (!candidate) return undefined;
  return {
    entityType,
    canonicalId: candidate.canonicalId,
    showdownId: candidate.showdownId,
    displayName: candidate.displayName,
    originalText: candidate.rawText,
    confidence: candidate.confidence,
    source: 'ocr',
  };
}

function cleanStats(stats) {
  const cleaned = {};
  for (const stat of STAT_IDS) {
    if (Number.isFinite(stats?.[stat])) cleaned[stat] = stats[stat];
  }
  return cleaned;
}

function hasCompleteStats(stats) {
  return STAT_IDS.every((stat) => Number.isFinite(stats?.[stat]));
}

function createSavedTeamId(teamName, generatedAt) {
  const base = sanitizeFileSegment(teamName) || 'own-team';
  const stamp = generatedAt.replace(/[-:.TZ]/g, '').slice(0, 14);
  return `${base}-${stamp}`;
}

function sanitizeFileSegment(value) {
  return String(value || '')
    .normalize('NFKC')
    .replace(/[<>:"/\\|?*\x00-\x1F]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 80);
}

function countMoveItemFields(slots) {
  const total = slots.length * 7;
  const recognized = slots.reduce(
    (count, slot) =>
      count +
      Number(Boolean(slot.species)) +
      Number(Boolean(slot.ability)) +
      Number(Boolean(slot.item)) +
      slot.moves.filter(Boolean).length,
    0
  );
  return { recognized, total, rate: recognized / total };
}

function countStatsFields(slots) {
  const total = slots.length * 7;
  const recognized = slots.reduce(
    (count, slot) =>
      count +
      Number(Boolean(slot.species)) +
      STAT_IDS.reduce(
        (sum, stat) => sum + Number(Number.isFinite(slot.actualStats[stat])),
        0
      ),
    0
  );
  return { recognized, total, rate: recognized / total };
}

function summarizeMoveItem(results) {
  const total = results.reduce((sum, result) => sum + result.recognition.total, 0);
  const recognized = results.reduce((sum, result) => sum + result.recognition.recognized, 0);
  return { recognized, total, rate: total ? recognized / total : 0 };
}

function summarizeStats(results) {
  const total = results.reduce((sum, result) => sum + result.recognition.total, 0);
  const recognized = results.reduce((sum, result) => sum + result.recognition.recognized, 0);
  return { recognized, total, rate: total ? recognized / total : 0 };
}

async function imageInfo(file) {
  const metadata = await sharp(file, { limitInputPixels: false }).metadata();
  return {
    path: file,
    width: metadata.width,
    height: metadata.height,
  };
}

async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function printSummary(summary) {
  console.log(`RapidOCR own-team extraction`);
  console.log(`move_item: ${summary.moveItem.recognized}/${summary.moveItem.total} (${(summary.moveItem.rate * 100).toFixed(1)}%)`);
  console.log(`stats:     ${summary.stats.recognized}/${summary.stats.total} (${(summary.stats.rate * 100).toFixed(1)}%)`);
  console.log(`output:    ${summary.outputs.outputDir}`);
  if (summary.outputs.savedTeamFile) {
    console.log(`team file: ${summary.outputs.savedTeamFile}`);
    console.log(`team:      ${summary.savedTeam.teamName} (${summary.savedTeam.status}, damageReady=${summary.savedTeam.damageReady})`);
  }
}

function clamp01(value) {
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
