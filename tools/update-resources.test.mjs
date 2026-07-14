import assert from 'node:assert/strict';
import test from 'node:test';

import {
  parseArgs,
  summarizeDatasetManifest,
  summarizeIconCoverage,
  summarizeLocalizationCoverage,
} from './update-resources.mjs';

test('parseArgs returns interactive mode when no task flags are provided', () => {
  assert.deepEqual(parseArgs([]), {mode: 'interactive', tasks: [], dryRun: false});
});

test('parseArgs expands all into common resource update tasks', () => {
  assert.deepEqual(parseArgs(['--all', '--dry-run']), {
    mode: 'batch',
    tasks: ['localization', 'iconsCatalog', 'iconsDownload', 'visionDataset', 'visionEvaluate'],
    dryRun: true,
  });
});

test('parseArgs maps individual flags in caller order', () => {
  assert.deepEqual(parseArgs(['--smogon', '--icons-catalog', '--vision-dataset']), {
    mode: 'batch',
    tasks: ['smogon', 'iconsCatalog', 'visionDataset'],
    dryRun: false,
  });
});

test('parseArgs returns help mode for help flags', () => {
  assert.deepEqual(parseArgs(['--help']), {mode: 'help', tasks: [], dryRun: false});
});

test('summarizeLocalizationCoverage reports total missing mappings', () => {
  const text = summarizeLocalizationCoverage({
    missing: {
      species: ['A'],
      move: [],
      ability: ['B', 'C'],
    },
  });
  assert.match(text, /缺失映射 3/);
  assert.match(text, /species=1/);
  assert.match(text, /ability=2/);
});

test('summarizeIconCoverage reports important coverage buckets', () => {
  const text = summarizeIconCoverage({
    counts: {
      unmapped: 1,
      userTemplateRequired: 2,
      shinyTemplateMissing: 3,
    },
    fallbackUsed: [{showdownId: 'Example'}],
  });
  assert.match(text, /unmapped=1/);
  assert.match(text, /templateRequired=2/);
  assert.match(text, /shinyMissing=3/);
  assert.match(text, /fallbackUsed=1/);
});

test('summarizeDatasetManifest reports reference and label counts', () => {
  const text = summarizeDatasetManifest(
    {
      references: {count: 10},
      realTrain: {imageCount: 2, labelCount: 24},
      realTest: {imageCount: 0},
    },
    {references: [{}, {}]},
  );
  assert.match(text, /references=10/);
  assert.match(text, /manifestReferences=2/);
  assert.match(text, /trainImages=2/);
  assert.match(text, /labels=24/);
});
