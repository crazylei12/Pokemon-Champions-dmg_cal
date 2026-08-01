import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import { battleOverlayCoordinator } from '../services/BattleOverlayCoordinator';
import { REPLAY_ENABLED } from 'BuildProfile';
import { replayRecordingCoordinator } from '../services/ReplayRecordingCoordinator';

@Entry
@Component
struct FloatAssistant {
  @State expanded: boolean = false;
  @State busy: boolean = false;
  @State status: string = '';
  @State recording: boolean = false;
  @State pendingRecording: boolean = false;

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
    } catch (error) {
      this.status = String(error);
    }
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
      .backgroundColor('#E6167D6D')
      .border({ width: 4, color: Color.White })
      .borderRadius(100)
      .onClick(() => this.setExpanded(true))
    } else {
      Column({ space: 10 }) {
        Row() {
          Text('对局助手').fontSize(21).fontWeight(FontWeight.Bold).fontColor('#17233A').layoutWeight(1)
          Button('收起').fontSize(13).backgroundColor('#EEF2F6').fontColor('#17233A')
            .onClick(() => this.setExpanded(false))
        }.width('100%')
        Text(this.status).fontSize(14).fontColor(this.status.includes('无法') || this.status.includes('Error') ?
          '#B42318' : '#475467').width('100%').maxLines(4)
        Button(this.busy ? '正在识别…' : '录入我的队伍').id('float-recognize-own-team').width('100%')
          .enabled(!this.busy).backgroundColor('#167D6D').onClick(() => this.recognize())
        Button(this.busy ? '正在识别…' : '识别双方阵容').id('float-recognize-team-preview').width('100%')
          .enabled(!this.busy).backgroundColor('#8B5CF6').onClick(() => this.recognizePreview())
        if (REPLAY_ENABLED) {
          Button(this.pendingRecording ? '保存已完成的 MP4' :
            (this.recording ? '结束录屏并保存 MP4' : '开始录屏'))
            .id('float-replay-toggle').width('100%').enabled(!this.busy)
            .backgroundColor(this.recording ? '#B42318' : (this.pendingRecording ? '#8B5CF6' : '#F4C542'))
            .fontColor(this.recording || this.pendingRecording ? Color.White : '#17233A')
            .onClick(() => {
              if (this.pendingRecording) this.savePendingRecording();
              else if (this.recording) this.stopRecording();
              else this.startRecording();
            })
        }
        Button('打开伤害面板').id('float-open-damage-panel').width('100%')
          .enabled(!this.busy).backgroundColor('#167D6D').onClick(async () => {
            try { await this.setExpanded(false); await battleOverlayCoordinator.show('panel'); }
            catch (error) { this.status = String(error); }
          })
        Button('显示对战 HUD').id('float-open-direct-hud').width('100%')
          .enabled(!this.busy).backgroundColor('#EEF2F6').fontColor('#17233A').onClick(async () => {
            try { await this.setExpanded(false); await battleOverlayCoordinator.show('hud'); }
            catch (error) { this.status = String(error); }
          })
        Button('重新选择相册窗口').width('100%').backgroundColor('#EEF2F6').fontColor('#17233A')
          .enabled(!this.busy).onClick(() => {
            try { this.status = ownTeamCaptureCoordinator.selectAlbumWindow(); }
            catch (error) { this.status = String(error); }
          })
        Button('返回助手核对').width('100%').backgroundColor('#EEF2F6').fontColor('#17233A')
          .enabled(!this.busy).onClick(async () => {
            try { await ownTeamCaptureCoordinator.openMainApp(); }
            catch (error) { this.status = String(error); }
          })
        Button('结束对局助手').width('100%').backgroundColor('#FEE4E2').fontColor('#B42318')
          .enabled(!this.busy).onClick(() => this.stopAssistant())
      }
      .width('100%')
      .height('100%')
      .padding(18)
      .backgroundColor('#F7FAFC')
      .border({ width: 2, color: '#D0D5DD' })
      .borderRadius(22)
    }
  }
}
