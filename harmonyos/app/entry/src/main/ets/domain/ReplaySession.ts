export type ReplayLaunchMode = 'RECOGNIZE_AND_RECORD' | 'RECOGNIZE_ONLY' | 'RECORD_ONLY';
export type ReplaySessionState = 'IDLE' | 'PREPARING' | 'RUNNING' | 'STOPPING' | 'READY_TO_PUBLISH' | 'FAILED';

export interface ReplayProfile {
  videoCodec: string;
  audioCodec: string;
  width: number;
  height: number;
  framesPerSecond: number;
  videoBitrate: number;
  audioSampleRate: number;
  audioChannels: number;
  audioBitrate: number;
  microphoneEnabled: boolean;
}

export interface ReplaySessionSnapshot {
  mode: ReplayLaunchMode;
  state: ReplaySessionState;
  recognitionEnabled: boolean;
  recordingEnabled: boolean;
  filePath?: string;
  error?: string;
}

export const HARMONY_REPLAY_PROFILE: ReplayProfile = {
  videoCodec: 'video/avc',
  audioCodec: 'audio/mp4a-latm',
  width: 960,
  height: 540,
  framesPerSecond: 24,
  videoBitrate: 1_500_000,
  audioSampleRate: 48_000,
  audioChannels: 2,
  audioBitrate: 96_000,
  microphoneEnabled: false
};

export const HARMONY_REPLAY_VIDEO_PROFILES: ReplayProfile[] = [
  HARMONY_REPLAY_PROFILE,
  { ...HARMONY_REPLAY_PROFILE, width: 854, height: 480, framesPerSecond: 20, videoBitrate: 1_000_000 },
  { ...HARMONY_REPLAY_PROFILE, width: 640, height: 360, framesPerSecond: 20, videoBitrate: 750_000 }
];

export function replayUsesRecognition(mode: ReplayLaunchMode): boolean {
  return mode !== 'RECORD_ONLY';
}

export function replayUsesRecording(mode: ReplayLaunchMode): boolean {
  return mode !== 'RECOGNIZE_ONLY';
}

export class ReplaySessionStateMachine {
  private current: ReplaySessionSnapshot;

  constructor(mode: ReplayLaunchMode = 'RECOGNIZE_ONLY') {
    this.current = this.initial(mode);
  }

  private initial(mode: ReplayLaunchMode): ReplaySessionSnapshot {
    return { mode, state: 'IDLE', recognitionEnabled: replayUsesRecognition(mode),
      recordingEnabled: replayUsesRecording(mode) };
  }

  snapshot(): ReplaySessionSnapshot {
    return { ...this.current };
  }

  select(mode: ReplayLaunchMode): ReplaySessionSnapshot {
    if (this.current.state !== 'IDLE' && this.current.state !== 'FAILED' &&
      this.current.state !== 'READY_TO_PUBLISH') throw new Error('运行中的会话不能切换模式');
    this.current = this.initial(mode);
    return this.snapshot();
  }

  beginPreparing(filePath?: string): ReplaySessionSnapshot {
    if (this.current.state !== 'IDLE') throw new Error('会话尚未回到空闲状态');
    if (this.current.recordingEnabled && (!filePath || filePath.trim().length === 0)) {
      throw new Error('录屏模式需要应用私有输出路径');
    }
    this.current = { ...this.current, state: 'PREPARING', filePath, error: undefined };
    return this.snapshot();
  }

  started(): ReplaySessionSnapshot {
    if (this.current.state !== 'PREPARING') throw new Error('只有准备中的会话可以开始');
    this.current = { ...this.current, state: 'RUNNING' };
    return this.snapshot();
  }

  beginStopping(): ReplaySessionSnapshot {
    if (this.current.state !== 'RUNNING') throw new Error('只有运行中的会话可以停止');
    this.current = { ...this.current, state: 'STOPPING' };
    return this.snapshot();
  }

  stopped(recordingFinalized: boolean): ReplaySessionSnapshot {
    if (this.current.state !== 'STOPPING') throw new Error('只有收尾中的会话可以结束');
    const state: ReplaySessionState = this.current.recordingEnabled && recordingFinalized ?
      'READY_TO_PUBLISH' : 'IDLE';
    this.current = { ...this.current, state };
    return this.snapshot();
  }

  published(): ReplaySessionSnapshot {
    if (this.current.state !== 'READY_TO_PUBLISH') throw new Error('没有待发布的录像');
    const mode = this.current.mode;
    this.current = this.initial(mode);
    return this.snapshot();
  }

  fail(message: string): ReplaySessionSnapshot {
    this.current = { ...this.current, state: 'FAILED', error: message };
    return this.snapshot();
  }
}

let selectedReplayMode: ReplayLaunchMode = 'RECOGNIZE_ONLY';

export function setSelectedReplayMode(mode: ReplayLaunchMode): void {
  selectedReplayMode = mode;
}

export function getSelectedReplayMode(): ReplayLaunchMode {
  return selectedReplayMode;
}
