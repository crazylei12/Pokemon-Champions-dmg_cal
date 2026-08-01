import { hilog } from '@kit.PerformanceAnalysisKit';
import { RELEASE_VARIANT, REPLAY_ENABLED } from 'BuildProfile';
import pcbridge, { BridgeInfo } from 'libpcbridge.so';

const DOMAIN: number = 0x5043;

@Component
struct HomeEntryCard {
  title: string = '';
  summary: string = '';
  accent: string = '#F7D64A';
  testId: string = '';

  build() {
    Row({ space: 14 }) {
      Blank()
        .width(8)
        .height(48)
        .backgroundColor(this.accent)
        .borderRadius(4)
      Column({ space: 4 }) {
        Text(this.title)
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor('#17233A')
        Text(this.summary)
          .fontSize(13)
          .fontColor('#667085')
          .maxLines(2)
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
    }
    .id(this.testId)
    .width('100%')
    .padding(16)
    .backgroundColor(Color.White)
    .borderRadius(16)
  }
}

@Entry
@Component
struct Index {
  @State bridgeReady: boolean = false;

  aboutToAppear(): void {
    const info: BridgeInfo = pcbridge.getBridgeInfo();
    this.bridgeReady = info.api === 1 && info.native && info.name === 'pcbridge';
    hilog.info(DOMAIN, 'PCApp', 'APP_NATIVE_BRIDGE_%{public}s variant=%{public}s',
      this.bridgeReady ? 'READY' : 'FAIL', RELEASE_VARIANT);
  }

  build() {
    Column() {
      Scroll() {
        Column({ space: 14 }) {
          Row() {
            Column({ space: 3 }) {
              Text('宝可梦冠军助手')
                .fontSize(26)
                .fontWeight(FontWeight.Bold)
                .fontColor('#17233A')
              Text(REPLAY_ENABLED ? '录屏功能版' : '标准版')
                .id(REPLAY_ENABLED ? 'variant-replay' : 'variant-standard')
                .fontSize(13)
                .fontColor('#667085')
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)
            Text('v1.1.4')
              .fontSize(12)
              .fontColor('#667085')
              .padding({ left: 10, right: 10, top: 6, bottom: 6 })
              .backgroundColor('#EEF2F6')
              .borderRadius(12)
          }
          .width('100%')

          Text('离线对战计算与识别助手')
            .fontSize(15)
            .fontColor('#475467')
            .width('100%')

          HomeEntryCard({
            title: '自由计算',
            summary: '配置双方宝可梦并查看伤害结果',
            accent: '#F7D64A',
            testId: 'entry-calculator'
          })
          HomeEntryCard({
            title: '对局助手',
            summary: '从队伍预览开始建立本地对局',
            accent: '#58B09C',
            testId: 'entry-battle'
          })
          HomeEntryCard({
            title: '我的队伍',
            summary: '管理已确认的队伍配置',
            accent: '#7289DA',
            testId: 'entry-teams'
          })
          if (REPLAY_ENABLED) {
            HomeEntryCard({
              title: '录屏模式',
              summary: '记录对局并在本地回放',
              accent: '#E76F51',
              testId: 'entry-replay'
            })
          }
          HomeEntryCard({
            title: '设置',
            summary: '管理显示、数据与应用信息',
            accent: '#98A2B3',
            testId: 'entry-settings'
          })
        }
        .width('100%')
        .padding({ left: 20, right: 20, top: 24, bottom: 32 })
      }
      .width('100%')
      .align(Alignment.Top)
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F4F7FB')
  }
}
