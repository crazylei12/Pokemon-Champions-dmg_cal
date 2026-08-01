import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import pcbridge, { NativeResult, ReplayStats } from 'libpcbridge.so';
import {
  ReplayLaunchMode,
  ReplaySessionSnapshot,
  ReplaySessionStateMachine,
  replayUsesRecognition,
  replayUsesRecording
} from '../domain/ReplaySession';
import { OWN_TEAM_CAPTURE_HEIGHT, OWN_TEAM_CAPTURE_WIDTH } from './ScreenCaptureService';

export class ReplayRecordingCoordinator {
  private context?: common.UIAbilityContext;
  private machine: ReplaySessionStateMachine = new ReplaySessionStateMachine();
  private recordingOwnsCapture: boolean = false;
  private lastStartMode: ReplayLaunchMode = 'RECORD_ONLY';
  private lastStartUsedExistingCapture: boolean = false;
  private lastStartRequestedAudio: boolean = true;
  private statusText: string = '录像尚未开始';

  configure(context: common.UIAbilityContext): void {
    this.context = context;
  }

  status(): string {
    return this.statusText;
  }

  snapshot(): ReplaySessionSnapshot {
    return this.machine.snapshot();
  }

  nativeStats(): ReplayStats {
    return pcbridge.getReplayStats();
  }

  isRecording(): boolean {
    const stats: ReplayStats = pcbridge.getReplayStats();
    return stats.running || (stats.prepared && !stats.failed && !stats.finalized && stats.videoInputFrames > 0);
  }

  hasPendingRecording(): boolean {
    return this.machine.snapshot().state === 'READY_TO_PUBLISH' && this.nativeStats().finalized;
  }

  canRetrySilently(): boolean {
    const stats: ReplayStats = this.nativeStats();
    return this.lastStartRequestedAudio && stats.failed && stats.message.includes('AAC-LC');
  }

  silentFallbackNeeded(): boolean {
    const stats: ReplayStats = this.nativeStats();
    return stats.running && stats.audioEnabled && stats.videoInputFrames >= 12 &&
      (stats.audioInputBuffers === 0 || stats.nonSilentSamples === 0);
  }

  selectWindow(): string {
    const result: NativeResult = pcbridge.presentWindowPicker();
    if (!result.ok) throw new Error(`暂时无法打开窗口选择器（${result.code}）：${result.message}`);
    this.statusText = '请选择系统相册窗口；助手悬浮层不会进入单窗口录制内容';
    return this.statusText;
  }

  private requireContext(): common.UIAbilityContext {
    if (!this.context) throw new Error('录像服务尚未配置');
    return this.context;
  }

  private nextPrivatePath(): string {
    return `${this.requireContext().filesDir}/pokemon-champions-${Date.now()}.mp4`;
  }

  startCaptureSession(mode: ReplayLaunchMode, audioEnabled: boolean = true): string {
    if (this.hasPendingRecording()) throw new Error('请先保存已经完成的录像');
    const previous: ReplayStats = this.nativeStats();
    const session: ReplaySessionSnapshot = this.machine.snapshot();
    if (session.state === 'RUNNING' && session.recordingEnabled && previous.prepared &&
      !previous.running && previous.videoInputFrames === 0) {
      pcbridge.stopCapture();
      this.machine = new ReplaySessionStateMachine(mode);
      this.recordingOwnsCapture = false;
    }
    this.machine.select(mode);
    this.lastStartMode = mode;
    this.lastStartUsedExistingCapture = false;
    this.lastStartRequestedAudio = audioEnabled;
    const path = replayUsesRecording(mode) ? this.nextPrivatePath() : undefined;
    this.machine.beginPreparing(path);
    let prepared: NativeResult;
    if (replayUsesRecording(mode)) {
      prepared = pcbridge.prepareReplayCapture(path as string, OWN_TEAM_CAPTURE_WIDTH, OWN_TEAM_CAPTURE_HEIGHT,
        replayUsesRecognition(mode), audioEnabled);
    } else {
      prepared = pcbridge.prepareCapture(OWN_TEAM_CAPTURE_WIDTH, OWN_TEAM_CAPTURE_HEIGHT);
    }
    if (!prepared.ok) {
      this.machine.fail(`捕获准备失败（${prepared.code}）：${prepared.message}`);
      throw new Error(this.machine.snapshot().error);
    }
    const started: NativeResult = pcbridge.startCapture();
    if (!started.ok) {
      pcbridge.stopCapture();
      this.machine.fail(`无法启动系统画面会话（${started.code}）：${started.message}`);
      throw new Error(this.machine.snapshot().error);
    }
    this.recordingOwnsCapture = mode === 'RECORD_ONLY';
    this.machine.started();
    this.statusText = mode === 'RECORD_ONLY' ?
      (audioEnabled ? '已请求仅录屏授权；请选择相册窗口，录像不会加载识别和伤害运行时' :
        '已请求无声录屏授权；不会录制任何音轨') :
      (audioEnabled ? '已请求识别与录像授权；识别取原始帧，录像优先编码为 960×540 / 24 fps' :
        '识别与无声录像已启动；识别会话保持运行，MP4 不包含音轨');
    return this.statusText;
  }

  private startRecorderOnPreparedCapture(mode: ReplayLaunchMode, audioEnabled: boolean,
    ownsCapture: boolean): string {
    if (this.isRecording()) return this.statusText;
    if (this.hasPendingRecording()) throw new Error('请先保存已经完成的录像');
    const previous: ReplayStats = this.nativeStats();
    const session: ReplaySessionSnapshot = this.machine.snapshot();
    if (session.state === 'RUNNING' && session.recordingEnabled && previous.prepared &&
      !previous.running && previous.videoInputFrames === 0) {
      pcbridge.stopReplayRecorder();
      this.machine = new ReplaySessionStateMachine(mode);
      this.recordingOwnsCapture = false;
    }
    const path = this.nextPrivatePath();
    this.machine.select(mode);
    this.machine.beginPreparing(path);
    this.lastStartMode = mode;
    this.lastStartUsedExistingCapture = true;
    this.lastStartRequestedAudio = audioEnabled;
    const prepared: ReplayStats = pcbridge.prepareReplayRecorder(path, audioEnabled);
    if (!prepared.prepared || prepared.failed) {
      this.machine.fail(`录像准备失败（${prepared.code}）：${prepared.message}`);
      throw new Error(this.machine.snapshot().error);
    }
    const started: ReplayStats = pcbridge.startReplayRecorder();
    if (!started.running || started.failed) {
      pcbridge.stopReplayRecorder();
      this.machine.fail(`录像编码器启动失败（${started.code}）：${started.message}`);
      throw new Error(this.machine.snapshot().error);
    }
    this.recordingOwnsCapture = ownsCapture;
    this.machine.started();
    this.statusText = audioEnabled ?
      '录像已开始：优先 H.264 960×540 / 24 fps；只录制所选应用内部声音，不使用麦克风' :
      '无声录像已开始：识别会话保持运行，MP4 不包含音轨';
    return this.statusText;
  }

  startRecordingOnCurrentCapture(audioEnabled: boolean = true): string {
    return this.startRecorderOnPreparedCapture('RECOGNIZE_AND_RECORD', audioEnabled, false);
  }

  continueSilently(): string {
    const stats: ReplayStats = this.nativeStats();
    if (stats.running) {
      if (!stats.audioEnabled) return this.statusText;
      const mode = this.machine.snapshot().mode;
      const ownsCapture = this.recordingOwnsCapture;
      pcbridge.cancelReplayRecorder();
      this.machine = new ReplaySessionStateMachine(mode);
      return this.startRecorderOnPreparedCapture(mode, false, ownsCapture);
    }
    if (!this.canRetrySilently()) throw new Error('当前没有需要无声回退的录像');
    if (this.lastStartUsedExistingCapture) {
      this.machine = new ReplaySessionStateMachine(this.lastStartMode);
      return this.startRecorderOnPreparedCapture(this.lastStartMode, false, this.recordingOwnsCapture);
    }
    this.machine = new ReplaySessionStateMachine(this.lastStartMode);
    return this.startCaptureSession(this.lastStartMode, false);
  }

  cancelRecording(): string {
    if (this.recordingOwnsCapture) pcbridge.stopCapture();
    else pcbridge.cancelReplayRecorder();
    const mode = this.machine.snapshot().mode;
    this.machine = new ReplaySessionStateMachine(mode);
    this.recordingOwnsCapture = false;
    this.statusText = '本次录像已取消，未完成的私有文件已清理；识别数据不受影响';
    return this.statusText;
  }

  async stopAndPublish(): Promise<string> {
    const before: ReplayStats = this.nativeStats();
    const canFinalize: boolean = before.running ||
      (before.prepared && !before.failed && !before.finalized && before.videoInputFrames > 0);
    if (!canFinalize) throw new Error('当前没有可以收尾的录像');
    this.machine.beginStopping();
    const finalized: ReplayStats = this.recordingOwnsCapture ?
      pcbridge.stopReplayCapture() : pcbridge.stopReplayRecorder();
    if (!finalized.finalized || finalized.failed || finalized.videoEncodedFrames <= 0) {
      this.machine.fail(`录像收尾失败（${finalized.code}）：${finalized.message}`);
      throw new Error(this.machine.snapshot().error);
    }
    this.machine.stopped(true);
    this.statusText = '录像已完整收尾；请在系统界面决定是否保存到相册';
    return this.publishPending();
  }

  async publishPending(): Promise<string> {
    const snapshot = this.machine.snapshot();
    if (snapshot.state !== 'READY_TO_PUBLISH' || !snapshot.filePath) throw new Error('没有待保存的录像');
    const sourcePath = snapshot.filePath;
    const helper = photoAccessHelper.getPhotoAccessHelper(this.requireContext());
    const config: photoAccessHelper.PhotoCreationConfig = {
      title: `Pokemon Champions ${Date.now()}`,
      fileNameExtension: 'mp4',
      photoType: photoAccessHelper.PhotoType.VIDEO,
      subtype: photoAccessHelper.PhotoSubtype.DEFAULT
    };
    const destinationUris: string[] = await helper.showAssetsCreationDialog([sourcePath], [config]);
    if (destinationUris.length !== 1) {
      this.statusText = '系统没有返回相册目标；录像仍保留在应用私有目录，可再次保存';
      return this.statusText;
    }
    let source: fileIo.File | undefined;
    let destination: fileIo.File | undefined;
    try {
      source = await fileIo.open(sourcePath, fileIo.OpenMode.READ_ONLY);
      destination = await fileIo.open(destinationUris[0], fileIo.OpenMode.WRITE_ONLY);
      await fileIo.copyFile(source.fd, destination.fd);
    } finally {
      if (source) fileIo.closeSync(source);
      if (destination) fileIo.closeSync(destination);
    }
    fileIo.unlinkSync(sourcePath);
    this.machine.published();
    this.statusText = `录像已保存到系统相册：${destinationUris[0]}`;
    return this.statusText;
  }

  cancel(): void {
    pcbridge.stopCapture();
    const current = this.machine.snapshot();
    this.machine = new ReplaySessionStateMachine(current.mode);
    this.recordingOwnsCapture = false;
    this.statusText = '录像已取消，未完成的私有文件已清理';
  }
}

export const replayRecordingCoordinator: ReplayRecordingCoordinator = new ReplayRecordingCoordinator();
