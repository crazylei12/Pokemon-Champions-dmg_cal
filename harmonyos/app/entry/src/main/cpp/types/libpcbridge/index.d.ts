export interface BridgeInfo {
  api: number;
  name: string;
  native: boolean;
  screenCapture: boolean;
}

export interface NativeResult {
  ok: boolean;
  code: number;
  message: string;
}

export interface CaptureStats extends NativeResult {
  prepared: boolean;
  running: boolean;
  hasStableFrame: boolean;
  videoFrames: number;
  acceptedFrames: number;
  rejectedFrames: number;
  width: number;
  height: number;
  stateCode: number;
  errorCode: number;
}

export interface ReplayStats extends NativeResult {
  prepared: boolean;
  running: boolean;
  finalized: boolean;
  failed: boolean;
  audioEnabled: boolean;
  recognitionEnabled: boolean;
  videoInputFrames: number;
  videoEncodedFrames: number;
  videoDroppedFrames: number;
  audioInputBuffers: number;
  audioEncodedBuffers: number;
  nonSilentSamples: number;
  audioPeak: number;
  durationUs: number;
  fileBytes: number;
  videoWidth: number;
  videoHeight: number;
  videoFps: number;
  videoBitrate: number;
  audioSampleRate: number;
  audioChannels: number;
  audioBitrate: number;
  videoCodec: string;
  audioCodec: string;
  filePath: string;
}

export interface CapturedFrame extends NativeResult {
  data?: ArrayBuffer;
  width: number;
  height: number;
  strideBytes?: number;
  hash?: number;
  timestampUs?: number;
  cards?: CaptureRect[];
}

export interface CaptureRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

declare const pcbridge: {
  getBridgeInfo(): BridgeInfo;
  prepareCapture(width: number, height: number): NativeResult;
  prepareReplayCapture(path: string, width: number, height: number,
    recognitionEnabled: boolean, audioEnabled: boolean): NativeResult;
  startCapture(): NativeResult;
  presentWindowPicker(): NativeResult;
  getCaptureStats(): CaptureStats;
  getReplayStats(): ReplayStats;
  prepareReplayRecorder(path: string, audioEnabled: boolean): ReplayStats;
  startReplayRecorder(): ReplayStats;
  stopReplayRecorder(): ReplayStats;
  cancelReplayRecorder(): ReplayStats;
  takeLatestFrame(): CapturedFrame;
  recognizeTeamPreview(rgba: ArrayBuffer, width: number, height: number,
    templates: ArrayBuffer, capturedAt: string): Promise<string>;
  stopCapture(): NativeResult;
  stopReplayCapture(): ReplayStats;
};

export default pcbridge;
