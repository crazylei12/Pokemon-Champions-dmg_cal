import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import { battleOverlayCoordinator } from '../services/BattleOverlayCoordinator';
import { REPLAY_ENABLED } from 'BuildProfile';
import { replayRecordingCoordinator } from '../services/ReplayRecordingCoordinator';

const ACCENT: string = '#F4C542';
const ON_ACCENT: string = '#281E00';
const TEXT: string = '#F4F8FB';
const MUTED: string = '#C0CCD6';
const PANEL: string = '#F20E141B';
const SECONDARY: string = '#1C2731';
const BORDER: string = '#566976';
const DANGER: string = '#FF8A80';
const DANGER_SURFACE: string = '#3B2229';

@Entry
@Component
struct FloatAssistant {
  @State expanded: boolean = false;
  @State busy: boolean = false;
  @State status: string = '';
  @State recording: boolean = false;
  @State pendingRecording: boolean = false;
  @State silentFallbackAvailable: boolean = false;

  aboutToAppear(): void {
    this.status = ownTeamCaptureCoordinator.status();
    if (REPLAY_ENABLED) {
      this.recording = replayRecordingCoordinator.isRecording();
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    }
  }

  private async setExpanded(value: boolean): Promise<void> {
    this.expanded = value;
    await ownTeamCaptureCoordinator.resizeFloatWindow(value);
  }

  private async recognize(): Promise<void> {
    this.busy = true;
    try {
      this.status = await ownTeamCaptureCoordinator.recognizeOwnTeam();
    } catch (error) {
      this.status = String(error);
    } finally {
      this.busy = false;
    }
  }

  private async recognizePreview(): Promise<void> {
    this.busy = true;
    try {
      this.status = await ownTeamCaptureCoordinator.recognizeTeamPreview();
      await ownTeamCaptureCoordinator.minimizeFloatWindow();
      await battleOverlayCoordinator.showSetup();
    } catch (error) {
      this.status = String(error);
    } finally {
      this.busy = false;
    }
  }

  private startRecording(): void {
    try {
      this.status = replayRecordingCoordinator.startRecordingOnCurrentCapture();
      this.recording = true;
      setTimeout(() => {
        this.silentFallbackAvailable = replayRecordingCoordinator.silentFallbackNeeded();
        if (this.silentFallbackAvailable) {
          this.status = '暂未检测到所选应用的内部声音；请选择改为无声录制或取消本次录像。';
        }
      }, 1500);
    } catch (error) {
      this.status = String(error);
      this.silentFallbackAvailable = replayRecordingCoordinator.canRetrySilently();
    }
  }

  private continueSilently(): void {
    try {
      this.status = replayRecordingCoordinator.continueSilently();
      this.recording = true;
      this.silentFallbackAvailable = false;
    } catch (error) {
      this.status = String(error);
    }
  }

  private cancelRecording(): void {
    this.status = replayRecordingCoordinator.cancelRecording();
    this.recording = false;
    this.silentFallbackAvailable = false;
  }

  private async stopRecording(): Promise<void> {
    this.busy = true;
    try {
      this.status = await replayRecordingCoordinator.stopAndPublish();
      this.recording = false;
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } catch (error) {
      this.status = String(error);
      this.recording = replayRecordingCoordinator.isRecording();
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } finally {
      this.busy = false;
    }
  }

  private async savePendingRecording(): Promise<void> {
    this.busy = true;
    try {
      this.status = await replayRecordingCoordinator.publishPending();
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } catch (error) {
      this.status = String(error);
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } finally {
      this.busy = false;
    }
  }

  private async stopAssistant(): Promise<void> {
    this.busy = true;
    try {
      if (REPLAY_ENABLED && replayRecordingCoordinator.isRecording()) {
        this.status = await replayRecordingCoordinator.stopAndPublish();
      }
      await ownTeamCaptureCoordinator.stop();
      this.recording = false;
    } catch (error) {
      this.status = String(error);
    } finally {
      this.busy = false;
    }
  }

  build() {
    if (!this.expanded) {
      Column({ space: 2 }) {
        Text('对局').fontSize(23).fontWeight(FontWeight.Bold).fontColor(Color.White)
        Text('助手').fontSize(17).fontColor(Color.White)
      }
      .width('100%')
      .height('100%')
      .justifyContent(FlexAlign.Center)
      .backgroundColor(PANEL)
      .border({ width: 4, color: ACCENT })
      .borderRadius(100)
      .onClick(() => this.setExpanded(true))
    } else {
      Column({ space: 10 }) {
        Row() {
          Text('对局助手').fontSize(21).fontWeight(FontWeight.Bold).fontColor(TEXT).layoutWeight(1)
          Button('收起').fontSize(13).backgroundColor(SECONDARY).fontColor(TEXT)
            .onClick(() => this.setExpanded(false))
        }.width('100%')
        Text(this.status).fontSize(14).fontColor(this.status.includes('无法') || this.status.includes('Error') ?
          DANGER : MUTED).width('100%').maxLines(4)
        Button(this.busy ? '正在识别…' : '录入我的队伍').id('float-recognize-own-team').width('100%')
          .enabled(!this.busy).backgroundColor(ACCENT).fontColor(ON_ACCENT).onClick(() => this.recognize())
        Button(this.busy ? '正在识别…' : '识别双方阵容').id('float-recognize-team-preview').width('100%')
          .enabled(!this.busy).backgroundColor(ACCENT).fontColor(ON_ACCENT).onClick(() => this.recognizePreview())
        if (REPLAY_ENABLED) {
          Button(this.pendingRecording ? '保存已完成的 MP4' :
            (this.recording ? '结束录屏并保存 MP4' : '开始录屏'))
            .id('float-replay-toggle').width('100%').enabled(!this.busy)
            .backgroundColor(this.recording ? DANGER_SURFACE : (this.pendingRecording ? SECONDARY : ACCENT))
            .fontColor(this.recording ? DANGER : (this.pendingRecording ? TEXT : ON_ACCENT))
            .onClick(() => {
              if (this.pendingRecording) this.savePendingRecording();
              else if (this.recording) this.stopRecording();
              else this.startRecording();
            })
          if (this.silentFallbackAvailable) {
            Text('内部声音不可用时不会静默降级；请明确选择。').fontSize(13).fontColor(DANGER).width('100%')
            Button('改为无声录制').id('float-replay-continue-silent').width('100%').backgroundColor(ACCENT)
              .fontColor(ON_ACCENT).onClick(() => this.continueSilently())
            Button('取消本次录像').id('float-replay-cancel-audio-fallback').width('100%')
              .backgroundColor(DANGER_SURFACE).fontColor(DANGER).onClick(() => this.cancelRecording())
          }
        }
        if (battleOverlayCoordinator.snapshot().ready) {
          Button('打开伤害面板').id('float-open-damage-panel').width('100%')
            .enabled(!this.busy).backgroundColor(ACCENT).fontColor(ON_ACCENT).onClick(async () => {
              try { await this.setExpanded(false); await battleOverlayCoordinator.show('panel'); }
              catch (error) { this.status = String(error); }
            })
          Button('显示对战 HUD').id('float-open-direct-hud').width('100%')
            .enabled(!this.busy).backgroundColor(SECONDARY).fontColor(TEXT).onClick(async () => {
              try { await this.setExpanded(false); await battleOverlayCoordinator.show('hud'); }
              catch (error) { this.status = String(error); }
            })
        }
        Button('重新选择相册窗口').width('100%').backgroundColor(SECONDARY).fontColor(TEXT)
          .enabled(!this.busy).onClick(() => {
            try { this.status = ownTeamCaptureCoordinator.selectAlbumWindow(); }
            catch (error) { this.status = String(error); }
          })
        Button('返回助手核对').width('100%').backgroundColor(SECONDARY).fontColor(TEXT)
          .enabled(!this.busy).onClick(async () => {
            try { await ownTeamCaptureCoordinator.openMainApp(); }
            catch (error) { this.status = String(error); }
          })
        Button('结束对局助手').width('100%').backgroundColor(DANGER_SURFACE).fontColor(DANGER)
          .enabled(!this.busy).onClick(() => this.stopAssistant())
      }
      .width('100%')
      .height('100%')
      .padding(18)
      .backgroundColor(PANEL)
      .border({ width: 2, color: BORDER })
      .borderRadius(22)
    }
  }
}
