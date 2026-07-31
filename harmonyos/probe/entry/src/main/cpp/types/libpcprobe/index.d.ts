export interface NativeProbeResult {
  ok: boolean;
  code: number;
  message: string;
}

export interface TemplateProbeResult extends NativeProbeResult {
  magic: string;
  version: number;
  templateWidth: number;
  templateHeight: number;
  recordCount: number;
  matchScore: number;
}

export interface CaptureStats extends NativeProbeResult {
  prepared: boolean;
  running: boolean;
  recording: boolean;
  videoFrames: number;
  innerAudioBuffers: number;
  microphoneBuffers: number;
  firstVideoBytes: number;
  firstVideoHash: number;
  firstTimestampUs: number;
  lastTimestampUs: number;
  stateCode: number;
  errorCode: number;
  snapshotPending: boolean;
  snapshotBytes: number;
  snapshotHash: number;
  snapshotTimestampUs: number;
  frameStrideBytes: number;
  snapshotPath: string;
  width: number;
  height: number;
}

declare const pcprobe: {
  probeNativeCapture(): NativeProbeResult;
  probeOpenCvTemplate(path: string): TemplateProbeResult;
  probeOpenCvTemplateFd(fd: number, offset: number, length: number): TemplateProbeResult;
  prepareRawCapture(width: number, height: number): CaptureStats;
  prepareRawWindowCapture(missionId: number, width: number, height: number): CaptureStats;
  prepareFileRecording(path: string, width: number, height: number): CaptureStats;
  prepareFileWindowRecording(path: string, missionId: number, width: number, height: number): CaptureStats;
  presentWindowPicker(): CaptureStats;
  startPreparedCapture(): CaptureStats;
  requestFrameSnapshot(path: string): CaptureStats;
  stopCapture(): CaptureStats;
  getCaptureStats(): CaptureStats;
};

export default pcprobe;
