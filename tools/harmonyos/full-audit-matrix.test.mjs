import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { access, readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const planPath = path.join(repositoryRoot, 'docs', 'harmonyos_full_parity_audit_plan_zh.md');
const matrixPath = path.join(repositoryRoot, 'config', 'harmonyos-full-audit-matrix.json');
const evidenceCatalogPath = path.join(repositoryRoot, 'config', 'harmonyos-node-test-evidence.json');
const mappingPath = path.join(repositoryRoot, 'docs', 'harmonyos_shared_fix_and_evidence_mapping_zh.md');
const reportPath = path.join(repositoryRoot, 'docs', 'harmonyos_full_parity_audit_report_zh.md');
const emulatorManifestPath = path.join(repositoryRoot, 'config',
  'harmonyos-emulator-evidence-manifest.json');
const historicalAcceptancePath = path.join(repositoryRoot, 'config', 'harmonyos-phase10-acceptance.json');
const mainPagesPath = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main',
  'resources', 'base', 'profile', 'main_pages.json');
const productProfilePath = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'build-profile.json5');
const allowedStatuses = new Set(['PASS', 'FAIL', 'BLOCKED', 'NOT_APPLICABLE']);
const evidenceRank = new Map([
  ['NONE', -1],
  ['E0', 0],
  ['E1', 1],
  ['E2', 2],
  ['E3', 3],
  ['E4', 4],
  ['E5', 5]
]);

function planItems(markdown) {
  return [...markdown.matchAll(/^- \[ \] `([A-Z]+-[0-9]+)` (.+)$/gm)]
    .map((match) => ({ id: match[1], acceptance: match[2] }));
}

async function currentNodeTests() {
  const toolsPath = path.join(repositoryRoot, 'tools', 'harmonyos');
  const files = (await readdir(toolsPath))
    .filter((file) => file.endsWith('.test.mjs'))
    .sort((left, right) => left.localeCompare(right));
  const discovered = [];
  for (const file of files) {
    const source = await readFile(path.join(toolsPath, file), 'utf8');
    for (const match of source.matchAll(/^test\((['"])(.+?)\1/gm)) {
      discovered.push({
        file: `tools/harmonyos/${file}`,
        title: match[2]
      });
    }
  }
  return discovered;
}

test('full audit matrix covers each of the 220 plan IDs exactly once with terminal evidence-aware status', async () => {
  const [planMarkdown, matrixText, catalogText, mappingText, manifestText, discoveredTests] = await Promise.all([
    readFile(planPath, 'utf8'),
    readFile(matrixPath, 'utf8'),
    readFile(evidenceCatalogPath, 'utf8'),
    readFile(mappingPath, 'utf8'),
    readFile(emulatorManifestPath, 'utf8'),
    currentNodeTests()
  ]);
  const planned = planItems(planMarkdown);
  const matrix = JSON.parse(matrixText);
  const catalog = JSON.parse(catalogText);
  const manifest = JSON.parse(manifestText);

  assert.equal(planned.length, 220, 'the authoritative plan must still contain exactly 220 checklist items');
  assert.equal(matrix.schemaVersion, 1);
  assert.equal(matrix.deviceEvidenceAvailable, false,
    'do not silently upgrade E5 conclusions without a recorded ARM64 device run');
  assert.equal(matrix.releaseSigningAvailable, false,
    'the matrix must continue to expose the missing release signing material');
  assert.equal(matrix.items.length, 220);

  const plannedIds = planned.map((item) => item.id);
  const matrixIds = matrix.items.map((item) => item.id);
  assert.deepEqual(matrixIds, plannedIds, 'matrix IDs and order must exactly match the plan');
  assert.equal(new Set(matrixIds).size, 220, 'each plan ID must appear exactly once');

  const planById = new Map(planned.map((item) => [item.id, item]));
  const computedSummary = { PASS: 0, FAIL: 0, BLOCKED: 0, NOT_APPLICABLE: 0 };
  for (const item of matrix.items) {
    for (const field of [
      'id', 'domain', 'variants', 'status', 'requiredEvidence', 'currentEvidence',
      'evidence', 'blocker', 'retest'
    ]) {
      assert.ok(Object.hasOwn(item, field), `${item.id} is missing required field ${field}`);
    }
    assert.equal(item.domain, item.id.split('-')[0], `${item.id} has the wrong domain`);
    assert.ok(Array.isArray(item.variants) && item.variants.length > 0, `${item.id} needs variants`);
    assert.equal(new Set(item.variants).size, item.variants.length, `${item.id} repeats a variant`);
    assert.ok(item.variants.every((variant) => variant === 'standard' || variant === 'replay'),
      `${item.id} contains an unknown variant`);
    assert.ok(allowedStatuses.has(item.status), `${item.id} has non-terminal status ${item.status}`);
    assert.ok(evidenceRank.has(item.requiredEvidence), `${item.id} has invalid requiredEvidence`);
    assert.ok(evidenceRank.has(item.currentEvidence), `${item.id} has invalid currentEvidence`);
    assert.ok(Array.isArray(item.evidence) && item.evidence.length > 0,
      `${item.id} must record current evidence even when blocked`);
    assert.ok(item.evidence.every((entry) => typeof entry === 'string' && entry.trim().length > 0),
      `${item.id} has an empty evidence entry`);
    assert.equal(typeof item.blocker, 'string', `${item.id} needs a blocker string`);
    assert.equal(typeof item.retest, 'string', `${item.id} needs a retest string`);
    assert.ok(item.retest.trim().length > 0, `${item.id} needs an actionable retest`);
    assert.equal(item.acceptance, planById.get(item.id).acceptance,
      `${item.id} acceptance text drifted from the plan`);

    if (item.status === 'PASS') {
      assert.ok(evidenceRank.get(item.currentEvidence) >= evidenceRank.get(item.requiredEvidence),
        `${item.id} is PASS below its required evidence level`);
      assert.ok(evidenceRank.get(item.requiredEvidence) <= evidenceRank.get('E3'),
        `${item.id} must not claim E4/E5 PASS without the required interactive or ARM64 evidence`);
      assert.equal(item.blocker, '', `${item.id} is PASS but still records a blocker`);
      if (item.requiredEvidence === 'E2') {
        const evidenceText = item.evidence.join('\n');
        assert.match(evidenceText, /test|Phase|Node|测试|编译/i,
          `${item.id} is E2 PASS without executable-test or compilation evidence`);
        const localPaths = evidenceText.match(/(?:tools|docs|config|harmonyos)\/[A-Za-z0-9_.\/-]+/g) ?? [];
        for (const localPath of localPaths) {
          await access(path.join(repositoryRoot, localPath));
        }
      }
      if (item.requiredEvidence === 'E3') {
        assert.match(item.evidence.join('\n'), /模拟器|emulator|HAP|正式页面|UI hierarchy/i,
          `${item.id} is E3 PASS without current packaged-emulator evidence`);
      }
    } else if (item.status === 'BLOCKED') {
      assert.ok(item.blocker.trim().length > 0, `${item.id} is BLOCKED without a blocker`);
      assert.ok(evidenceRank.get(item.currentEvidence) < evidenceRank.get(item.requiredEvidence),
        `${item.id} has enough evidence and should not remain BLOCKED`);
    } else if (item.status === 'FAIL') {
      assert.ok(item.blocker.trim().length > 0, `${item.id} is FAIL without the confirmed gap`);
      assert.notEqual(item.currentEvidence, 'NONE', `${item.id} is FAIL without confirming evidence`);
    }
    computedSummary[item.status] += 1;
  }

  assert.deepEqual(matrix.summary, computedSummary, 'summary counts must match item statuses');
  assert.equal(computedSummary.PASS + computedSummary.FAIL + computedSummary.BLOCKED +
    computedSummary.NOT_APPLICABLE, 220);

  const currentHead = execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: repositoryRoot,
    encoding: 'utf8'
  }).trim();
  assert.equal(matrix.sourceState, 'COMMITTED_IMPLEMENTATION');
  assert.equal(matrix.sourceCommit, manifest.sourceCommit);
  assert.equal(catalog.sourceCommit, matrix.sourceCommit);
  assert.equal(catalog.sourceState, 'COMMITTED_IMPLEMENTATION');
  execFileSync('git', ['cat-file', '-e', `${matrix.sourceCommit}^{commit}`], { cwd: repositoryRoot });
  execFileSync('git', ['merge-base', '--is-ancestor', matrix.sourceCommit, currentHead], {
    cwd: repositoryRoot
  });
  const allowedAuditFiles = new Set([
    'config/harmonyos-emulator-evidence-manifest.json',
    'config/harmonyos-full-audit-matrix.json',
    'config/harmonyos-node-test-evidence.json',
    'docs/harmonyos_full_parity_audit_report_zh.md',
    'docs/harmonyos_shared_fix_and_evidence_mapping_zh.md',
    'tools/harmonyos/full-audit-matrix.test.mjs'
  ]);
  const postSnapshotChanges = execFileSync('git', [
    'diff', '--name-only', matrix.sourceCommit, currentHead
  ], { cwd: repositoryRoot, encoding: 'utf8' }).trim().split(/\r?\n/).filter(Boolean);
  assert.ok(postSnapshotChanges.every((file) => allowedAuditFiles.has(file)),
    `product files changed after the evidenced implementation: ${postSnapshotChanges.join(', ')}`);
  for (const commit of [
    matrix.sourceCommit,
    matrix.workingTreeEvidence.androidMainCommit,
    matrix.workingTreeEvidence.androidReplayCommit
  ]) {
    assert.ok(mappingText.includes(commit), `mapping snapshot omits commit ${commit}`);
  }

  const allowedEvidenceTypes = new Map([
    ['SOURCE_ASSERTION', 'E1'],
    ['STATIC_CONTRACT', 'E1'],
    ['LOGIC_EXECUTION', 'E2'],
    ['NATIVE_EXECUTION', 'E2'],
    ['FORMAL_UI', 'E4'],
    ['DEVICE_BLACK_BOX', 'E5']
  ]);
  assert.equal(catalog.tests.length, discoveredTests.length,
    'every current Node test must have one evidence classification');
  assert.equal(new Set(catalog.tests.map((entry) => entry.id)).size, catalog.tests.length);
  const catalogKeys = catalog.tests.map((entry) => `${entry.file}\0${entry.title}`).sort();
  const discoveredKeys = discoveredTests.map((entry) => `${entry.file}\0${entry.title}`).sort();
  assert.deepEqual(catalogKeys, discoveredKeys, 'Node evidence catalog drifted from current test declarations');
  const computedCatalogSummary = {};
  for (const entry of catalog.tests) {
    assert.ok(allowedEvidenceTypes.has(entry.evidenceType), `${entry.id} has unknown evidenceType`);
    assert.equal(entry.supportsMaxEvidence, allowedEvidenceTypes.get(entry.evidenceType),
      `${entry.id} overclaims its evidence type`);
    assert.equal(typeof entry.executesProductionLogic, 'boolean');
    computedCatalogSummary[entry.evidenceType] = (computedCatalogSummary[entry.evidenceType] ?? 0) + 1;
  }
  for (const evidenceType of allowedEvidenceTypes.keys()) {
    computedCatalogSummary[evidenceType] ??= 0;
  }
  assert.deepEqual(catalog.summary, computedCatalogSummary);
  assert.ok(catalog.summary.NATIVE_EXECUTION >= 1,
    'at least one Node test must compile, load and execute current C/C++ production logic');
  assert.equal(catalog.summary.FORMAL_UI, 0);
  assert.equal(catalog.summary.DEVICE_BLACK_BOX, 0,
    'Node declarations cannot manufacture formal UI or ARM64 black-box evidence');

  assert.equal(manifest.sourceTree, 'CLEAN_TRACKED_TREE');
  assert.equal(manifest.build.mode, 'debug');
  assert.equal(manifest.build.signed, false);
  assert.deepEqual(manifest.build.abis, ['arm64-v8a', 'x86_64']);
  assert.equal(manifest.emulator.result, 'PASS_WITH_E5_BLOCKERS');
  assert.equal(manifest.emulator.abi, 'x86_64');
  assert.equal(manifest.evidenceBoundary.privacyPromptsAutomated, false);
  for (const entry of manifest.evidence) {
    const bytes = await readFile(path.join(repositoryRoot, entry.path));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.sha256,
      `${entry.path} no longer matches the current evidence manifest`);
  }
});

test('E5 system integrations and missing signing material cannot be promoted by source evidence', async () => {
  const [matrixText, historicalText, catalogText, mainPagesText, productProfileText] = await Promise.all([
    readFile(matrixPath, 'utf8'),
    readFile(historicalAcceptancePath, 'utf8'),
    readFile(evidenceCatalogPath, 'utf8'),
    readFile(mainPagesPath, 'utf8'),
    readFile(productProfilePath, 'utf8')
  ]);
  const matrix = JSON.parse(matrixText);
  const historicalAcceptance = JSON.parse(historicalText);
  const catalog = JSON.parse(catalogText);
  const byId = new Map(matrix.items.map((item) => [item.id, item]));
  const requiredE5 = new Set([
    'BUILD-009', 'APP-004', 'PERM-003', 'PERM-004', 'PERM-005', 'PERM-006',
    'PERM-007', 'PERM-008', 'PERM-009', 'PERM-011', 'PERM-012',
    ...Array.from({ length: 12 }, (_, index) => `CAPTURE-${String(index + 1).padStart(3, '0')}`),
    'OWN-009', 'OWN-014', 'OWN-015', 'OWN-016',
    'PREVIEW-009', 'PREVIEW-012', 'PREVIEW-013', 'PREVIEW-014', 'PREVIEW-015',
    'TEAM-009', 'STORE-003', 'STORE-011', 'STORE-012',
    ...Array.from({ length: 8 }, (_, index) => `PANEL-${String(index + 1).padStart(3, '0')}`),
    ...Array.from({ length: 15 }, (_, index) => `HUD-${String(index + 1).padStart(3, '0')}`)
      .filter((id) => id !== 'HUD-004'),
    'UPDATE-008', 'UPDATE-009', 'UPDATE-010',
    ...Array.from({ length: 15 }, (_, index) => `REPLAY-${String(index + 1).padStart(3, '0')}`),
    'QUAL-003', 'QUAL-007', 'QUAL-008', 'QUAL-009', 'QUAL-010'
  ]);

  for (const id of requiredE5) {
    const item = byId.get(id);
    assert.ok(item, `missing E5 item ${id}`);
    assert.equal(item.requiredEvidence, 'E5', `${id} must require ARM64 device evidence`);
    assert.notEqual(item.status, 'PASS', `${id} cannot PASS without current E5 evidence`);
    assert.notEqual(item.currentEvidence, 'E5', `${id} fabricates device evidence`);
  }

  for (const id of ['BUILD-009', 'UPDATE-009']) {
    assert.equal(byId.get(id).status, 'BLOCKED', `${id} must remain blocked without release signing material`);
    assert.match(byId.get(id).blocker, /签名|证书/);
  }

  assert.equal(matrix.historicalEvidencePolicy, 'EXCLUDE_FROM_CURRENT_PASS');
  assert.notEqual(historicalAcceptance.sourceCommit, matrix.sourceCommit,
    'old Phase 10 acceptance must remain historical after remediation');
  assert.ok(historicalAcceptance.candidates.every((candidate) => candidate.signed === false));
  assert.ok(catalog.historicalEvidence.every((entry) => entry.use === 'HISTORICAL_ONLY'));
  const forbiddenCurrentEvidence = /bbdf55c|harmonyos-phase10-acceptance|\.tmp\/rotation-fix|release-unsigned\.hap/i;
  for (const item of matrix.items.filter((entry) => entry.status === 'PASS')) {
    assert.doesNotMatch(item.evidence.join('\n'), forbiddenCurrentEvidence,
      `${item.id} uses historical evidence for a current PASS`);
  }

  const formalProductText = `${mainPagesText}\n${productProfileText}`;
  assert.doesNotMatch(formalProductText, /pages\/Stage\d+Verification/,
    'production page lists must not expose Stage verification routes');
  assert.equal(catalog.productionStagePolicy.stagePagesInFormalProducts, false);
  assert.equal(catalog.productionStagePolicy.stageSourceRetainedForHistoricalDebugging, false);
});

test('final report and machine-readable matrix describe the same committed audit result', async () => {
  const [matrixText, reportText, manifestText] = await Promise.all([
    readFile(matrixPath, 'utf8'),
    readFile(reportPath, 'utf8'),
    readFile(emulatorManifestPath, 'utf8')
  ]);
  const matrix = JSON.parse(matrixText);
  const manifest = JSON.parse(manifestText);
  assert.ok(reportText.includes(matrix.sourceCommit));
  assert.ok(reportText.includes(`PASS：${matrix.summary.PASS}`));
  assert.ok(reportText.includes(`FAIL：${matrix.summary.FAIL}`));
  assert.ok(reportText.includes(`BLOCKED：${matrix.summary.BLOCKED}`));
  for (const artifact of manifest.build.artifacts) {
    assert.ok(reportText.includes(artifact.sha256));
  }
  assert.match(reportText, /缺少.*签名|签名.*缺少/);
  assert.match(reportText, /ARM64.*真机|真机.*ARM64/);
});
