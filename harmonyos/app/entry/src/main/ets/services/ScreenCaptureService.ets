import { image } from '@kit.ImageKit';
import pcbridge, { CapturedFrame, CaptureRect, CaptureStats, NativeResult } from 'libpcbridge.so';

export const OWN_TEAM_CAPTURE_WIDTH: number = 2772;
export const OWN_TEAM_CAPTURE_HEIGHT: number = 1240;

export interface StableCaptureFrame {
  data: ArrayBuffer;
  width: number;
  height: number;
  hash: number;
  timestampUs: number;
  cards: CaptureRect[];
}

export class ScreenCaptureService {
  requestAuthorization(): NativeResult {
    const prepared: NativeResult = pcbridge.prepareCapture(OWN_TEAM_CAPTURE_WIDTH, OWN_TEAM_CAPTURE_HEIGHT);
    if (!prepared.ok) return prepared;
    return pcbridge.startCapture();
  }

  selectWindow(): NativeResult {
    return pcbridge.presentWindowPicker();
  }

  stats(): CaptureStats {
    return pcbridge.getCaptureStats();
  }

  takeStableFrame(): StableCaptureFrame {
    const frame: CapturedFrame = pcbridge.takeLatestFrame();
    if (!frame.ok || !frame.data) throw new Error('暂时没有可用画面，请确认相册图片已全屏显示后重试');
    if (frame.width !== OWN_TEAM_CAPTURE_WIDTH || frame.height !== OWN_TEAM_CAPTURE_HEIGHT) {
      throw new Error(`当前画面为 ${frame.width}×${frame.height}，请调整为 2772×1240 横屏全屏后重试`);
    }
    if ((frame.cards ?? []).length !== 6) throw new Error('当前画面没有完整显示六个队伍卡片，请全屏显示正确页面后重试');
    return {
      data: frame.data,
      width: frame.width,
      height: frame.height,
      hash: frame.hash ?? 0,
      timestampUs: frame.timestampUs ?? 0,
      cards: frame.cards ?? []
    };
  }

  takeTeamPreviewFrame(): StableCaptureFrame {
    const frame: CapturedFrame = pcbridge.takeLatestFrame();
    if (!frame.ok || !frame.data) throw new Error('暂时没有可用画面，请确认相册图片已全屏显示后重试');
    if (frame.width !== OWN_TEAM_CAPTURE_WIDTH || frame.height !== OWN_TEAM_CAPTURE_HEIGHT) {
      throw new Error(`当前画面为 ${frame.width}×${frame.height}，请调整为 2772×1240 横屏全屏后重试`);
    }
    return { data: frame.data, width: frame.width, height: frame.height,
      hash: frame.hash ?? 0, timestampUs: frame.timestampUs ?? 0, cards: frame.cards ?? [] };
  }

  stop(): NativeResult {
    return pcbridge.stopCapture();
  }

  async pixelMapForFrame(frame: StableCaptureFrame): Promise<image.PixelMap> {
    return image.createPixelMap(frame.data, {
      size: { width: frame.width, height: frame.height },
      srcPixelFormat: image.PixelMapFormat.RGBA_8888,
      pixelFormat: image.PixelMapFormat.RGBA_8888,
      editable: false
    });
  }
}
