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
  startCapture(): NativeResult;
  presentWindowPicker(): NativeResult;
  getCaptureStats(): CaptureStats;
  takeLatestFrame(): CapturedFrame;
  recognizeTeamPreview(rgba: ArrayBuffer, width: number, height: number,
    templates: ArrayBuffer, capturedAt: string): Promise<string>;
  stopCapture(): NativeResult;
};

export default pcbridge;
