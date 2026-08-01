import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import { battleOverlayCoordinator } from '../services/BattleOverlayCoordinator';
import { REPLAY_ENABLED } from 'BuildProfile';
import { replayRecordingCoordinator } from '../services/ReplayRecordingCoordinator';
import { safeUiError } from '../ui/PrivacySafeError';

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
  private dragX: number = 0;
  private dragY: number = 0;
  private dragMoved: boolean = false;
  private suppressNextClick: boolean = false;
  private windowTransitioning: boolean = false;
  private lastImmediateActionAt: number = 0;

  aboutToAppear(): void {
    this.status = ownTeamCaptureCoordinator.status();
    if (REPLAY_ENABLED) {
      this.recording = replayRecordingCoordinator.isRecording();
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    }
  }

  private async setExpanded(value: boolean): Promise<void> {
    if (this.windowTransitioning) return;
    this.windowTransitioning = true;
    try {
      this.expanded = value;
      await ownTeamCaptureCoordinator.resizeFloatWindow(value);
    } finally {
      this.windowTransitioning = false;
    }
  }

  private allowImmediateAction(): boolean {
    const now = Date.now();
    if (now - this.lastImmediateActionAt < 700) return false;
    this.lastImmediateActionAt = now;
    return true;
  }

  private beginDrag(): void {
    this.dragX = 0;
    this.dragY = 0;
    this.dragMoved = false;
  }

  private updateDrag(event: GestureEvent): void {
    const deltaX = Math.round(event.offsetX - this.dragX);
    const deltaY = Math.round(event.offsetY - this.dragY);
    this.dragX = event.offsetX;
    this.dragY = event.offsetY;
    if (Math.abs(event.offsetX) > 4 || Math.abs(event.offsetY) > 4) this.dragMoved = true;
    ownTeamCaptureCoordinator.moveFloatWindowBy(deltaX, deltaY);
  }

  private endDrag(): void {
    this.suppressNextClick = this.dragMoved;
    ownTeamCaptureCoordinator.snapFloatWindowToEdge();
  }

  private openCollapsed(): void {
    if (this.suppressNextClick) {
      this.suppressNextClick = false;
      return;
    }
    this.setExpanded(true);
  }

  private async recognize(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      this.status = await ownTeamCaptureCoordinator.recognizeOwnTeam();
    } catch (error) {
      this.status = safeUiError(error, '识别我的队伍失败');
    } finally {
      this.busy = false;
    }
  }

  private async recognizePreview(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      this.status = await ownTeamCaptureCoordinator.recognizeTeamPreview();
      await ownTeamCaptureCoordinator.minimizeFloatWindow();
      await battleOverlayCoordinator.showSetup();
    } catch (error) {
      this.status = safeUiError(error, '识别双方阵容失败');
    } finally {
      this.busy = false;
    }
  }

  private startRecording(): void {
    if (!this.allowImmediateAction()) return;
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
      this.status = safeUiError(error, '开始录屏失败');
      this.silentFallbackAvailable = replayRecordingCoordinator.canRetrySilently();
    }
  }

  private continueSilently(): void {
    if (!this.allowImmediateAction()) return;
    try {
      this.status = replayRecordingCoordinator.continueSilently();
      this.recording = true;
      this.silentFallbackAvailable = false;
    } catch (error) {
      this.status = safeUiError(error, '切换无声录制失败');
    }
  }

  private cancelRecording(): void {
    if (!this.allowImmediateAction()) return;
    this.status = replayRecordingCoordinator.cancelRecording();
    this.recording = false;
    this.silentFallbackAvailable = false;
  }

  private async stopRecording(): Promise<void> {
    if (this.busy || !this.allowImmediateAction()) return;
    this.busy = true;
    try {
      this.status = await replayRecordingCoordinator.stopAndPublish();
      this.recording = false;
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } catch (error) {
      this.status = safeUiError(error, '保存录像失败');
      this.recording = replayRecordingCoordinator.isRecording();
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } finally {
      this.busy = false;
    }
  }

  private async savePendingRecording(): Promise<void> {
    if (this.busy || !this.allowImmediateAction()) return;
    this.busy = true;
    try {
      this.status = await replayRecordingCoordinator.publishPending();
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } catch (error) {
      this.status = safeUiError(error, '发布待保存录像失败');
      this.pendingRecording = replayRecordingCoordinator.hasPendingRecording();
    } finally {
      this.busy = false;
    }
  }

  private async stopAssistant(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      if (REPLAY_ENABLED && replayRecordingCoordinator.isRecording()) {
        this.status = await replayRecordingCoordinator.stopAndPublish();
      }
      await battleOverlayCoordinator.close();
      await ownTeamCaptureCoordinator.stop();
      this.recording = false;
    } catch (error) {
      this.status = safeUiError(error, '结束对局助手失败');
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
      .gesture(PanGesture().onActionStart(() => this.beginDrag())
        .onActionUpdate((event: GestureEvent) => this.updateDrag(event))
        .onActionEnd(() => this.endDrag()))
      .onClick(() => this.openCollapsed())
    } else {
      Column({ space: 10 }) {
        Row() {
          Text('对局助手').fontSize(21).fontWeight(FontWeight.Bold).fontColor(TEXT).layoutWeight(1)
          Button('收起').fontSize(13).backgroundColor(SECONDARY).fontColor(TEXT)
            .onClick(() => this.setExpanded(false))
        }.width('100%')
          .gesture(PanGesture().onActionStart(() => this.beginDrag())
            .onActionUpdate((event: GestureEvent) => this.updateDrag(event))
            .onActionEnd(() => this.endDrag()))
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
              catch (error) { this.status = safeUiError(error, '打开伤害面板失败'); }
            })
          Button('显示对战 HUD').id('float-open-direct-hud').width('100%')
            .enabled(!this.busy).backgroundColor(SECONDARY).fontColor(TEXT).onClick(async () => {
              try { await this.setExpanded(false); await battleOverlayCoordinator.show('hud'); }
              catch (error) { this.status = safeUiError(error, '显示对战 HUD 失败'); }
            })
        }
        Button('重新选择相册窗口').width('100%').backgroundColor(SECONDARY).fontColor(TEXT)
          .enabled(!this.busy).onClick(() => {
            try { this.status = ownTeamCaptureCoordinator.selectAlbumWindow(); }
            catch (error) { this.status = safeUiError(error, '重新选择相册窗口失败'); }
          })
        Button('返回助手核对').width('100%').backgroundColor(SECONDARY).fontColor(TEXT)
          .enabled(!this.busy).onClick(async () => {
            try { await ownTeamCaptureCoordinator.openMainApp(); }
            catch (error) { this.status = safeUiError(error, '返回主应用失败'); }
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
