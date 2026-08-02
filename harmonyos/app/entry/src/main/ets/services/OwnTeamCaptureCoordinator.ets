import { common } from '@kit.AbilityKit';
import { display, window } from '@kit.ArkUI';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import {
  acceptOwnTeamPage,
  blankOwnTeamPage,
  buildOwnTeamCorrectionDraft,
  buildSavedOwnTeam,
  expectedOwnTeamPageType,
  OwnTeamCorrectionDraft,
  OwnTeamImportDraft,
  OwnTeamDraftUpdate,
  OwnTeamPageType,
  RecognizedOwnTeamPage,
  shouldResetAfterEmptyFirstOwnTeamPage
} from '../domain/OwnTeamRecognition';
import { AppStorageRepository } from '../storage/AppStorageRepository';
import { StoredOwnTeamImportDraft, StoredTeam } from '../storage/StorageContracts';
import { OwnTeamRecognitionService } from './OwnTeamRecognitionService';
import { ScreenCaptureService, StableCaptureFrame } from './ScreenCaptureService';
import { TeamPreviewRecognitionResult } from '../domain/TeamPreviewRecognition';
import { StoredTeamPreview } from '../storage/StorageContracts';
import { TeamPreviewRecognitionService } from './TeamPreviewRecognitionService';
import {
  avoidWindowOcclusions,
  clampWindowBounds,
  OcclusionRect,
  SafeAreaInsets,
  snapWindowBoundsToEdge,
  WindowBounds
} from '../ui/AppUiModels';

const FLOAT_WINDOW_NAME: string = 'pokemon-champions-assistant-float';
const FLOAT_HIDE_SETTLE_MS: number = 140;
const FRESH_FRAME_TIMEOUT_MS: number = 4000;
const SLOW_TASK_NOTICE_MS: number = 30000;
const RECOGNITION_TIMEOUT_MS: number = 90000;

function delay(milliseconds: number): Promise<void> {
  return new Promise<void>((resolve: () => void) => setTimeout(resolve, milliseconds));
}

export class OwnTeamCaptureCoordinator {
  private context?: common.UIAbilityContext;
  private storage?: AppStorageRepository;
  private recognition?: OwnTeamRecognitionService;
  private teamPreviewRecognition?: TeamPreviewRecognitionService;
  private capture: ScreenCaptureService = new ScreenCaptureService();
  private floatWindow?: window.Window;
  private statusText: string = '尚未启动对局助手';
  private busy: boolean = false;
  private running: boolean = false;
  private assistantMode: string = 'standard';
  private taskGeneration: number = 0;
  private floatX: number = 24;
  private floatY: number = 120;
  private floatWidth: number = 200;
  private floatHeight: number = 200;
  private sessionInvalidated?: () => void;

  configure(context: common.UIAbilityContext, catalog: RuntimeDataRepository,
    storage: AppStorageRepository, sessionInvalidated?: () => void): void {
    this.context = context;
    this.storage = storage;
    this.recognition = new OwnTeamRecognitionService(catalog);
    this.teamPreviewRecognition = new TeamPreviewRecognitionService(context);
    this.sessionInvalidated = sessionInvalidated;
  }

  private mergeAvoidArea(insets: SafeAreaInsets, area: window.AvoidArea): SafeAreaInsets {
    if (!area.visible) return insets;
    return {
      left: Math.max(insets.left, area.leftRect.width),
      top: Math.max(insets.top, area.topRect.height),
      right: Math.max(insets.right, area.rightRect.width),
      bottom: Math.max(insets.bottom, area.bottomRect.height)
    };
  }

  private safeInsets(includeKeyboard: boolean = false): SafeAreaInsets {
    let insets: SafeAreaInsets = { left: 24, top: 48, right: 24, bottom: 48 };
    if (!this.floatWindow) return insets;
    const types: window.AvoidAreaType[] = [window.AvoidAreaType.TYPE_SYSTEM, window.AvoidAreaType.TYPE_CUTOUT,
      window.AvoidAreaType.TYPE_SYSTEM_GESTURE];
    if (includeKeyboard) types.push(window.AvoidAreaType.TYPE_KEYBOARD);
    for (const type of types) {
      try {
        insets = this.mergeAvoidArea(insets, this.floatWindow.getWindowAvoidArea(type));
      } catch (_error) {
        // Some floating-window implementations do not expose every avoid-area type.
      }
    }
    return insets;
  }

  private currentFloatBounds(): WindowBounds {
    return { x: this.floatX, y: this.floatY, width: this.floatWidth, height: this.floatHeight };
  }

  private foldOcclusions(target: display.Display): OcclusionRect[] {
    try {
      const region = display.getCurrentFoldCreaseRegion();
      if (region.displayId !== target.id) return [];
      return region.creaseRects.map((rect: display.Rect): OcclusionRect => ({
        left: rect.left, top: rect.top, width: rect.width, height: rect.height
      }));
    } catch (_error) {
      return [];
    }
  }

  private safeFloatBounds(bounds: WindowBounds, target: display.Display): WindowBounds {
    const insets = this.safeInsets();
    const clamped = clampWindowBounds(bounds, target.width, target.height, insets, 160, 160);
    return avoidWindowOcclusions(clamped, target.width, target.height, insets, 160, 160,
      this.foldOcclusions(target));
  }

  private invalidateRunningSession(message: string): void {
    this.running = false;
    this.taskGeneration += 1;
    this.statusText = message;
    if (this.floatWindow) {
      const staleWindow = this.floatWindow;
      this.floatWindow = undefined;
      staleWindow.destroyWindow().catch((_error: Error) => {});
    }
    if (this.sessionInvalidated) this.sessionInvalidated();
  }

  status(): string {
    return this.statusText;
  }

  isBusy(): boolean {
    return this.busy;
  }

  isRunning(): boolean {
    if (this.running) {
      const stats = this.capture.stats();
      if (!stats.ok || !stats.running) {
        this.invalidateRunningSession('画面会话已由系统结束，悬浮界面已清理；请重新启动对局助手');
      }
    }
    return this.running;
  }

  mode(): string {
    return this.assistantMode;
  }

  private requireConfigured(): void {
    if (!this.context || !this.storage || !this.recognition) throw new Error('对局助手尚未准备完成');
  }

  private assertCurrentTask(generation: number): void {
    if (generation !== this.taskGeneration || !this.running) {
      throw new Error('画面会话或本次识别已结束，旧结果不会保存');
    }
    const stats = this.capture.stats();
    if (!stats.ok || !stats.running) {
      this.invalidateRunningSession('画面授权已撤销或目标窗口已关闭，悬浮界面已清理；请重新启动对局助手');
      throw new Error('画面授权已撤销或目标窗口已关闭，请重新启动对局助手');
    }
  }

  private async takeFreshFrame(generation: number, teamPreview: boolean): Promise<StableCaptureFrame> {
    if (this.floatWindow) await this.floatWindow.minimize();
    await delay(FLOAT_HIDE_SETTLE_MS);
    this.assertCurrentTask(generation);
    const invalidated = this.capture.invalidateLatestFrame();
    if (!invalidated.ok) throw new Error(`无法隔离助手浮窗画面（${invalidated.code}）：${invalidated.message}`);
    const baseline: number = this.capture.stats().frameGeneration;
    const deadline = Date.now() + FRESH_FRAME_TIMEOUT_MS;
    while (Date.now() < deadline) {
      this.assertCurrentTask(generation);
      const stats = this.capture.stats();
      if (stats.hasStableFrame && !stats.frameInvalidated && stats.contentVisible && stats.frameGeneration > baseline) {
        const frame = teamPreview ? this.capture.takeTeamPreviewFrame() : this.capture.takeStableFrame();
        if (frame.generation <= baseline) throw new Error('捕获服务返回了浮窗隐藏前的旧画面，请重试');
        return frame;
      }
      await delay(32);
    }
    throw new Error('等待浮窗离开画面后的新帧超时，请确认目标窗口仍在前台');
  }

  private async runWithRecognitionDeadline<T>(generation: number, task: Promise<T>): Promise<T> {
    let slowTimer: number = -1;
    let timeoutTimer: number = -1;
    slowTimer = setTimeout(() => {
      if (generation === this.taskGeneration && this.running) {
        this.statusText = '本机识别仍在进行，可继续等待；超时后旧结果不会写入';
      }
    }, SLOW_TASK_NOTICE_MS);
    const timeout = new Promise<T>((_resolve: (value: T) => void, reject: (reason?: Error) => void) => {
      timeoutTimer = setTimeout(() => reject(new Error('本机识别超过 90 秒，已放弃本次结果，请重试')), RECOGNITION_TIMEOUT_MS);
    });
    try {
      const result = await Promise.race<T>([task, timeout]);
      this.assertCurrentTask(generation);
      return result;
    } catch (error) {
      if (generation === this.taskGeneration && String(error).includes('超过 90 秒')) this.taskGeneration += 1;
      throw new Error(String(error));
    } finally {
      if (slowTimer >= 0) clearTimeout(slowTimer);
      if (timeoutTimer >= 0) clearTimeout(timeoutTimer);
    }
  }

  async startAssistant(mode: string = 'standard'): Promise<string> {
    this.requireConfigured();
    this.taskGeneration += 1;
    const result = this.capture.requestAuthorization();
    if (!result.ok) throw new Error(`无法启动画面读取（${result.code}）：${result.message}`);
    this.running = true;
    this.assistantMode = mode === 'hud' ? 'hud' : 'standard';
    this.statusText = '已向系统请求画面授权；请由你在系统界面选择是否允许，并选择相册窗口';
    if (mode === 'standard') await this.showFloatWindow();
    return this.statusText;
  }

  async attachPreparedAssistant(mode: string = 'standard', status?: string): Promise<string> {
    this.requireConfigured();
    this.taskGeneration += 1;
    this.running = true;
    this.assistantMode = mode === 'hud' ? 'hud' : 'standard';
    this.statusText = status ?? '系统画面会话已启动；请选择相册窗口';
    if (mode === 'standard') await this.showFloatWindow();
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
    await created.setWindowFocusable(false);
    await created.setWindowTouchable(true);
    await created.resize(this.floatWidth, this.floatHeight);
    this.floatWindow = created;
    const currentDisplay = display.getDefaultDisplaySync();
    const initial = this.safeFloatBounds({ x: currentDisplay.width - this.floatWidth - 24,
      y: Math.floor(currentDisplay.height * 0.2), width: this.floatWidth, height: this.floatHeight },
      currentDisplay);
    this.floatX = initial.x; this.floatY = initial.y;
    await created.moveWindowTo(initial.x, initial.y);
    await created.loadContent('pages/FloatAssistant');
    await created.showWindow();
  }

  async resizeFloatWindow(expanded: boolean): Promise<void> {
    if (!this.floatWindow) return;
    const currentDisplay = display.getDefaultDisplaySync();
    const requested: WindowBounds = { x: this.floatX, y: this.floatY,
      width: expanded ? 760 : 200, height: expanded ? 680 : 200 };
    const next = this.safeFloatBounds(requested, currentDisplay);
    this.floatX = next.x; this.floatY = next.y; this.floatWidth = next.width; this.floatHeight = next.height;
    await this.floatWindow.resize(next.width, next.height);
    await this.floatWindow.moveWindowTo(next.x, next.y);
  }

  async moveFloatWindowBy(deltaX: number, deltaY: number): Promise<void> {
    if (!this.floatWindow) return;
    const target = display.getDefaultDisplaySync();
    const current = this.currentFloatBounds();
    const requested: WindowBounds = { x: this.floatX + Math.round(deltaX),
      y: this.floatY + Math.round(deltaY), width: current.width, height: current.height };
    const next = this.safeFloatBounds(requested, target);
    this.floatX = next.x; this.floatY = next.y;
    await this.floatWindow.moveWindowTo(next.x, next.y);
  }

  async snapFloatWindowToEdge(): Promise<void> {
    if (!this.floatWindow) return;
    const target = display.getDefaultDisplaySync();
    const snapped = snapWindowBoundsToEdge(this.currentFloatBounds(), target.width, target.height,
      this.safeInsets(), 160, 160);
    const next = this.safeFloatBounds(snapped, target);
    this.floatX = next.x; this.floatY = next.y;
    await this.floatWindow.moveWindowTo(next.x, next.y);
  }

  async minimizeFloatWindow(): Promise<void> {
    if (this.floatWindow) await this.floatWindow.minimize();
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
    if (!this.isRunning()) throw new Error('画面会话尚未运行，请先启动对局助手');
    const previous = this.loadImportDraft();
    const expectedType = expectedOwnTeamPageType(previous);
    if (!expectedType) {
      this.statusText = '两张队伍页面均已处理，请继续核对并补全内容';
      await this.openMainApp();
      return this.statusText;
    }
    let generation = ++this.taskGeneration;
    this.busy = true;
    this.statusText = expectedType === 'STATS' ? '正在读取能力值页面…' : '正在读取招式与道具页面…';
    try {
      let frame: StableCaptureFrame;
      try {
        frame = await this.takeFreshFrame(generation, false);
      } catch (_error) {
        if (!this.running) throw new Error('画面会话已结束，本次识别结果不会保存');
        generation = this.taskGeneration;
        if (expectedType === 'MOVE_ITEM') {
          return this.resetAfterEmptyFirstOwnTeamPage('暂时无法读取我方队伍的招式与道具页面');
        }
        const fallback = blankOwnTeamPage(expectedType, 1, 1);
        const update = acceptOwnTeamPage(previous, fallback);
        (this.storage as AppStorageRepository).saveOwnTeamImportDraft(update.draft as StoredOwnTeamImportDraft);
        this.statusText = '暂时无法读取能力值页，已转入核对页；未识别内容可手动补充';
        return this.statusText;
      }
      this.statusText = expectedType === 'STATS' ? '正在本机识别能力值，最长等待 90 秒…' :
        '正在本机识别招式与道具，最长等待 90 秒…';
      let page: RecognizedOwnTeamPage;
      let usedBlankFallback: boolean = false;
      try {
        page = await this.runWithRecognitionDeadline(generation,
          (this.recognition as OwnTeamRecognitionService).recognize(frame, expectedType));
      } catch (error) {
        if (!this.running) throw new Error('画面会话已结束，本次识别结果不会保存');
        generation = this.taskGeneration;
        this.assertCurrentTask(generation);
        page = blankOwnTeamPage(expectedType, frame.width, frame.height, new Date().toISOString(), frame.hash);
        usedBlankFallback = true;
      }
      this.assertCurrentTask(generation);
      if (shouldResetAfterEmptyFirstOwnTeamPage(page)) {
        return this.resetAfterEmptyFirstOwnTeamPage('没有识别到我方宝可梦、道具或招式');
      }
      const update: OwnTeamDraftUpdate = acceptOwnTeamPage(previous, page);
      this.assertCurrentTask(generation);
      (this.storage as AppStorageRepository).saveOwnTeamImportDraft(update.draft as StoredOwnTeamImportDraft);
      this.statusText = usedBlankFallback ?
        '能力值页没有识别成功，已转入核对页；未识别内容可手动补充' : update.message;
      return this.statusText;
    } finally {
      this.busy = false;
      if (this.floatWindow) await this.floatWindow.showWindow();
    }
  }

  ownTeamRecognitionButtonLabel(): string {
    if (this.busy) return '识别进行中';
    const expected = expectedOwnTeamPageType(this.loadImportDraft());
    if (expected === 'STATS') return '识别能力值';
    if (expected === 'MOVE_ITEM') return '识别招式/道具';
    return '继续核对';
  }

  expectedOwnTeamPage(): OwnTeamPageType | undefined {
    return expectedOwnTeamPageType(this.loadImportDraft());
  }

  async recognizeTeamPreview(): Promise<string> {
    this.requireConfigured();
    if (this.busy) throw new Error('上一次识别仍在进行，请稍候');
    if (!this.teamPreviewRecognition) throw new Error('双方阵容识别器尚未准备完成');
    if (!this.isRunning()) throw new Error('画面会话尚未运行，请先启动对局助手');
    const generation = ++this.taskGeneration;
    this.busy = true;
    this.statusText = '正在读取当前双方阵容预览……';
    try {
      const frame = await this.takeFreshFrame(generation, true);
      this.statusText = '正在本机识别双方十二个阵容位置……';
      const result = await this.runWithRecognitionDeadline(generation, this.teamPreviewRecognition.recognize(frame));
      this.assertCurrentTask(generation);
      (this.storage as AppStorageRepository).saveCurrentTeamPreview(result as StoredTeamPreview);
      const pending = [...result.ownTeamCandidates, ...result.opponentTeamCandidates]
        .filter((slot) => slot.requiresConfirmation).length;
      this.statusText = pending === 0 ? '双方阵容识别完成，请返回应用逐项确认十二个位置' :
        `双方阵容识别完成，其中 ${pending} 个位置置信度较低，请返回应用重点核对`;
      return this.statusText;
    } finally {
      this.busy = false;
      if (this.floatWindow) await this.floatWindow.showWindow();
    }
  }

  loadTeamPreview(): TeamPreviewRecognitionResult | undefined {
    const stored = this.storage?.loadCurrentTeamPreview();
    return stored as TeamPreviewRecognitionResult | undefined;
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

  private resetAfterEmptyFirstOwnTeamPage(reason: string): string {
    (this.storage as AppStorageRepository).discardOwnTeamImport();
    this.statusText = `${reason}，已放弃本次识别；请确认当前显示的是我方队伍的“招式与道具”页面后重新开始`;
    return this.statusText;
  }

  restartImport(): string {
    this.storage?.discardOwnTeamImport();
    this.statusText = '已放弃本次队伍识别并复位，请重新识别“招式与道具”页面';
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
    this.taskGeneration += 1;
    this.capture.invalidateLatestFrame();
    this.capture.stop();
    if (this.floatWindow) {
      await this.floatWindow.destroyWindow();
      this.floatWindow = undefined;
    }
    this.statusText = '对局助手已停止，画面会话和悬浮入口均已释放';
    this.busy = false;
    this.running = false;
    this.assistantMode = 'standard';
    if (this.sessionInvalidated) this.sessionInvalidated();
    return this.statusText;
  }
}

export const ownTeamCaptureCoordinator: OwnTeamCaptureCoordinator = new OwnTeamCaptureCoordinator();
