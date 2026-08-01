import { common } from '@kit.AbilityKit';
import pcbridge from 'libpcbridge.so';
import { parseTeamPreviewRecognition, TeamPreviewRecognitionResult } from '../domain/TeamPreviewRecognition';
import { StableCaptureFrame } from './ScreenCaptureService';

const TEMPLATE_PATH: string = 'runtime/recognition/team-preview-templates-v2.bin';

function exactArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.length);
  copy.set(bytes);
  return copy.buffer as ArrayBuffer;
}

export class TeamPreviewRecognitionService {
  private context: common.UIAbilityContext;
  private templates?: ArrayBuffer;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  private async templateAsset(): Promise<ArrayBuffer> {
    if (!this.templates) {
      const bytes = await this.context.resourceManager.getRawFileContent(TEMPLATE_PATH);
      this.templates = exactArrayBuffer(bytes);
    }
    return this.templates as ArrayBuffer;
  }

  async recognize(frame: StableCaptureFrame): Promise<TeamPreviewRecognitionResult> {
    const capturedAt = new Date().toISOString();
    const templates = await this.templateAsset();
    const json: string = await pcbridge.recognizeTeamPreview(
      frame.data, frame.width, frame.height, templates, capturedAt) as string;
    return parseTeamPreviewRecognition(json);
  }
}
