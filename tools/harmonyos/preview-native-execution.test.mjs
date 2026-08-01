import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import sharp from 'sharp';

const execFileAsync = promisify(execFile);
const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const target = process.env.HARMONYOS_TARGET ?? '127.0.0.1:5557';
const maxBuffer = 64 * 1024 * 1024;

async function run(file, args, timeout = 60_000) {
  return execFileAsync(file, args, { encoding: 'utf8', maxBuffer, timeout, windowsHide: true });
}

async function runRemote(hdc, remoteDirectory, argumentsList, timeout = 60_000) {
  assert.match(remoteDirectory, /^\/data\/local\/tmp\/pc-preview-e2-\d+$/,
    'remote cleanup boundary must remain an exact generated directory');
  const executable = `${remoteDirectory}/team_preview_native_runner`;
  const command = [
    `LD_LIBRARY_PATH=${remoteDirectory}`,
    executable,
    ...argumentsList,
    '; status=$?; echo __PC_EXIT__:$status; exit $status'
  ].join(' ');
  const result = await run(hdc, ['-t', target, 'shell', command], timeout);
  const marker = result.stdout.match(/__PC_EXIT__:(\d+)/);
  assert.ok(marker, `native command did not publish an exit marker:\n${result.stdout}\n${result.stderr}`);
  assert.equal(Number(marker[1]), 0, `native command failed:\n${result.stdout}\n${result.stderr}`);
  return result.stdout.split(/\r?\n/).map((line) => line.trim()).filter((line) => line.startsWith('{'));
}

function normalizeCandidates(result) {
  return {
    ownTeamCandidates: result.ownTeamCandidates,
    opponentTeamCandidates: result.opponentTeamCandidates
  };
}

async function sha256(file) {
  return createHash('sha256').update(await readFile(file)).digest('hex');
}

function verifyRecognition(result, expectedWidth, expectedHeight) {
  assert.equal(result.kind, 'TeamPreviewRecognitionResult');
  assert.equal(result.backend, 'harmonyos_opencv_4.13.0');
  assert.deepEqual(result.imageSize, { width: expectedWidth, height: expectedHeight });
  assert.equal(result.ownTeamCandidates.length, 6);
  assert.equal(result.opponentTeamCandidates.length, 6);
  assert.equal(result.performance.slots.length, 12);
  assert.ok(result.performance.eligibleTemplateEvaluations > result.performance.refinedTemplateEvaluations);
  assert.ok(result.performance.refinedTemplateEvaluations > 0);

  const slots = [...result.ownTeamCandidates, ...result.opponentTeamCandidates];
  for (const slot of slots) {
    assert.equal(slot.candidates.length, 3, `${slot.roiId} must publish Top-3`);
    assert.deepEqual(slot.selectedCandidate, slot.candidates[0], `${slot.roiId} Top-1 must be selected`);
    assert.equal(new Set(slot.candidates.map((candidate) => candidate.canonicalId)).size, 3,
      `${slot.roiId} candidates must be deduplicated by canonical id`);
    assert.ok(slot.candidates[0].score >= slot.candidates[1].score);
    assert.ok(slot.candidates[1].score >= slot.candidates[2].score);

    for (const candidate of slot.candidates) {
      assert.ok(Math.abs(candidate.confidence - Math.min(1, Math.max(0, candidate.score))) <= 0.000000500000001,
        'raw confidence must round to the published six-decimal score');
      assert.equal(typeof candidate.source, 'string');
      assert.equal(typeof candidate.visualVariant, 'string');
      assert.equal(typeof candidate.isShiny, 'boolean');
    }
    const expectedMargin = Math.max(slot.candidates[0].score - slot.candidates[1].score, 0);
    assert.ok(Math.abs(slot.candidates[0].scoreMargin - expectedMargin) <= 0.000002,
      `${slot.roiId} score margin must describe Top-1 versus Top-2`);
    assert.equal(slot.candidates[1].scoreMargin, 0);
    assert.equal(slot.candidates[2].scoreMargin, 0);
    assert.equal(slot.requiresConfirmation,
      slot.candidates[0].score < 0.90 || slot.candidates[0].scoreMargin < 0.035);
  }

  for (const slot of result.performance.slots) {
    assert.ok(slot.eligibleTemplates > slot.refinedTemplates, `${slot.roiId} must run coarse then refined ranking`);
    assert.ok(slot.refinedTemplates > 0, `${slot.roiId} must refine at least one template`);
    for (const signal of [
      'cropMs', 'featureMs', 'strictColorMaskMs', 'relaxedColorMaskMs', 'grabCutMaskMs',
      'maskSelectionMs', 'colorMaskQuality', 'rankMs'
    ]) {
      assert.ok(Number.isFinite(slot[signal]) && slot[signal] >= 0,
        `${slot.roiId} must publish a non-negative ${signal} preprocessing/ranking signal`);
    }
    assert.equal(typeof slot.adaptiveGrabCutFallback, 'boolean');
  }
}

test('Harmony x86 native team-preview engine executes boundaries and matches Android production golden for eight samples',
  { timeout: 120_000 }, async () => {
    const localConfig = JSON.parse(await readFile(
      path.join(repositoryRoot, 'config', 'harmonyos-local.json'), 'utf8'));
    const sampleManifest = JSON.parse(await readFile(
      path.join(repositoryRoot, 'config', 'harmonyos-phase7-album-samples.json'), 'utf8'));
    const evidenceDirectory = path.join(repositoryRoot, 'harmonyos', 'app', 'evidence',
      'team-preview-cross-platform');
    const provenance = JSON.parse(await readFile(path.join(evidenceDirectory, 'provenance.json'), 'utf8'));
    const androidGolden = path.join(evidenceDirectory, provenance.androidProduction.result);
    assert.equal(sampleManifest.samples.length, 8);

    const nativeRoot = path.join(localConfig.toolchainRoot, 'sdk', 'default', 'openharmony', 'native');
    const clang = path.join(nativeRoot, 'llvm', 'bin', 'clang++.exe');
    const hdc = path.join(localConfig.toolchainRoot, 'sdk', 'default', 'openharmony', 'toolchains', 'hdc.exe');
    const libcxx = path.join(nativeRoot, 'llvm', 'lib', 'x86_64-linux-ohos', 'libc++_shared.so');
    const cppDirectory = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'cpp');
    const opencvBuild = localConfig.opencv.buildRoots.x86_64;
    const opencvSource = localConfig.opencv.sourceRoot;
    const runnerSource = path.join(toolDirectory, 'native', 'team_preview_native_runner.cpp');
    const engineSource = path.join(cppDirectory, 'team_preview_engine.cpp');
    const templateAsset = path.join(repositoryRoot, 'src', 'data', 'recognition',
      'android', 'team-preview-templates-v2.bin');
    const sampleDirectory = path.resolve(repositoryRoot, sampleManifest.sourceDirectory);
    const temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), 'pc-preview-native-'));
    const executable = path.join(temporaryDirectory, 'team_preview_native_runner');
    const remoteDirectory = `/data/local/tmp/pc-preview-e2-${process.pid}${Date.now()}`;

    try {
      assert.equal(await sha256(path.join(repositoryRoot, 'config', 'harmonyos-phase7-album-samples.json')),
        provenance.fixedInput.manifestSha256);
      assert.equal(await sha256(androidGolden), provenance.androidProduction.resultSha256);
      assert.equal(await sha256(engineSource), provenance.harmonyProduction.workingTreeSourceSha256);
      const androidRoots = [
        path.resolve(repositoryRoot, '..', 'pokemon-champions-assistant-main-safe-area'),
        path.resolve(repositoryRoot, '..', 'pokemon-champions-assistant')
      ];
      const androidSourceFiles = {
        'TeamPreviewRecognition.kt': 'android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/TeamPreviewRecognition.kt',
        'TeamPreviewViewportMapping.kt': 'android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/TeamPreviewViewportMapping.kt',
        'CloseSafeSerialExecutor.kt': 'android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/CloseSafeSerialExecutor.kt',
        'AppStorage.kt': 'android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/AppStorage.kt',
        'team-preview-templates-v2.bin': 'src/data/recognition/android/team-preview-templates-v2.bin',
        'team-preview.safe-zone-roi.zh-Hans.v2.json': 'src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json'
      };
      for (const [name, relativeFile] of Object.entries(androidSourceFiles)) {
        const expected = provenance.androidProduction.sourceSha256[name];
        for (const root of androidRoots) assert.equal(await sha256(path.join(root, relativeFile)), expected,
          `${name} drifted from the Android production source used to create the golden`);
      }

      const targets = (await run(hdc, ['list', 'targets'])).stdout.split(/\r?\n/).map((line) => line.trim());
      assert.ok(targets.includes(target), `HarmonyOS target ${target} must be connected`);
      assert.equal((await run(hdc, ['-t', target, 'shell', 'uname', '-m'])).stdout.trim(), 'x86_64');
      assert.equal((await run(hdc, ['-t', target, 'shell', 'param', 'get',
        'const.ohos.apiversion'])).stdout.trim(), '24');

      await run(clang, [
        '--target=x86_64-linux-ohos', `--gcc-toolchain=${path.join(nativeRoot, 'llvm')}`,
        `--sysroot=${path.join(nativeRoot, 'sysroot')}`, '-std=c++17', '-O2', '-D__MUSL__',
        '-DPC_TEAM_PREVIEW_STANDALONE=1', '-Wall', '-Wextra', '-Werror',
        '-Wno-unused-command-line-argument', `-I${cppDirectory}`, `-I${opencvBuild}`,
        `-I${path.join(opencvSource, 'include')}`, `-I${path.join(opencvSource, 'modules', 'core', 'include')}`,
        `-I${path.join(opencvSource, 'modules', 'imgproc', 'include')}`, runnerSource, engineSource,
        path.join(opencvBuild, 'lib', 'libopencv_imgproc.a'),
        path.join(opencvBuild, 'lib', 'libopencv_core.a'),
        path.join(opencvBuild, '3rdparty', 'lib', 'libittnotify.a'),
        '--rtlib=compiler-rt', '-fuse-ld=lld', '-lunwind', '-lz', '-lm', '-o', executable
      ]);

      const rawFiles = [];
      for (let index = 0; index < sampleManifest.samples.length; index += 1) {
        const sample = sampleManifest.samples[index];
        const source = path.join(sampleDirectory, sample.name);
        const sourceBytes = await readFile(source);
        assert.equal((await stat(source)).size, sample.bytes, `${sample.name} byte count drifted`);
        assert.equal(createHash('sha256').update(sourceBytes).digest('hex'), sample.sha256,
          `${sample.name} hash drifted`);
        const { data, info } = await sharp(sourceBytes).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
        assert.deepEqual({ width: info.width, height: info.height, channels: info.channels },
          { width: sampleManifest.expectedWidth, height: sampleManifest.expectedHeight, channels: 4 });
        const rawFile = path.join(temporaryDirectory, `sample-${index}.rgba`);
        await writeFile(rawFile, data);
        rawFiles.push(rawFile);
      }

      await run(hdc, ['-t', target, 'shell', 'mkdir', '-p', remoteDirectory]);
      const remoteFiles = [executable, libcxx, templateAsset, ...rawFiles];
      for (const localFile of remoteFiles) {
        await run(hdc, ['-t', target, 'file', 'send', localFile,
          `${remoteDirectory}/${path.basename(localFile)}`]);
      }
      await run(hdc, ['-t', target, 'shell', 'chmod', '700', `${remoteDirectory}/${path.basename(executable)}`]);

      const policyLines = await runRemote(hdc, remoteDirectory, ['--self-test']);
      assert.equal(policyLines.length, 1);
      const policy = JSON.parse(policyLines[0]);
      assert.deepEqual(policy, {
        kind: 'TeamPreviewNativePolicyResult',
        checks: 16,
        failures: 0,
        covers: [
          'frame-validation', 'empty-roi', 'negative-dimensions', 'stale-generation',
          'rotation-dimensions', 'invalidated-frame', 'threshold-0.90-0.035'
        ]
      });

      const remoteTemplate = `${remoteDirectory}/${path.basename(templateAsset)}`;
      const remoteRawFiles = rawFiles.map((file) => `${remoteDirectory}/${path.basename(file)}`);
      const recognitionArguments = ['--recognize', remoteTemplate, String(sampleManifest.expectedWidth),
        String(sampleManifest.expectedHeight), ...remoteRawFiles];
      const firstRun = (await runRemote(hdc, remoteDirectory, recognitionArguments, 60_000)).map(JSON.parse);
      const secondRun = (await runRemote(hdc, remoteDirectory, recognitionArguments, 60_000)).map(JSON.parse);
      assert.equal(firstRun.length, sampleManifest.samples.length);
      assert.equal(secondRun.length, sampleManifest.samples.length);
      for (const result of firstRun) verifyRecognition(
        result, sampleManifest.expectedWidth, sampleManifest.expectedHeight);
      assert.ok(firstRun.some((result) => result.performance.slots.some((slot) => slot.grabCutMaskMs > 0)),
        'fixed samples must exercise GrabCut preprocessing');
      assert.deepEqual(secondRun.map(normalizeCandidates), firstRun.map(normalizeCandidates),
        'candidate ranking must be deterministic for the same fixed input corpus');
      const currentHarmonyOutput = path.join(temporaryDirectory, 'harmony-production-current.json');
      const currentHarmonyDocument = {
        schemaVersion: 1,
        kind: 'HarmonyTeamPreviewNativeResults',
        productionBackend: 'harmonyos_opencv_4.13.0',
        results: sampleManifest.samples.map((sample, index) => ({ sample: sample.name, result: firstRun[index] }))
      };
      await writeFile(currentHarmonyOutput, `${JSON.stringify(currentHarmonyDocument)}\n`, 'utf8');
      const comparison = await run(process.execPath, [
        path.join(toolDirectory, 'compare-preview-production-results.mjs'), androidGolden, currentHarmonyOutput
      ]);
      assert.deepEqual(JSON.parse(comparison.stdout.trim()), {
        samples: 8,
        totalSlots: 96,
        totalCandidates: 288,
        exactTop1: 96,
        exactOrderedTop3: 96,
        exactRankingSignals: 96,
        candidateNumbersWithinTolerance: 288,
        maximumConfidenceDelta: 1.1368665497890618e-7,
        maximumPublishedNumberDelta: 0.0000010000000000287557,
        mismatchCount: 0,
        pass: true
      });
      if (process.env.PC_PREVIEW_NATIVE_OUTPUT) {
        const outputPath = path.resolve(process.env.PC_PREVIEW_NATIVE_OUTPUT);
        await writeFile(outputPath, `${JSON.stringify(currentHarmonyDocument)}\n`, 'utf8');
      }
    } finally {
      if (/^\/data\/local\/tmp\/pc-preview-e2-\d+$/.test(remoteDirectory)) {
        await run(hdc, ['-t', target, 'shell', 'rm', '-rf', remoteDirectory]).catch(() => undefined);
      }
      await rm(temporaryDirectory, { recursive: true, force: true });
    }
  });
