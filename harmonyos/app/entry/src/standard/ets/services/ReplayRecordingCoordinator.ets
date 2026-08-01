import { common } from '@kit.AbilityKit';
import { ReplayStats } from 'libpcbridge.so';
import { ReplayLaunchMode, ReplaySessionSnapshot, ReplaySessionStateMachine } from '../domain/ReplaySession';

const DISABLED_MESSAGE: string = '标准版不包含录像功能';

export class ReplayRecordingCoordinator {
  private machine: ReplaySessionStateMachine = new ReplaySessionStateMachine();

  configure(_context: common.UIAbilityContext): void {}

  status(): string {
    return DISABLED_MESSAGE;
  }

  snapshot(): ReplaySessionSnapshot {
    return this.machine.snapshot();
  }

  nativeStats(): ReplayStats {
    return {
      ok: false, code: -1, message: DISABLED_MESSAGE,
      prepared: false, running: false, finalized: false, failed: true,
      paused: false, failureOutputRetained: false,
      audioEnabled: false, recognitionEnabled: false,
      videoInputFrames: 0, videoEncodedFrames: 0, videoDroppedFrames: 0,
      audioInputBuffers: 0, audioDroppedBuffers: 0, audioEncodedBuffers: 0,
      nonSilentSamples: 0, audioPeak: 0, durationUs: 0, fileBytes: 0,
      videoWidth: 0, videoHeight: 0, videoFps: 0, videoBitrate: 0,
      audioSampleRate: 0, audioChannels: 0, audioBitrate: 0,
      videoCodec: '', audioCodec: '', filePath: ''
    };
  }

  isRecording(): boolean { return false; }
  hasPendingRecording(): boolean { return false; }
  canRetrySilently(): boolean { return false; }
  silentFallbackNeeded(): boolean { return false; }

  selectWindow(): string { throw new Error(DISABLED_MESSAGE); }
  startCaptureSession(_mode: ReplayLaunchMode, _audioEnabled: boolean = true): string {
    throw new Error(DISABLED_MESSAGE);
  }
  startRecordingOnCurrentCapture(_audioEnabled: boolean = true): string { throw new Error(DISABLED_MESSAGE); }
  continueSilently(): string { throw new Error(DISABLED_MESSAGE); }
  cancelRecording(): string { return DISABLED_MESSAGE; }
  async stopAndPublish(): Promise<string> { throw new Error(DISABLED_MESSAGE); }
  async publishPending(): Promise<string> { throw new Error(DISABLED_MESSAGE); }
  cancel(): void {}
}

export const replayRecordingCoordinator: ReplayRecordingCoordinator = new ReplayRecordingCoordinator();
