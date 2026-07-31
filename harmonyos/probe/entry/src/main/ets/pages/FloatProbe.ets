@Entry
@Component
struct FloatProbe {
  @State label: string = 'FLOAT_WINDOW_VISIBLE';

  build() {
    Column({ space: 6 }) {
      Text(this.label)
        .fontColor(Color.White)
        .fontSize(15)
        .fontWeight(FontWeight.Bold)
      Button('交互测试')
        .fontSize(13)
        .height(58)
        .onClick(() => {
          this.label = 'FLOAT_WINDOW_INTERACTION_PASS';
        })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#CC1F6FEB')
    .borderRadius(18)
  }
}
