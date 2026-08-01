import { ReplayLaunchMode, setSelectedReplayMode } from '../domain/ReplaySession';
import { replayRecordingCoordinator } from '../services/ReplayRecordingCoordinator';
import { common } from '@kit.AbilityKit';

const TEXT: string = '#17233A';
const MUTED: string = '#667085';
const ACCENT: string = '#167D6D';

@Entry
@Component
struct ReplayLaunch {
  @State recordOnlySelected: boolean = false;
  @State busy: boolean = false;
  @State message: string = '选择本次使用方式；稍后出现的录屏和保存确认均由系统界面处理。';
  @State recording: boolean = false;
  @State readyToPublish: boolean = false;
  @State silentFallbackAvailable: boolean = false;
  @State statsText: string = 'H.264 960×540 / 24 fps · AAC-LC 48 kHz 双声道 · 不使用麦克风';

  aboutToAppear(): void {
    replayRecordingCoordinator.configure(getContext(this) as common.UIAbilityContext);
  }

  private openAssistant(mode: ReplayLaunchMode): void {
    setSelectedReplayMode(mode);
    this.getUIContext().getRouter().replaceUrl({ url: 'pages/Index' });
  }

  private chooseRecordOnly(): void {
    setSelectedReplayMode('RECORD_ONLY');
    this.recordOnlySelected = true;
    this.message = '仅录屏不会进入完整助手，因此不会加载识别和伤害计算功能。';
  }

  private refreshStats(): void {
    const stats = replayRecordingCoordinator.nativeStats();
    this.recording = replayRecordingCoordinator.isRecording();
    this.readyToPublish = stats.finalized;
    this.silentFallbackAvailable = replayRecordingCoordinator.canRetrySilently() ||
      replayRecordingCoordinator.silentFallbackNeeded();
    this.statsText = `${stats.videoCodec} ${stats.videoWidth}×${stats.videoHeight} / ${stats.videoFps} fps` +
      (stats.audioEnabled ? ` · ${stats.audioCodec} · 仅应用内部声音 · 麦克风关闭` : ' · 无音轨');
  }

  private startRecordOnly(): void {
    if (this.busy) return;
    this.busy = true;
    try {
      this.message = replayRecordingCoordinator.startCaptureSession('RECORD_ONLY');
      this.refreshStats();
      setTimeout(() => {
        this.refreshStats();
        if (this.silentFallbackAvailable) {
          this.message = '暂未检测到所选应用的内部声音；请选择改为无声录制或取消本次录像。';
        }
      }, 1500);
    } catch (error) {
      this.message = `启动失败：${String(error)}`;
    } finally {
      this.refreshStats();
      this.busy = false;
    }
  }

  private continueSilently(): void {
    try { this.message = replayRecordingCoordinator.continueSilently(); }
    catch (error) { this.message = `无声回退失败：${String(error)}`; }
    finally { this.refreshStats(); }
  }

  private cancelRecording(): void {
    this.message = replayRecordingCoordinator.cancelRecording();
    this.refreshStats();
  }

  private selectWindow(): void {
    try { this.message = replayRecordingCoordinator.selectWindow(); }
    catch (error) { this.message = String(error); }
  }

  private async stopAndSave(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      this.message = await replayRecordingCoordinator.stopAndPublish();
    } catch (error) {
      this.message = `收尾或保存结果：${String(error)}`;
    } finally {
      this.refreshStats();
      this.busy = false;
    }
  }

  private async retrySave(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try { this.message = await replayRecordingCoordinator.publishPending(); }
    catch (error) { this.message = `保存结果：${String(error)}`; }
    finally { this.refreshStats(); this.busy = false; }
  }

  build() {
    Scroll() {
      Column({ space: 14 }) {
        Text('Pokémon Champions · 录屏功能版')
          .fontSize(24).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
        Text('录像与识别共享同一个系统授权的单应用画面源；录屏始终只捕获所选应用的播放声音。')
          .fontSize(14).fontColor(MUTED).width('100%')

        if (!this.recordOnlySelected) {
          Column({ space: 8 }) {
            Text('识别并录屏').fontSize(19).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
            Text('进入完整助手；启动对局助手时同时录像，录像不中断即可读取原分辨率识别帧。')
              .fontColor(MUTED).width('100%')
            Button('进入识别并录屏').id('replay-mode-combined').width('100%').backgroundColor(ACCENT)
              .onClick(() => this.openAssistant('RECOGNIZE_AND_RECORD'))
          }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)

          Column({ space: 8 }) {
            Text('仅识别').fontSize(19).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
            Text('行为与标准版一致，不创建 MP4；之后仍可从悬浮入口单独开始录像。')
              .fontColor(MUTED).width('100%')
            Button('进入仅识别').id('replay-mode-recognition').width('100%').backgroundColor('#8B5CF6')
              .onClick(() => this.openAssistant('RECOGNIZE_ONLY'))
          }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)

          Column({ space: 8 }) {
            Text('仅录屏').fontSize(19).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
            Text('停留在轻量页面，不加载识别、图鉴和伤害模块。')
              .fontColor(MUTED).width('100%')
            Button('进入仅录屏').id('replay-mode-record-only').width('100%').backgroundColor('#F4C542').fontColor(TEXT)
              .onClick(() => this.chooseRecordOnly())
          }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        } else {
          Column({ space: 10 }) {
            Text('仅录屏控制').fontSize(21).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
            Text(this.message).id('replay-record-only-status').fontColor(
              this.message.includes('失败') ? '#B42318' : MUTED).width('100%')
            Text(this.statsText).id('replay-record-only-stats').fontSize(13).fontColor(ACCENT).width('100%')
            if (!this.recording && !this.readyToPublish) {
              Button(this.busy ? '正在请求系统授权…' : '开始仅录屏').id('replay-record-only-start')
                .width('100%').enabled(!this.busy).backgroundColor(ACCENT).onClick(() => this.startRecordOnly())
            }
            if (this.recording) {
              Button('重新选择相册窗口').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
                .onClick(() => this.selectWindow())
              Button(this.busy ? '正在收尾…' : '结束录屏并保存到相册').id('replay-record-only-stop')
                .width('100%').enabled(!this.busy).backgroundColor('#B42318').onClick(() => this.stopAndSave())
            }
            if (this.silentFallbackAvailable) {
              Text('内部声音不可用时不会静默降级；请明确选择。').fontSize(13).fontColor('#B42318').width('100%')
              Button('改为无声录制').id('replay-continue-silent').width('100%').backgroundColor('#F4C542')
                .fontColor(TEXT).onClick(() => this.continueSilently())
              Button('取消本次录像').id('replay-cancel-audio-fallback').width('100%')
                .backgroundColor('#FEE4E2').fontColor('#B42318').onClick(() => this.cancelRecording())
            }
            if (this.readyToPublish) {
              Button('再次请求保存到相册').id('replay-record-only-publish').width('100%')
                .enabled(!this.busy).backgroundColor('#8B5CF6').onClick(() => this.retrySave())
            }
            Button('刷新编码统计').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
              .onClick(() => this.refreshStats())
            if (!this.recording) {
              Button('返回模式选择').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
                .onClick(() => { this.recordOnlySelected = false; })
            }
          }.width('100%').padding(16).backgroundColor(Color.White).borderRadius(14)
        }

        Text('隐私说明：系统录屏与媒体库保存确认必须由你在系统界面决定。应用不使用麦克风，不上传画面或声音。')
          .fontSize(13).fontColor(ACCENT).width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
      }.width('100%').padding(18)
    }.id('replay-launch-page').width('100%').height('100%').backgroundColor('#F4F7FB')
  }
}
