import { common } from '@kit.AbilityKit';
import { display, window } from '@kit.ArkUI';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import {
  acceptOwnTeamPage,
  buildOwnTeamCorrectionDraft,
  buildSavedOwnTeam,
  OwnTeamCorrectionDraft,
  OwnTeamImportDraft,
  OwnTeamDraftUpdate
} from '../domain/OwnTeamRecognition';
import { AppStorageRepository } from '../storage/AppStorageRepository';
import { StoredOwnTeamImportDraft, StoredTeam } from '../storage/StorageContracts';
import { OwnTeamRecognitionService } from './OwnTeamRecognitionService';
import { ScreenCaptureService } from './ScreenCaptureService';

const FLOAT_WINDOW_NAME: string = 'pokemon-champions-assistant-float';

export class OwnTeamCaptureCoordinator {
  private context?: common.UIAbilityContext;
  private storage?: AppStorageRepository;
  private recognition?: OwnTeamRecognitionService;
  private capture: ScreenCaptureService = new ScreenCaptureService();
  private floatWindow?: window.Window;
  private statusText: string = '尚未启动对局助手';
  private busy: boolean = false;

  configure(context: common.UIAbilityContext, catalog: RuntimeDataRepository,
    storage: AppStorageRepository): void {
    this.context = context;
    this.storage = storage;
    this.recognition = new OwnTeamRecognitionService(catalog);
  }

  status(): string {
    return this.statusText;
  }

  isBusy(): boolean {
    return this.busy;
  }

  private requireConfigured(): void {
    if (!this.context || !this.storage || !this.recognition) throw new Error('对局助手尚未准备完成');
  }

  async startAssistant(): Promise<string> {
    this.requireConfigured();
    const result = this.capture.requestAuthorization();
    if (!result.ok) throw new Error(`无法启动画面读取（${result.code}）：${result.message}`);
    this.statusText = '已向系统请求画面授权；请由你在系统界面选择是否允许，并选择相册窗口';
    await this.showFloatWindow();
    return this.statusText;
  }

  async showFloatWindow(): Promise<void> {
    this.requireConfigured();
    if (this.floatWindow) {
      await this.floatWindow.showWindow();
      return;
    }
    const config: window.Configuration = {
      name: FLOAT_WINDOW_NAME,
      windowType: window.WindowType.TYPE_FLOAT,
      ctx: this.context as common.UIAbilityContext
    };
    const created = await window.createWindow(config);
    await created.resize(200, 200);
    const currentDisplay = display.getDefaultDisplaySync();
    await created.moveWindowTo(Math.max(24, currentDisplay.width - 224), Math.max(120, Math.floor(currentDisplay.height * 0.2)));
    await created.loadContent('pages/FloatAssistant');
    await created.showWindow();
    this.floatWindow = created;
  }

  async resizeFloatWindow(expanded: boolean): Promise<void> {
    if (!this.floatWindow) return;
    const currentDisplay = display.getDefaultDisplaySync();
    const width = expanded ? Math.min(760, currentDisplay.width - 48) : 200;
    const height = expanded ? Math.min(680, currentDisplay.height - 96) : 200;
    await this.floatWindow.resize(width, height);
    await this.floatWindow.moveWindowTo(Math.max(24, currentDisplay.width - width - 24),
      Math.max(48, Math.min(Math.floor(currentDisplay.height * 0.18), currentDisplay.height - height - 48)));
  }

  selectAlbumWindow(): string {
    const result = this.capture.selectWindow();
    if (!result.ok) throw new Error(`暂时无法打开窗口选择器（${result.code}）。请先在系统界面完成画面授权`);
    this.statusText = '请选择系统相册窗口，然后把队伍图片横屏全屏显示';
    return this.statusText;
  }

  async recognizeOwnTeam(): Promise<string> {
    this.requireConfigured();
    if (this.busy) throw new Error('上一次识别仍在进行，请稍候');
    this.busy = true;
    this.statusText = '正在读取当前全屏图片…';
    try {
      if (this.floatWindow) await this.floatWindow.minimize();
      const frame = this.capture.takeStableFrame();
      this.statusText = '正在本机识别六个队伍槽位…';
      const page = await (this.recognition as OwnTeamRecognitionService).recognize(frame);
      const previous = this.loadImportDraft();
      const update: OwnTeamDraftUpdate = acceptOwnTeamPage(previous, page);
      (this.storage as AppStorageRepository).saveOwnTeamImportDraft(update.draft as StoredOwnTeamImportDraft);
      this.statusText = update.message;
      return this.statusText;
    } finally {
      this.busy = false;
      if (this.floatWindow) await this.floatWindow.showWindow();
    }
  }

  loadImportDraft(): OwnTeamImportDraft | undefined {
    const stored = this.storage?.loadOwnTeamImportDraft();
    if (!stored) return undefined;
    return stored as OwnTeamImportDraft;
  }

  loadCorrectionDraft(): OwnTeamCorrectionDraft {
    const draft = this.loadImportDraft();
    if (!draft) throw new Error('还没有识别结果');
    return buildOwnTeamCorrectionDraft(draft);
  }

  saveCorrection(name: string, correction: OwnTeamCorrectionDraft): StoredTeam {
    this.requireConfigured();
    const team = buildSavedOwnTeam(name, correction);
    (this.storage as AppStorageRepository).saveTeam(team);
    (this.storage as AppStorageRepository).clearOwnTeamImportDraft();
    this.statusText = `队伍“${name.trim()}”已保存`;
    return team;
  }

  restartImport(): string {
    this.storage?.clearOwnTeamImportDraft();
    this.statusText = '已放弃上一次草稿，请重新识别“招式与道具”页面';
    return this.statusText;
  }

  async openMainApp(): Promise<void> {
    this.requireConfigured();
    await (this.context as common.UIAbilityContext).startAbility({
      bundleName: 'com.crazylei12.pokemonchampionsassistant',
      abilityName: 'EntryAbility'
    });
  }

  async stop(): Promise<string> {
    this.capture.stop();
    if (this.floatWindow) {
      await this.floatWindow.destroyWindow();
      this.floatWindow = undefined;
    }
    this.statusText = '对局助手已停止，画面会话和悬浮入口均已释放';
    this.busy = false;
    return this.statusText;
  }
}

export const ownTeamCaptureCoordinator: OwnTeamCaptureCoordinator = new OwnTeamCaptureCoordinator();
