import { common } from '@kit.AbilityKit';
import pcbridge, { NativeResult, ReplayStats } from 'libpcbridge.so';
import {
  HARMONY_REPLAY_PROFILE,
  ReplaySessionStateMachine,
  replayUsesRecognition,
  replayUsesRecording
} from '../domain/ReplaySession';
import { takeStage9VerificationMode } from '../storage/Stage9VerificationLaunch';

@Entry
@Component
struct Stage9Verification {
  @State status: string = 'RUNNING';

  aboutToAppear(): void {
    this.run();
  }

  private run(): void {
    const mode = takeStage9VerificationMode();
    try {
      if (mode === 'routes') {
        if (!replayUsesRecognition('RECOGNIZE_AND_RECORD') || !replayUsesRecording('RECOGNIZE_AND_RECORD') ||
          !replayUsesRecognition('RECOGNIZE_ONLY') || replayUsesRecording('RECOGNIZE_ONLY') ||
          replayUsesRecognition('RECORD_ONLY') || !replayUsesRecording('RECORD_ONLY')) {
          throw new Error('three-mode routing contract failed');
        }
        const state = new ReplaySessionStateMachine('RECORD_ONLY');
        state.beginPreparing('/data/replay.mp4');
        state.started();
        state.beginStopping();
        if (state.stopped(true).state !== 'READY_TO_PUBLISH') throw new Error('record-only state machine failed');
        this.status = 'PASS routes';
        return;
      }
      if (mode !== 'profile') throw new Error('invalid mode');
      const context = getContext(this) as common.UIAbilityContext;
      const path = `${context.filesDir}/stage9-codec-prepare.mp4`;
      const prepared: NativeResult = pcbridge.prepareReplayCapture(path, 2772, 1240, false, true);
      const stats: ReplayStats = pcbridge.getReplayStats();
      if (!prepared.ok && prepared.code === 1 && stats.message === 'H.264 encoder is unavailable' &&
        !stats.prepared && stats.fileBytes === 0 && stats.videoWidth === HARMONY_REPLAY_PROFILE.width &&
        stats.videoHeight === HARMONY_REPLAY_PROFILE.height && stats.videoFps === HARMONY_REPLAY_PROFILE.framesPerSecond &&
        !stats.recognitionEnabled && stats.audioEnabled) {
        pcbridge.stopCapture();
        this.status = 'PASS profile-emulator-blocked';
        return;
      }
      if (!prepared.ok || !stats.prepared || stats.running || stats.recognitionEnabled || !stats.audioEnabled ||
        stats.videoWidth !== HARMONY_REPLAY_PROFILE.width || stats.videoHeight !== HARMONY_REPLAY_PROFILE.height ||
        stats.videoFps !== HARMONY_REPLAY_PROFILE.framesPerSecond || stats.videoCodec !== 'video/avc' ||
        stats.audioCodec !== 'audio/mp4a-latm' || stats.audioSampleRate !== 48000 || stats.audioChannels !== 2) {
        throw new Error(`codec profile mismatch: ${JSON.stringify(stats)}`);
      }
      pcbridge.stopCapture();
      const stopped: ReplayStats = pcbridge.getReplayStats();
      if (stopped.prepared || stopped.running || stopped.fileBytes !== 0) {
        throw new Error(`prepare cleanup failed: ${JSON.stringify(stopped)}`);
      }
      this.status = 'PASS profile';
    } catch (error) {
      pcbridge.stopCapture();
      this.status = `FAIL ${String(error)}`;
    }
  }

  build() {
    Column({ space: 12 }) {
      Text('Stage 9 verification').fontSize(22).fontWeight(FontWeight.Bold)
      Text(this.status).id('stage9-verification-status').fontSize(16)
    }.width('100%').height('100%').justifyContent(FlexAlign.Center)
  }
}
