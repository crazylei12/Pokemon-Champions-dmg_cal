import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const sourceRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main');
const replaySourceRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'replay');

async function loadDomain() {
  const output = await build({
    entryPoints: [path.join(sourceRoot, 'ets', 'domain', 'ReplaySession.ts')],
    bundle: true, format: 'esm', platform: 'neutral', target: 'es2021', write: false, logLevel: 'silent'
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

const domainPromise = loadDomain();

test('three replay modes gate recognition and recording independently', async () => {
  const domain = await domainPromise;
  assert.equal(domain.replayUsesRecognition('RECOGNIZE_AND_RECORD'), true);
  assert.equal(domain.replayUsesRecording('RECOGNIZE_AND_RECORD'), true);
  assert.equal(domain.replayUsesRecognition('RECOGNIZE_ONLY'), true);
  assert.equal(domain.replayUsesRecording('RECOGNIZE_ONLY'), false);
  assert.equal(domain.replayUsesRecognition('RECORD_ONLY'), false);
  assert.equal(domain.replayUsesRecording('RECORD_ONLY'), true);
  assert.deepEqual(domain.HARMONY_REPLAY_PROFILE, {
    videoCodec: 'video/avc', audioCodec: 'audio/mp4a-latm', width: 960, height: 540,
    framesPerSecond: 24, videoBitrate: 1_500_000, audioSampleRate: 48_000,
    audioChannels: 2, audioBitrate: 96_000, microphoneEnabled: false
  });
  assert.deepEqual(domain.HARMONY_REPLAY_VIDEO_PROFILES.map((profile) =>
    [profile.width, profile.height, profile.framesPerSecond, profile.videoBitrate]), [
    [960, 540, 24, 1_500_000], [854, 480, 20, 1_000_000], [640, 360, 20, 750_000]
  ]);
});

test('replay state machine requires a private file, monotonic transitions and explicit publication', async () => {
  const domain = await domainPromise;
  const recording = new domain.ReplaySessionStateMachine('RECORD_ONLY');
  assert.throws(() => recording.beginPreparing(), /私有输出路径/);
  recording.beginPreparing('/private/replay.mp4');
  recording.started();
  assert.throws(() => recording.select('RECOGNIZE_ONLY'), /不能切换模式/);
  recording.beginStopping();
  assert.equal(recording.stopped(true).state, 'READY_TO_PUBLISH');
  assert.equal(recording.published().state, 'IDLE');

  const recognition = new domain.ReplaySessionStateMachine('RECOGNIZE_ONLY');
  recognition.beginPreparing();
  recognition.started();
  recognition.beginStopping();
  assert.equal(recognition.stopped(false).state, 'IDLE');
});

test('single raw capture session fans out one full-resolution frame to recognition and the recorder', async () => {
  const [bridge, recorder, header] = await Promise.all([
    readFile(path.join(sourceRoot, 'cpp', 'napi_init.cpp'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'replay_recorder.cpp'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'replay_recorder.h'), 'utf8')
  ]);
  assert.match(bridge, /config\.dataType = OH_ORIGINAL_STREAM/);
  assert.match(bridge, /std::make_shared<std::vector<uint8_t>>/);
  assert.match(bridge, /g_recorder\.EnqueueVideo\(candidate, width, height, timestamp\)/);
  assert.match(bridge, /OH_AVBuffer_GetNativeBuffer/);
  assert.match(bridge, /OH_NativeBuffer_GetConfig/);
  assert.match(bridge, /SetCaptureContentChangedCallback/);
  assert.match(bridge, /SetPrivacyProtectCallback/);
  assert.match(bridge, /OH_SCREEN_CAPTURE_STATE_ENTER_PRIVATE_SCENE/);
  assert.match(bridge, /g_recorder\.SetCapturePaused/);
  assert.match(bridge, /StrategyForPrivacyMaskMode\(strategy, 0\)/);
  assert.match(bridge, /InvalidateLatestFrame/);
  assert.match(bridge, /if \(!g_session\.recognitionEnabled\.load\(\)\) return/);
  assert.match(bridge, /OH_SCREEN_CAPTURE_BUFFERTYPE_AUDIO_INNER/);
  assert.doesNotMatch(bridge, /OH_SCREEN_CAPTURE_BUFFERTYPE_AUDIO_MIC[\s\S]*EnqueueAudio/);
  assert.match(recorder, /ConvertRgbaToLetterboxedNv12/);
  assert.match(recorder, /OH_VideoEncoder_CreateByMime\(OH_AVCODEC_MIMETYPE_VIDEO_AVC\)/);
  assert.match(recorder, /OH_AudioCodec_CreateByMime\(OH_AVCODEC_MIMETYPE_AUDIO_AAC, true\)/);
  assert.match(recorder, /OH_AVMuxer_Create\(outputFd_, AV_OUTPUT_FORMAT_MPEG_4\)/);
  assert.match(recorder, /VIDEO_PROFILES\[\]/);
  assert.match(recorder, /no H\.264 encoder accepted replay profiles/);
  assert.match(recorder, /AVCODEC_BUFFER_FLAGS_EOS/);
  assert.match(recorder, /NormalizeTimestampUs\(captureTimestampUs\)/);
  assert.match(recorder, /O_EXCL \| O_RDWR \| O_CLOEXEC \| O_NOFOLLOW/);
  assert.doesNotMatch(recorder, /O_TRUNC/);
  assert.match(recorder, /audioDroppedBuffers_/);
  assert.match(recorder, /MIN_REPLAY_FREE_BYTES/);
  assert.match(recorder, /CleanupFailurePreservingOutput/);
  assert.match(recorder, /DecideReplayCleanup/);
  assert.match(recorder, /preserveFailedOutput/);
  assert.match(recorder, /decision\.removePrivateOutput/);
  assert.match(recorder, /unlink\(filePath_\.c_str\(\)\)/);
  assert.match(header, /REPLAY_VIDEO_WIDTH = 960/);
  assert.match(header, /REPLAY_VIDEO_HEIGHT = 540/);
  assert.match(header, /REPLAY_VIDEO_FPS = 24/);
});

test('replay controls stay integrated with Android-parity assistant surfaces without an invented launcher', async () => {
  const [index, floatUi, hudUi, ability, pages, service, standardService, profile, entryProfile, types, cmake,
    buildScript] = await Promise.all([
    readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'FloatAssistant.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'BattleHudElement.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'entryability', 'EntryAbility.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'resources', 'base', 'profile', 'main_pages.json'), 'utf8'),
    readFile(path.join(replaySourceRoot, 'ets', 'services', 'ReplayRecordingCoordinator.ets'), 'utf8'),
    readFile(path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'standard', 'ets', 'services',
      'ReplayRecordingCoordinator.ets'), 'utf8'),
    readFile(path.join(repositoryRoot, 'harmonyos', 'app', 'build-profile.json5'), 'utf8'),
    readFile(path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'build-profile.json5'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'types', 'libpcbridge', 'index.d.ts'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'CMakeLists.txt'), 'utf8'),
    readFile(path.join(repositoryRoot, 'tools', 'harmonyos', 'build-app.ps1'), 'utf8')
  ]);
  assert.match(index, /启动对局助手（HUD版）/);
  assert.match(index, /“开始录屏”和“结束录屏并保存 MP4”是独立操作/);
  assert.match(floatUi, /float-replay-toggle/);
  assert.match(floatUi, /float-replay-continue-silent/);
  assert.match(floatUi, /startRecordingOnCurrentCapture/);
  assert.match(hudUi, /recordingLabel/);
  assert.match(hudUi, /startRecordingOnCurrentCapture/);
  assert.match(hudUi, /继续无声录制/);
  assert.doesNotMatch(ability, /ReplayLaunch/);
  assert.doesNotMatch(pages, /ReplayLaunch/);
  assert.doesNotMatch(`${index}\n${floatUi}\n${hudUi}`, /replay-mode-(?:combined|recognition|record-only)/);
  assert.match(service, /showAssetsCreationDialog/);
  assert.match(service, /fileIo\.unlinkSync\(sourcePath\)/);
  assert.match(service, /MediaAssetChangeRequest\.deleteAssets/);
  assert.match(service, /requestPermissionsFromUser\(this\.requireContext\(\), \[WRITE_IMAGEVIDEO_PERMISSION\]\)/);
  assert.match(service, /permissionResult\.authResults\[0\] !== 0/);
  assert.match(service, /cleanupFailedReplay/);
  assert.match(service, /stats\.paused/);
  assert.match(service, /私有目录/);
  assert.match(service, /destinationBytes !== sourceBytes/);
  assert.match(service, /canRetrySilently/);
  assert.match(service, /silentFallbackNeeded/);
  assert.match(service, /!stats\.paused && stats\.audioEnabled && stats\.durationUs >= 5_000_000/);
  assert.match(service, /stats\.audioInputBuffers === 0/);
  assert.doesNotMatch(service, /stats\.nonSilentSamples === 0/);
  assert.match(service, /continueSilently/);
  assert.match(profile, /"REPLAY_ENABLED": false/);
  assert.match(profile, /"REPLAY_ENABLED": true/);
  assert.match(entryProfile, /"name": "standard"[\s\S]*-DPC_REPLAY_ENABLED=OFF/);
  assert.match(entryProfile, /"name": "replay"[\s\S]*-DPC_REPLAY_ENABLED=ON/);
  const entryBuild = JSON.parse(entryProfile);
  const standardPages = entryBuild.targets.find((target) => target.name === 'standard').source.pages;
  const replayPages = entryBuild.targets.find((target) => target.name === 'replay').source.pages;
  assert.equal(standardPages.some((page) => page.includes('Stage')), false);
  assert.equal(replayPages.some((page) => page.includes('Stage')), false);
  assert.equal(standardPages.includes('pages/BattleHudRecording'), false);
  assert.equal(replayPages.includes('pages/BattleHudRecording'), true);
  assert.equal(entryBuild.targets.find((target) => target.name === 'standard').source.sourceRoots, undefined);
  assert.equal(entryBuild.targets.find((target) => target.name === 'replay').source.sourceRoots, undefined);
  assert.doesNotMatch(standardService, /getReplayStats|prepareReplay|photoAccessHelper|MediaAsset/);
  assert.match(buildScript, /Write-VariantReplayCoordinator -VariantName \$currentVariant/);
  assert.match(buildScript, /entry\\src\\\$VariantName\\ets\\services\\ReplayRecordingCoordinator\.ets/);
  assert.match(buildScript, /Remove-Item -LiteralPath \$materializedReplayCoordinatorPath -Force/);
  for (const marker of ['prepareReplayCapture', 'prepareReplayRecorder', 'startReplayRecorder',
    'stopReplayRecorder', 'cancelReplayRecorder', 'stopReplayCapture']) assert.match(types, new RegExp(marker));
  for (const library of ['libnative_media_venc.so', 'libnative_media_acodec.so',
    'libnative_media_avmuxer.so']) assert.match(cmake, new RegExp(library.replace('.', '\\.')));
  assert.match(cmake, /option\(PC_REPLAY_ENABLED/);
  assert.match(cmake, /if\(PC_REPLAY_ENABLED\)[\s\S]*replay_recorder\.cpp/);
  assert.match(cmake, /target_compile_options\(pcbridge PRIVATE -Wall -Wextra -Werror/);
  assert.match(cmake, /-Wno-unused-command-line-argument/);
  assert.doesNotMatch(cmake, /D:\/HarmonyOS/);
});

test('formal replay verification checks product gates but never accepts privacy prompts', async () => {
  const verification = await readFile(path.join(toolDirectory, 'verify-stage9-replay-ui.ps1'), 'utf8');
  assert.match(verification, /verify-formal-ui-smoke\.ps1/);
  assert.match(verification, /Variant standard/);
  assert.match(verification, /Variant replay/);
  assert.match(verification, /BLOCKED_REAL_CAPTURE_CODEC_AND_MEDIA/);
  assert.doesNotMatch(verification, /uiInput[^\n]*(allow|允许|保存到相册)|startCapture\(/i);
});
