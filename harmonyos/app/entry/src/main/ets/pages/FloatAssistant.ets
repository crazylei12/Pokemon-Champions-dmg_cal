import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';

@Entry
@Component
struct FloatAssistant {
  @State expanded: boolean = false;
  @State busy: boolean = false;
  @State status: string = '';

  aboutToAppear(): void {
    this.status = ownTeamCaptureCoordinator.status();
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
          .enabled(!this.busy).onClick(async () => { await ownTeamCaptureCoordinator.stop(); })
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
