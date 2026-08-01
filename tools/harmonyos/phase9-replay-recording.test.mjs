import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const sourceRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main');

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
    framesPerSecond: 24, videoBitrate: 4_000_000, audioSampleRate: 48_000,
    audioChannels: 2, audioBitrate: 128_000, microphoneEnabled: false
  });
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
  assert.match(bridge, /g_recorder\.EnqueueVideo\(candidate, g_session\.width, g_session\.height\)/);
  assert.match(bridge, /if \(!g_session\.recognitionEnabled\.load\(\)\) return/);
  assert.match(bridge, /OH_SCREEN_CAPTURE_BUFFERTYPE_AUDIO_INNER/);
  assert.doesNotMatch(bridge, /OH_SCREEN_CAPTURE_BUFFERTYPE_AUDIO_MIC[\s\S]*EnqueueAudio/);
  assert.match(recorder, /ConvertRgbaToLetterboxedNv12/);
  assert.match(recorder, /OH_VideoEncoder_CreateByMime\(OH_AVCODEC_MIMETYPE_VIDEO_AVC\)/);
  assert.match(recorder, /OH_AudioCodec_CreateByMime\(OH_AVCODEC_MIMETYPE_AUDIO_AAC, true\)/);
  assert.match(recorder, /OH_AVMuxer_Create\(outputFd_, AV_OUTPUT_FORMAT_MPEG_4\)/);
  assert.match(recorder, /AVCODEC_BUFFER_FLAGS_EOS/);
  assert.match(recorder, /unlink\(filePath_\.c_str\(\)\)/);
  assert.match(header, /REPLAY_VIDEO_WIDTH = 960/);
  assert.match(header, /REPLAY_VIDEO_HEIGHT = 540/);
  assert.match(header, /REPLAY_VIDEO_FPS = 24/);
});

test('record-only launch is product reachable without importing recognition, catalog or damage modules', async () => {
  const [launch, index, floatUi, ability, service, profile, types, cmake] = await Promise.all([
    readFile(path.join(sourceRoot, 'ets', 'pages', 'ReplayLaunch.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'FloatAssistant.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'entryability', 'EntryAbility.ets'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'services', 'ReplayRecordingCoordinator.ets'), 'utf8'),
    readFile(path.join(repositoryRoot, 'harmonyos', 'app', 'build-profile.json5'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'types', 'libpcbridge', 'index.d.ts'), 'utf8'),
    readFile(path.join(sourceRoot, 'cpp', 'CMakeLists.txt'), 'utf8')
  ]);
  for (const id of ['replay-mode-combined', 'replay-mode-recognition', 'replay-mode-record-only',
    'replay-record-only-start', 'replay-record-only-stop']) assert.match(launch, new RegExp(id));
  assert.doesNotMatch(launch,
    /(?:import|from).*?(?:RuntimeDataRepository|DamageEngineRuntime|OwnTeamRecognition|TeamPreviewRecognition|OpenCV)/);
  assert.match(ability, /if \(REPLAY_ENABLED\) this\.pagePath = 'pages\/ReplayLaunch'/);
  assert.match(index, /RECOGNIZE_AND_RECORD/);
  assert.match(floatUi, /float-replay-toggle/);
  assert.match(service, /showAssetsCreationDialog/);
  assert.match(service, /fileIo\.unlinkSync\(sourcePath\)/);
  assert.match(profile, /"REPLAY_ENABLED": false/);
  assert.match(profile, /"REPLAY_ENABLED": true/);
  for (const marker of ['prepareReplayCapture', 'prepareReplayRecorder', 'startReplayRecorder',
    'stopReplayRecorder', 'stopReplayCapture']) assert.match(types, new RegExp(marker));
  for (const library of ['libnative_media_venc.so', 'libnative_media_acodec.so',
    'libnative_media_avmuxer.so']) assert.match(cmake, new RegExp(library.replace('.', '\\.')));
});

test('automation verification prepares codecs but never starts capture or accepts privacy prompts', async () => {
  const verification = await readFile(path.join(sourceRoot, 'ets', 'pages', 'Stage9Verification.ets'), 'utf8');
  assert.match(verification, /prepareReplayCapture/);
  assert.match(verification, /pcbridge\.stopCapture\(\)/);
  assert.doesNotMatch(verification, /startCapture\(/);
  assert.doesNotMatch(verification, /click|tap|allow|允许|保存到相册/i);
});
