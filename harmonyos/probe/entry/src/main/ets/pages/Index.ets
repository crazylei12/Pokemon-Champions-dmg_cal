import { common } from '@kit.AbilityKit';
import { componentSnapshot, window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { textRecognition } from '@kit.CoreVisionKit';
import { fileIo } from '@kit.CoreFileKit';
import { resourceManager } from '@kit.LocalizationKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { webview } from '@kit.ArkWeb';
import pcprobe, { CaptureStats, NativeProbeResult, TemplateProbeResult } from 'libpcprobe.so';

const DOMAIN: number = 0x5043;
const FLOAT_WINDOW_NAME: string = 'pc-stage1-float-probe';

@Component
struct ProbeStatusCard {
  title: string = '';
  @Prop status: string = '';

  build() {
    Column({ space: 6 }) {
      Text(this.title)
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .fontColor('#17233A')
      Text(this.status)
        .fontSize(13)
        .fontColor(this.status.startsWith('PASS') ? '#087A45' : '#5C667A')
        .maxLines(5)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .alignItems(HorizontalAlign.Start)
    .padding(12)
    .width('100%')
    .backgroundColor(Color.White)
    .borderRadius(12)
  }
}

@Entry
@Component
struct Index {
  @State floatStatus: string = '未运行';
  @State ocrStatus: string = '未运行';
  @State arkWebStatus: string = '等待本地页面';
  @State fileStatus: string = '未运行';
  @State photoStatus: string = '未运行';
  @State nativeStatus: string = '未运行';
  @State openCvStatus: string = '未运行';
  @State captureStatus: string = '未运行';
  @State busy: boolean = false;

  private floatWindow?: window.Window;
  private webController: webview.WebviewController = new webview.WebviewController('pc-stage1-web');

  private describeError(error: Error | BusinessError): string {
    const businessError: BusinessError = error as BusinessError;
    const code: number = businessError.code ?? -1;
    const message: string = businessError.message ?? String(error);
    return `code=${code}; message=${message}`;
  }

  private async createFloatWindow(): Promise<void> {
    this.busy = true;
    try {
      if (this.floatWindow) {
        await this.floatWindow.destroyWindow();
        this.floatWindow = undefined;
      }
      const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      const config: window.Configuration = {
        name: FLOAT_WINDOW_NAME,
        windowType: window.WindowType.TYPE_FLOAT,
        ctx: context
      };
      const created: window.Window = await window.createWindow(config);
      await created.resize(620, 180);
      await created.moveWindowTo(64, 108);
      await created.loadContent('pages/FloatProbe');
      await created.showWindow();
      this.floatWindow = created;
      this.floatStatus = 'PASS：已创建、移动、显示并可交互';
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_FLOAT_PASS');
    } catch (error) {
      this.floatStatus = `FAIL：${this.describeError(error as BusinessError)}`;
      hilog.error(DOMAIN, 'PCProbe', 'PROBE_FLOAT_FAIL %{public}s', this.floatStatus);
    } finally {
      this.busy = false;
    }
  }

  private async removeFloatWindow(): Promise<void> {
    try {
      if (this.floatWindow) {
        await this.floatWindow.destroyWindow();
        this.floatWindow = undefined;
      } else {
        const existing: window.Window = window.findWindow(FLOAT_WINDOW_NAME);
        await existing.destroyWindow();
      }
      this.floatStatus = 'PASS：悬浮窗已移除';
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_FLOAT_REMOVED');
    } catch (error) {
      this.floatStatus = `移除结果：${this.describeError(error as BusinessError)}`;
    }
  }

  private async runOcrProbe(): Promise<void> {
    this.busy = true;
    try {
      const pixelMap = await componentSnapshot.get('ocr-probe-card');
      const result: textRecognition.TextRecognitionResult = await textRecognition.recognizeText({ pixelMap: pixelMap });
      const normalized: string = result.value.replace(/\s+/g, ' ').trim();
      const lineCount: number = result.blocks.reduce((sum: number, block: textRecognition.TextBlock) =>
        sum + block.lines.length, 0);
      const hasChinese: boolean = normalized.includes('皮卡丘');
      const hasEnglish: boolean = normalized.toUpperCase().includes('THUNDERBOLT');
      const hasDigits: boolean = normalized.includes('2772') && normalized.includes('1240');
      const hasCoordinates: boolean = result.blocks.some((block: textRecognition.TextBlock) =>
        block.lines.some((line: textRecognition.TextLine) => line.cornerPoints.length >= 4));
      if (!hasChinese || !hasEnglish || !hasDigits || !hasCoordinates) {
        throw new Error(`OCR字段不完整: ${normalized}`);
      }
      this.ocrStatus = `PASS：${lineCount} 行；${normalized}`;
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_OCR_PASS lines=%{public}d text=%{public}s', lineCount, normalized);
      pixelMap.release();
    } catch (error) {
      this.ocrStatus = `FAIL：${this.describeError(error as BusinessError)}`;
      hilog.error(DOMAIN, 'PCProbe', 'PROBE_OCR_FAIL %{public}s', this.ocrStatus);
    } finally {
      this.busy = false;
    }
  }

  private async runArkWebProbe(): Promise<void> {
    try {
      const result: string = await this.webController.runJavaScript('probeDamage(50, 90, 120, 100, 1.5)');
      const expected: boolean = result.includes('73') && result.includes('local-fixed-js-v1');
      this.arkWebStatus = expected ? `PASS：${result}` : `FAIL：结果不符 ${result}`;
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_ARKWEB_%{public}s %{public}s', expected ? 'PASS' : 'FAIL', result);
    } catch (error) {
      this.arkWebStatus = `FAIL：${this.describeError(error as BusinessError)}`;
    }
  }

  private async runSandboxProbe(): Promise<void> {
    try {
      const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      const filePath: string = `${context.filesDir}/stage1-probe.json`;
      const handle: fileIo.File = fileIo.openSync(filePath,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      const payload: string = JSON.stringify({ version: 1, marker: 'PC_STAGE1_SANDBOX_PASS' });
      fileIo.writeSync(handle.fd, payload);
      fileIo.closeSync(handle);
      const readHandle: fileIo.File = fileIo.openSync(filePath, fileIo.OpenMode.READ_ONLY);
      const buffer: ArrayBuffer = new ArrayBuffer(256);
      const readLength: number = fileIo.readSync(readHandle.fd, buffer);
      fileIo.closeSync(readHandle);
      const decoded: string = String.fromCharCode(...new Uint8Array(buffer, 0, readLength));
      if (decoded !== payload) {
        throw new Error('sandbox round trip mismatch');
      }
      this.fileStatus = `PASS：${filePath}`;
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_SANDBOX_PASS %{public}s', filePath);
    } catch (error) {
      this.fileStatus = `FAIL：${this.describeError(error as BusinessError)}`;
    }
  }

  private async openPhotoPicker(): Promise<void> {
    try {
      const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
      const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
      options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
      options.maxSelectNumber = 1;
      const result: photoAccessHelper.PhotoSelectResult = await picker.select(options);
      this.photoStatus = result.photoUris.length === 1 ? `PASS：${result.photoUris[0]}` : 'FAIL：未返回单张图片';
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_PHOTO_PICKER count=%{public}d', result.photoUris.length);
    } catch (error) {
      this.photoStatus = `结果：${this.describeError(error as BusinessError)}`;
    }
  }

  private runNativeProbe(): void {
    const result: NativeProbeResult = pcprobe.probeNativeCapture();
    this.nativeStatus = `${result.ok ? 'PASS' : 'FAIL'}：${JSON.stringify(result)}`;
    hilog.info(DOMAIN, 'PCProbe', 'PROBE_NATIVE_UI %{public}s', this.nativeStatus);
  }

  private async runOpenCvProbe(): Promise<void> {
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    try {
      const descriptor: resourceManager.RawFileDescriptor =
        await context.resourceManager.getRawFd('team-preview-templates-v2.bin');
      const result: TemplateProbeResult =
        pcprobe.probeOpenCvTemplateFd(descriptor.fd, descriptor.offset, descriptor.length);
      await context.resourceManager.closeRawFd('team-preview-templates-v2.bin');
      this.openCvStatus = `${result.ok ? 'PASS' : 'FAIL'}：${JSON.stringify(result)}`;
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_OPENCV_UI %{public}s', this.openCvStatus);
    } catch (error) {
      this.openCvStatus = `FAIL：${this.describeError(error as BusinessError)}`;
    }
  }

  private prepareRawCapture(): void {
    const prepare: CaptureStats = pcprobe.prepareRawCapture(1240, 2772);
    this.captureStatus = `${prepare.ok ? 'PASS：原始流已准备' : 'FAIL'}：${JSON.stringify(prepare)}`;
  }

  private prepareGalleryWindowCapture(): void {
    const prepare: CaptureStats = pcprobe.prepareRawWindowCapture(35, 1240, 2772);
    this.captureStatus = `${prepare.ok ? 'PASS：相册任务 35 原始流已准备' : 'FAIL'}：${JSON.stringify(prepare)}`;
  }

  private prepareFileRecording(): void {
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    const outputPath: string = `${context.filesDir}/stage1-window-recording.mp4`;
    const prepare: CaptureStats = pcprobe.prepareFileRecording(outputPath, 1240, 2772);
    this.captureStatus = `${prepare.ok ? `PASS：MP4 已准备；输出 ${outputPath}` : 'FAIL'}：${JSON.stringify(prepare)}`;
  }

  private prepareGalleryWindowRecording(): void {
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    const outputPath: string = `${context.filesDir}/stage1-window-recording.mp4`;
    const prepare: CaptureStats = pcprobe.prepareFileWindowRecording(outputPath, 35, 1240, 2772);
    this.captureStatus = `${prepare.ok ? `PASS：相册任务 35 MP4 已准备；输出 ${outputPath}` : 'FAIL'}：${JSON.stringify(prepare)}`;
  }

  private showWindowPicker(): void {
    const result: CaptureStats = pcprobe.presentWindowPicker();
    this.captureStatus = `${result.ok ? 'PASS：窗口选择器已请求' : 'FAIL'}：${JSON.stringify(result)}`;
  }

  private startPreparedCapture(): void {
    const result: CaptureStats = pcprobe.startPreparedCapture();
    this.captureStatus = `${result.ok ? 'PASS：已请求启动' : 'FAIL'}：${JSON.stringify(result)}`;
  }

  private refreshCaptureStats(): void {
    const result: CaptureStats = pcprobe.getCaptureStats();
    this.captureStatus = `统计：${JSON.stringify(result)}`;
  }

  private requestFrameSnapshot(): void {
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    const outputPath: string = `${context.filesDir}/stage1-captured-frame.rgba`;
    const result: CaptureStats = pcprobe.requestFrameSnapshot(outputPath);
    this.captureStatus = `${result.ok ? `PASS：下一帧将保存到 ${outputPath}` : 'FAIL'}：${JSON.stringify(result)}`;
  }

  private stopCapture(): void {
    const result: CaptureStats = pcprobe.stopCapture();
    this.captureStatus = `${result.ok ? 'PASS：已停止并释放' : 'FAIL'}：${JSON.stringify(result)}`;
  }

  private async publishRecordingToGallery(): Promise<void> {
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    const sourcePath: string = `${context.filesDir}/stage1-window-recording.mp4`;
    try {
      const helper: photoAccessHelper.PhotoAccessHelper = photoAccessHelper.getPhotoAccessHelper(context);
      const config: photoAccessHelper.PhotoCreationConfig = {
        title: 'pc-stage1-window-recording',
        fileNameExtension: 'mp4',
        photoType: photoAccessHelper.PhotoType.VIDEO,
        subtype: photoAccessHelper.PhotoSubtype.DEFAULT
      };
      const destinationUris: string[] = await helper.showAssetsCreationDialog([sourcePath], [config]);
      if (destinationUris.length !== 1) {
        throw new Error(`媒体库没有返回唯一目标 URI: ${destinationUris.length}`);
      }
      const source: fileIo.File = await fileIo.open(sourcePath, fileIo.OpenMode.READ_ONLY);
      const destination: fileIo.File = await fileIo.open(destinationUris[0], fileIo.OpenMode.WRITE_ONLY);
      await fileIo.copyFile(source.fd, destination.fd);
      fileIo.closeSync(source);
      fileIo.closeSync(destination);
      this.captureStatus = `PASS：MP4 已发布到媒体库 ${destinationUris[0]}`;
      hilog.info(DOMAIN, 'PCProbe', 'PROBE_MEDIA_PUBLISH_PASS %{public}s', destinationUris[0]);
    } catch (error) {
      this.captureStatus = `媒体库发布结果：${this.describeError(error as BusinessError)}`;
      hilog.error(DOMAIN, 'PCProbe', 'PROBE_MEDIA_PUBLISH_FAIL %{public}s', this.captureStatus);
    }
  }

  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text('Pokémon Champions · HarmonyOS 阶段 1 能力探针')
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor('#17233A')
          .width('100%')
        Text('目标：只验证移植所需的系统能力，不承载产品功能。')
          .fontSize(14)
          .fontColor('#5C667A')
          .width('100%')

        ProbeStatusCard({ title: '系统悬浮窗 TYPE_FLOAT', status: this.floatStatus })
        Row({ space: 8 }) {
          Button('创建悬浮窗').onClick(() => this.createFloatWindow()).enabled(!this.busy)
          Button('移除悬浮窗').onClick(() => this.removeFloatWindow())
        }
        .width('100%')

        Column({ space: 4 }) {
          Text('皮卡丘 THUNDERBOLT')
            .fontSize(30)
            .fontWeight(FontWeight.Bold)
          Text('画面 2772 × 1240 / HP 187')
            .fontSize(24)
        }
        .id('ocr-probe-card')
        .width('100%')
        .height(150)
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#FFF4BF')
        .border({ width: 2, color: '#E0A800' })
        .borderRadius(12)
        ProbeStatusCard({ title: 'Core Vision OCR（中文/英文/数字/坐标）', status: this.ocrStatus })
        Button('运行 OCR').onClick(() => this.runOcrProbe()).enabled(!this.busy).width('100%')

        ProbeStatusCard({ title: 'ArkWeb 固定本地 JavaScript', status: this.arkWebStatus })
        Web({ src: $rawfile('arkweb_probe.html'), controller: this.webController })
          .javaScriptAccess(true)
          .onPageEnd(() => this.runArkWebProbe())
          .height(58)
          .width('100%')
          .borderRadius(10)
        Button('重跑 ArkWeb').onClick(() => this.runArkWebProbe()).width('100%')

        ProbeStatusCard({ title: '应用沙箱 + 备份扩展', status: this.fileStatus })
        Button('写入并读回沙箱文件').onClick(() => this.runSandboxProbe()).width('100%')

        ProbeStatusCard({ title: '系统相册选择器', status: this.photoStatus })
        Button('打开相册选择器').onClick(() => this.openPhotoPicker()).width('100%')

        ProbeStatusCard({ title: 'Native AVScreenCapture', status: this.nativeStatus })
        Button('Create / Release Native 捕获器').onClick(() => this.runNativeProbe()).width('100%')

        ProbeStatusCard({ title: 'OpenCV 4.13 + 现有模板二进制', status: this.openCvStatus })
        Button('运行 OpenCV / 模板探针').onClick(() => this.runOpenCvProbe()).width('100%')

        ProbeStatusCard({ title: '窗口原始帧 / 内部音频 / MP4', status: this.captureStatus })
        Row({ space: 8 }) {
          Button('准备原始流').onClick(() => this.prepareRawCapture())
          Button('准备 MP4').onClick(() => this.prepareFileRecording())
        }
        .width('100%')
        Row({ space: 8 }) {
          Button('准备相册任务 35 原始流').onClick(() => this.prepareGalleryWindowCapture())
          Button('准备相册任务 35 MP4').onClick(() => this.prepareGalleryWindowRecording())
        }
        .width('100%')
        Row({ space: 8 }) {
          Button('启动').onClick(() => this.startPreparedCapture())
          Button('选择窗口').onClick(() => this.showWindowPicker())
        }
        .width('100%')
        Row({ space: 8 }) {
          Button('刷新统计').onClick(() => this.refreshCaptureStats())
          Button('保存下一帧').onClick(() => this.requestFrameSnapshot())
          Button('停止释放').onClick(() => this.stopCapture())
        }
        .width('100%')
        Button('发布 MP4 到相册').onClick(() => this.publishRecordingToGallery()).width('100%')

        Text('PROBE_UI_READY')
          .fontSize(12)
          .fontColor('#7B8496')
      }
      .width('100%')
      .padding(20)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F4F7FB')
  }
}
