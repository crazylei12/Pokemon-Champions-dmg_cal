import { common } from '@kit.AbilityKit';
import { display, window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  battleCondition,
  battleDirectHudLayoutProfileKey,
  battleDirectHudSlotsPerSide,
  BattleCalculationState,
  BattleSide,
  buildBattleDamageRequest,
  buildSpeedLineActions,
  defaultBattleCalculation,
  includeBattleDirectHudSlot,
  normalizeBattleCalculation,
  prioritizeBattleDirectHudSlot,
  replaceBattleDirectHudSlot,
  SpeedLineAction,
  SpeedLinePokemonInput,
  speedModifiers,
  withBattleCondition,
  withBattleCalculationTypeDefaults,
  withOpponentPreset,
  withOpponentSlot,
  withSpeedModifiers
} from '../domain/BattleSession';
import { EntityRef, MoveValue, OpponentProfile, PokemonBuild, SpeedRange } from '../domain/Models';
import { defaultAbilityForTarget, isSpeedLinePriorityMove } from '../domain/PresetLogic';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import {
  buildBattleSessionFromSetup,
  buildTeamPreviewReview,
  replaceTeamPreviewSlot,
  teamPreviewReadyForSession,
  TeamPreviewRecognitionResult,
  TeamPreviewReviewDraft,
  TeamPreviewReviewSlot
} from '../domain/TeamPreviewRecognition';
import { AppStorageRepository } from '../storage/AppStorageRepository';
import {
  HudLayoutProfile,
  HudLayoutRoot,
  StoredBattleSession,
  StoredCalculationSelection,
  StoredEntity,
  StoredManualOverride,
  StoredTeam,
  UserOpponentPresetRoot
} from '../storage/StorageContracts';
import {
  avoidWindowOcclusions,
  BattlePanelNavigation,
  buildsShareConfiguration,
  clampWindowBounds,
  OcclusionRect,
  priorityMovesForSpecies,
  SafeAreaInsets,
  snapWindowBoundsToEdge,
  toTeamDisplay,
  userProfilesForSpecies
} from '../ui/AppUiModels';
import { safeUiErrorCode } from '../ui/PrivacySafeError';

const PANEL_WINDOW_NAME: string = 'pokemon-champions-battle-panel';
const ELEMENT_KEY: string = 'BATTLE_OVERLAY';
const REVISION_KEY: string = 'battleOverlayRevision';
const DOMAIN: number = 0x5043;
const DISPLAY_REFLOW_SETTLE_DELAY_MS: number = 250;

export type BattleHudElement = 'EDIT' | 'REMATCH' | 'TOGGLE' | 'RECORDING' | 'FORMAT' |
  'OWN_RECOGNITION' | 'SPEED' | 'STATUS' | 'ASSUMPTION' | 'OPPONENT_LEFT' |
  'OPPONENT_RIGHT' | 'OWN_LEFT' | 'OWN_RIGHT' | 'DAMAGE' | 'DETAIL';

interface OverlayWindowBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

interface HudAnchor {
  x: number;
  y: number;
  width?: number;
  centered?: boolean;
}

export type BattleOverlayMode = 'panel' | 'hud' | 'setup';
export type BattleOverlaySection = 'DAMAGE' | 'CONDITIONS' | 'SPEED' | 'OPPONENT';

export interface BattleSetupTeamOption {
  teamId: string;
  label: string;
  matchCount: number;
}

export interface BattleSetupSnapshot {
  ready: boolean;
  message: string;
  teams: BattleSetupTeamOption[];
  selectedTeamId: string;
  opponents: TeamPreviewReviewSlot[];
  pendingOpponentConfirmations: number[];
  canConfirm: boolean;
}

export interface BattleOverlaySnapshot {
  ready: boolean;
  message: string;
  mode: BattleOverlayMode;
  section: BattleOverlaySection;
  session?: StoredBattleSession;
  state: BattleCalculationState;
  ownTeam?: StoredTeam;
  ownTeamName: string;
  ownNames: string[];
  opponentNames: string[];
  own?: PokemonBuild;
  opponent?: EntityRef;
  profiles: OpponentProfile[];
  selectedProfile?: OpponentProfile;
  opponentForms: EntityRef[];
  moves: MoveValue[];
  speedActions: SpeedLineAction[];
}

export interface PreparedBattleDamage {
  request: string;
  configuredMoves: MoveValue[];
}

function entityFromStored(value: StoredEntity): EntityRef {
  return {
    entityType: 'species', canonicalId: value.canonicalId, showdownId: value.showdownId,
    displayName: value.displayName ?? value.showdownId, source: 'user'
  };
}

function entityToStored(value: EntityRef): StoredEntity {
  return { entityType: value.entityType, canonicalId: value.canonicalId, showdownId: value.showdownId,
    displayName: value.displayName ?? value.showdownId };
}

function storedEntityToRef(value: StoredEntity | undefined, type: string): EntityRef | undefined {
  if (!value) return undefined;
  return {
    entityType: type as 'species' | 'move' | 'ability' | 'item' | 'nature' | 'type',
    canonicalId: value.canonicalId, showdownId: value.showdownId,
    displayName: value.displayName ?? value.showdownId, source: 'user'
  };
}

function normalizedId(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function selectMove(moves: MoveValue[]): MoveValue | undefined {
  return moves.find((move: MoveValue) => (move.basePower ?? 0) > 0) ?? moves[0];
}

function profileWithRuntime(repository: RuntimeDataRepository, species: EntityRef,
  profile: OpponentProfile, manual: StoredManualOverride | undefined): OpponentProfile {
  const points = manual?.statPoints ?? profile.statPoints ?? {};
  const nature = storedEntityToRef(manual?.statAlignment, 'nature') ?? profile.statAlignment;
  const ability = defaultAbilityForTarget(storedEntityToRef(manual?.ability, 'ability') ?? profile.ability,
    repository.abilitiesFor(species.showdownId), repository.formFor(species.showdownId)?.defaultAbility);
  let item = profile.item;
  if (manual?.itemOverrideEnabled === true) item = storedEntityToRef(manual.item, 'item');
  return {
    ...profile,
    statPoints: points,
    statAlignment: nature,
    actualStats: repository.actualStatsFor(species.showdownId, points, nature),
    ability,
    item,
    moves: (profile.moves ?? []).length > 0 ? profile.moves : repository.legalMovesFor(species.showdownId).slice(0, 4)
  };
}

export class BattleOverlayCoordinator {
  private context?: common.UIAbilityContext;
  private catalog?: RuntimeDataRepository;
  private storage?: AppStorageRepository;
  private panelWindow?: window.Window;
  private hudWindows: Map<string, window.Window> = new Map<string, window.Window>();
  private hudBounds: Map<string, OverlayWindowBounds> = new Map<string, OverlayWindowBounds>();
  private currentMode: BattleOverlayMode = 'panel';
  private currentSection: BattleOverlaySection = 'DAMAGE';
  private panelNavigation: BattlePanelNavigation = new BattlePanelNavigation();
  private snapshotCache?: BattleOverlaySnapshot;
  private lastMessage: string = '';
  private currentX: number = 24;
  private currentY: number = 80;
  private currentWidth: number = 720;
  private currentHeight: number = 980;
  private layoutEditing: boolean = false;
  private setupDraft?: TeamPreviewReviewDraft;
  private setupTeams: BattleSetupTeamOption[] = [];
  private setupSelectedTeamId: string = '';
  private displayChangeListener?: (displayId: number) => void;
  private immediateDisplayReflowTimer: number = -1;
  private settledDisplayReflowTimer: number = -1;
  private displayReflowRunning: boolean = false;
  private displayReflowPending: boolean = false;
  private lastDisplayWidth: number = 0;
  private lastDisplayHeight: number = 0;
  private lastDisplayRotation: number = -1;
  private replayEnabled: boolean = false;
  private panelInputActive: boolean = false;

  configure(context: common.UIAbilityContext, catalog: RuntimeDataRepository, storage: AppStorageRepository,
    replayEnabled: boolean = false): void {
    this.context = context;
    this.catalog = catalog;
    this.storage = storage;
    this.replayEnabled = replayEnabled;
    AppStorage.setOrCreate<number>(REVISION_KEY, AppStorage.get<number>(REVISION_KEY) ?? 0);
    this.ensureDisplayChangeListener();
  }

  isConfigured(): boolean {
    return !!this.context && !!this.catalog && !!this.storage;
  }

  mode(): BattleOverlayMode {
    return this.currentMode;
  }

  section(): BattleOverlaySection {
    return this.currentSection;
  }

  setSection(section: BattleOverlaySection): void {
    this.currentSection = section;
    this.panelNavigation.show(section);
    this.notifyChanged();
  }

  isLayoutEditing(): boolean {
    return this.layoutEditing;
  }

  activeHudWindowCount(): number {
    return this.hudWindows.size;
  }

  hasPanelWindow(): boolean {
    return !!this.panelWindow;
  }

  notifyChanged(): void {
    this.snapshotCache = undefined;
    const current = AppStorage.get<number>(REVISION_KEY) ?? 0;
    AppStorage.setOrCreate<number>(REVISION_KEY, current + 1);
  }

  private foldOcclusions(target: display.Display): OcclusionRect[] {
    try {
      const region = display.getCurrentFoldCreaseRegion();
      if (region.displayId !== target.id) return [];
      return region.creaseRects.map((rect: display.Rect): OcclusionRect => ({
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height
      }));
    } catch (_error) {
      return [];
    }
  }

  private safeBounds(bounds: OverlayWindowBounds, target: display.Display, current: window.Window,
    minimumWidth: number, minimumHeight: number, includeKeyboard: boolean = false): OverlayWindowBounds {
    const insets = this.safeInsetsFor(current, includeKeyboard);
    const clamped = clampWindowBounds(bounds, target.width, target.height, insets, minimumWidth, minimumHeight);
    return avoidWindowOcclusions(clamped, target.width, target.height, insets, minimumWidth, minimumHeight,
      this.foldOcclusions(target));
  }

  private requireConfigured(): void {
    if (!this.context || !this.catalog || !this.storage) throw new Error('对局浮窗尚未准备完成');
  }

  private ensureDisplayChangeListener(): void {
    if (this.displayChangeListener) return;
    this.displayChangeListener = (_displayId: number): void => {
      this.scheduleDisplayReflow(0);
      this.scheduleDisplayReflow(DISPLAY_REFLOW_SETTLE_DELAY_MS);
    };
    display.on('change', this.displayChangeListener);
  }

  private scheduleDisplayReflow(delayMs: number): void {
    const settled = delayMs > 0;
    const currentTimer = settled ? this.settledDisplayReflowTimer : this.immediateDisplayReflowTimer;
    if (currentTimer >= 0) clearTimeout(currentTimer);
    const timer = setTimeout(() => {
      if (settled) this.settledDisplayReflowTimer = -1;
      else this.immediateDisplayReflowTimer = -1;
      this.runDisplayReflow();
    }, delayMs);
    if (settled) this.settledDisplayReflowTimer = timer;
    else this.immediateDisplayReflowTimer = timer;
  }

  private cancelDisplayReflowTimers(): void {
    if (this.immediateDisplayReflowTimer >= 0) clearTimeout(this.immediateDisplayReflowTimer);
    if (this.settledDisplayReflowTimer >= 0) clearTimeout(this.settledDisplayReflowTimer);
    this.immediateDisplayReflowTimer = -1;
    this.settledDisplayReflowTimer = -1;
    this.displayReflowPending = false;
  }

  private async runDisplayReflow(): Promise<void> {
    if (this.displayReflowRunning) {
      this.displayReflowPending = true;
      return;
    }
    this.displayReflowRunning = true;
    try {
      await this.reflowOpenWindowsForCurrentDisplay();
    } catch (error) {
      hilog.error(DOMAIN, 'PCApp', 'battle overlay display reflow failed code=%{public}s', safeUiErrorCode(error));
    } finally {
      this.displayReflowRunning = false;
      if (this.displayReflowPending) {
        this.displayReflowPending = false;
        this.runDisplayReflow();
      }
    }
  }

  private rememberCurrentDisplay(): display.Display {
    const target = display.getDefaultDisplaySync();
    this.lastDisplayWidth = target.width;
    this.lastDisplayHeight = target.height;
    this.lastDisplayRotation = target.rotation;
    return target;
  }

  private displayMetricsChanged(target: display.Display): boolean {
    return target.width !== this.lastDisplayWidth || target.height !== this.lastDisplayHeight ||
      target.rotation !== this.lastDisplayRotation;
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

  private safeInsetsFor(current: window.Window, includeKeyboard: boolean = false): SafeAreaInsets {
    let insets: SafeAreaInsets = { left: 0, top: 0, right: 0, bottom: 0 };
    const types: window.AvoidAreaType[] = [window.AvoidAreaType.TYPE_SYSTEM, window.AvoidAreaType.TYPE_CUTOUT,
      window.AvoidAreaType.TYPE_SYSTEM_GESTURE];
    if (includeKeyboard) types.push(window.AvoidAreaType.TYPE_KEYBOARD);
    for (const type of types) {
      try {
        insets = this.mergeAvoidArea(insets, current.getWindowAvoidArea(type));
      } catch (_error) {
        // Floating windows may not expose every avoid-area type on every device.
      }
    }
    return insets;
  }

  private async reflowOpenWindowsForCurrentDisplay(): Promise<void> {
    const target = display.getDefaultDisplaySync();
    const metricsChanged = this.displayMetricsChanged(target);

    if (this.currentMode === 'hud' && this.hudWindows.size > 0) {
      const snapshot = this.snapshot();
      for (const [key, current] of this.hudWindows.entries()) {
        const element = key as BattleHudElement;
        try {
          const requested = metricsChanged ? this.restoredHudBounds(element, snapshot.ready, snapshot.state.battleType) :
            this.hudBounds.get(element) ?? this.restoredHudBounds(element, snapshot.ready, snapshot.state.battleType);
          const minimum = this.minimumHudSize(element,
            this.hudDesiredSize(element, snapshot.ready, snapshot.state.battleType));
          const bounds = this.safeBounds(requested, target, current, minimum.width, minimum.height);
          await current.resize(bounds.width, bounds.height);
          await current.moveWindowTo(bounds.x, bounds.y);
          this.hudBounds.set(element, bounds);
        } catch (error) {
          hilog.error(DOMAIN, 'PCApp', 'HUD element reflow failed element=%{public}s code=%{public}s',
            element, safeUiErrorCode(error));
        }
      }
    }

    if (this.panelWindow) {
      const requested = metricsChanged ? this.restoredPanelBounds() :
        { x: this.currentX, y: this.currentY, width: this.currentWidth, height: this.currentHeight };
      const bounds = this.safeBounds(requested, target, this.panelWindow, 320, 220, this.panelInputActive);
      this.currentX = bounds.x;
      this.currentY = bounds.y;
      this.currentWidth = bounds.width;
      this.currentHeight = bounds.height;
      await this.panelWindow.resize(bounds.width, bounds.height);
      await this.panelWindow.moveWindowTo(bounds.x, bounds.y);
    }
    this.lastDisplayWidth = target.width;
    this.lastDisplayHeight = target.height;
    this.lastDisplayRotation = target.rotation;
    this.notifyChanged();
  }

  private defaultPanelSize(): { width: number; height: number } {
    const target = display.getDefaultDisplaySync();
    return { width: Math.min(760, target.width - 48), height: Math.min(1080, target.height - 96) };
  }

  private restoredPanelBounds(): OverlayWindowBounds {
    const target = display.getDefaultDisplaySync();
    const profileKey = battleDirectHudLayoutProfileKey({ left: 0, top: 0, right: target.width, bottom: target.height });
    const profile = profileKey === 'landscape' ? this.storage?.loadHudLayouts().landscape :
      this.storage?.loadHudLayouts().portrait;
    const placement = profile?.elements[ELEMENT_KEY];
    const fallback = this.defaultPanelSize();
    if (!placement) return { x: Math.max(24, target.width - fallback.width - 24), y: 72,
      width: fallback.width, height: fallback.height };
    const width = Math.max(320, Math.min(target.width - 48, Math.round(target.width * placement.width)));
    const height = Math.max(220, Math.min(target.height - 96, Math.round(target.height * placement.height)));
    const x = Math.max(24, Math.min(target.width - width - 24, Math.round(target.width * placement.x)));
    const y = Math.max(48, Math.min(target.height - height - 48, Math.round(target.height * placement.y)));
    return { x, y, width, height };
  }

  private saveWindowBounds(): void {
    if (!this.storage) return;
    const target = display.getDefaultDisplaySync();
    const root: HudLayoutRoot = this.storage.loadHudLayouts();
    const profile: HudLayoutProfile = {
      elements: {
        ...((target.width >= target.height ? root.landscape : root.portrait)?.elements ?? {}),
        [ELEMENT_KEY]: {
          x: this.currentX / Math.max(1, target.width), y: this.currentY / Math.max(1, target.height),
          width: this.currentWidth / Math.max(1, target.width), height: this.currentHeight / Math.max(1, target.height)
        }
      }
    };
    const next: HudLayoutRoot = { ...root };
    if (target.width >= target.height) next.landscape = profile;
    else next.portrait = profile;
    this.storage.saveHudLayouts(next);
  }

  private density(): number {
    return Math.max(1, display.getDefaultDisplaySync().densityPixels);
  }

  private dp(value: number): number {
    return Math.max(1, Math.round(value * this.density()));
  }

  private hudAnchor(element: BattleHudElement): HudAnchor {
    if (element === 'EDIT') return { x: 0.295, y: 0.015, centered: true };
    if (element === 'REMATCH') return { x: 0.38, y: 0.015, centered: true };
    if (element === 'TOGGLE') return { x: 0.465, y: 0.015, centered: true };
    if (element === 'RECORDING') return { x: 0.55, y: 0.015, centered: true };
    if (element === 'FORMAT') return { x: 0.635, y: 0.015, centered: true };
    if (element === 'OWN_RECOGNITION') return { x: 0.75, y: 0.015, centered: true };
    if (element === 'SPEED') return { x: 0.015, y: 0.266, width: 0.205 };
    if (element === 'STATUS') return { x: 0.015, y: 0.092 };
    if (element === 'ASSUMPTION') return { x: 0.775, y: 0.335 };
    if (element === 'OPPONENT_LEFT') return { x: 0.591, y: 0.158, width: 0.192 };
    if (element === 'OPPONENT_RIGHT') return { x: 0.797, y: 0.158, width: 0.203 };
    if (element === 'OWN_LEFT') return { x: 0.053, y: 0.762, width: 0.188 };
    if (element === 'OWN_RIGHT') return { x: 0.251, y: 0.762, width: 0.193 };
    if (element === 'DAMAGE') return { x: 0.021, y: 0.665, width: 0.43 };
    return { x: 0.937, y: 0.328 };
  }

  private hudDesiredSize(element: BattleHudElement, ready: boolean, battleType: string): { width: number; height: number } {
    if (element === 'EDIT' || element === 'REMATCH' || element === 'FORMAT') return { width: 64, height: 30 };
    if (element === 'TOGGLE' || element === 'OWN_RECOGNITION') return { width: 84, height: 30 };
    if (element === 'RECORDING') return { width: 70, height: 30 };
    if (element === 'SPEED') return { width: 180, height: battleType === 'DOUBLE' ? 154 : 96 };
    if (element === 'STATUS') return { width: ready ? 150 : 180, height: 34 };
    if (element === 'ASSUMPTION') return { width: 112, height: 32 };
    if (element === 'DAMAGE') return { width: 300, height: 40 };
    if (element === 'DETAIL') return { width: 64, height: 54 };
    return { width: 170, height: 38 };
  }

  private minimumHudSize(element: BattleHudElement, desired: { width: number; height: number }):
    { width: number; height: number } {
    if (element === 'SPEED') return { width: this.dp(120), height: this.dp(90) };
    if (element === 'DAMAGE') return { width: this.dp(180), height: this.dp(36) };
    if (element === 'OPPONENT_LEFT' || element === 'OPPONENT_RIGHT' || element === 'OWN_LEFT' ||
      element === 'OWN_RIGHT') return { width: this.dp(110), height: this.dp(34) };
    return { width: this.dp(Math.min(desired.width, 56)), height: this.dp(Math.min(desired.height, 30)) };
  }

  private restoredHudBounds(element: BattleHudElement, ready: boolean, battleType: string): OverlayWindowBounds {
    const target = display.getDefaultDisplaySync();
    const desired = this.hudDesiredSize(element, ready, battleType);
    const anchor = this.hudAnchor(element);
    const fallbackWidth = anchor.width === undefined ? this.dp(desired.width) : Math.round(target.width * anchor.width);
    const fallbackHeight = this.dp(desired.height);
    const minimum = this.minimumHudSize(element, desired);
    const profileKey = battleDirectHudLayoutProfileKey({ left: 0, top: 0, right: target.width, bottom: target.height });
    const profile = profileKey === 'landscape' ? this.storage?.loadHudLayouts().landscape :
      this.storage?.loadHudLayouts().portrait;
    const placement = profile?.elements[element];
    if (placement) {
      const width = Math.max(minimum.width, Math.min(target.width, Math.round(target.width * placement.width)));
      const height = Math.max(minimum.height, Math.min(target.height, Math.round(target.height * placement.height)));
      const x = Math.max(0, Math.min(target.width - width, Math.round(target.width * placement.x)));
      const y = Math.max(0, Math.min(target.height - height, Math.round(target.height * placement.y)));
      return { x, y, width, height };
    }
    const anchorX = Math.round(target.width * anchor.x);
    const x = Math.max(0, Math.min(target.width - fallbackWidth,
      anchor.centered === true ? anchorX - Math.round(fallbackWidth / 2) : anchorX));
    const y = Math.max(0, Math.min(target.height - fallbackHeight, Math.round(target.height * anchor.y)));
    return { x, y, width: fallbackWidth, height: fallbackHeight };
  }

  private saveHudBounds(element: BattleHudElement, bounds: OverlayWindowBounds): void {
    if (!this.storage) return;
    const target = display.getDefaultDisplaySync();
    const root = this.storage.loadHudLayouts();
    const existing = (target.width >= target.height ? root.landscape : root.portrait)?.elements ?? {};
    const profile: HudLayoutProfile = { elements: { ...existing, [element]: {
      x: bounds.x / Math.max(1, target.width), y: bounds.y / Math.max(1, target.height),
      width: bounds.width / Math.max(1, target.width), height: bounds.height / Math.max(1, target.height)
    } } };
    const next: HudLayoutRoot = { ...root };
    if (target.width >= target.height) next.landscape = profile;
    else next.portrait = profile;
    this.storage.saveHudLayouts(next);
  }

  private hudElements(): BattleHudElement[] {
    const snapshot = this.snapshot();
    const elements: BattleHudElement[] = ['REMATCH', 'TOGGLE'];
    if (this.replayEnabled) elements.push('RECORDING');
    elements.push('FORMAT', 'OWN_RECOGNITION');
    if (!snapshot.ready) return [...elements, 'STATUS'];
    if (!snapshot.state.directHud.visible) return [...elements, 'EDIT'];
    elements.push('SPEED', 'STATUS', 'ASSUMPTION');
    if (snapshot.state.battleType === 'DOUBLE') elements.push('OPPONENT_LEFT');
    elements.push('OPPONENT_RIGHT');
    elements.push('OWN_LEFT');
    if (snapshot.state.battleType === 'DOUBLE') elements.push('OWN_RIGHT');
    elements.push('DAMAGE', 'DETAIL', 'EDIT');
    return elements;
  }

  private interactiveElement(element: BattleHudElement): boolean {
    return element !== 'SPEED' && element !== 'DAMAGE';
  }

  private hudPage(element: BattleHudElement): string {
    if (element === 'EDIT') return 'pages/BattleHudEdit';
    if (element === 'REMATCH') return 'pages/BattleHudRematch';
    if (element === 'TOGGLE') return 'pages/BattleHudToggle';
    if (element === 'RECORDING') return 'pages/BattleHudRecording';
    if (element === 'FORMAT') return 'pages/BattleHudFormat';
    if (element === 'OWN_RECOGNITION') return 'pages/BattleHudOwnRecognition';
    if (element === 'SPEED') return 'pages/BattleHudSpeed';
    if (element === 'STATUS') return 'pages/BattleHudStatus';
    if (element === 'ASSUMPTION') return 'pages/BattleHudAssumption';
    if (element === 'OPPONENT_LEFT') return 'pages/BattleHudOpponentLeft';
    if (element === 'OPPONENT_RIGHT') return 'pages/BattleHudOpponentRight';
    if (element === 'OWN_LEFT') return 'pages/BattleHudOwnLeft';
    if (element === 'OWN_RIGHT') return 'pages/BattleHudOwnRight';
    if (element === 'DAMAGE') return 'pages/BattleHudDamage';
    return 'pages/BattleHudDetail';
  }

  private async destroyPanelWindow(): Promise<void> {
    if (!this.panelWindow) return;
    await this.panelWindow.destroyWindow();
    this.panelWindow = undefined;
    this.panelInputActive = false;
  }

  private async destroyHudWindows(): Promise<void> {
    for (const current of this.hudWindows.values()) await current.destroyWindow();
    this.hudWindows.clear();
    this.hudBounds.clear();
  }

  private async createHudWindows(): Promise<void> {
    this.rememberCurrentDisplay();
    const snapshot = this.snapshot();
    for (const element of this.hudElements()) {
      const requested = this.restoredHudBounds(element, snapshot.ready, snapshot.state.battleType);
      const created = await window.createWindow({ name: `pokemon-champions-hud-${element.toLowerCase()}`,
        windowType: window.WindowType.TYPE_FLOAT, ctx: this.context as common.UIAbilityContext });
      await created.setWindowFocusable(false);
      await created.setWindowTouchable(this.layoutEditing || this.interactiveElement(element));
      await created.resize(requested.width, requested.height);
      await created.moveWindowTo(requested.x, requested.y);
      await created.loadContent(this.hudPage(element));
      await created.showWindow();
      const target = display.getDefaultDisplaySync();
      const minimum = this.minimumHudSize(element, this.hudDesiredSize(element, snapshot.ready, snapshot.state.battleType));
      const bounds = this.safeBounds(requested, target, created, minimum.width, minimum.height);
      await created.resize(bounds.width, bounds.height);
      await created.moveWindowTo(bounds.x, bounds.y);
      this.hudWindows.set(element, created);
      this.hudBounds.set(element, bounds);
    }
  }

  private async rebuildHudWindows(): Promise<void> {
    if (this.currentMode !== 'hud') return;
    await this.destroyHudWindows();
    await this.createHudWindows();
    this.notifyChanged();
  }

  async show(mode: BattleOverlayMode): Promise<void> {
    this.requireConfigured();
    this.currentMode = mode;
    this.snapshotCache = undefined;
    if (mode === 'hud') {
      await this.destroyPanelWindow();
      await this.destroyHudWindows();
      await this.createHudWindows();
      this.notifyChanged();
      return;
    }
    if (mode === 'panel') this.currentSection = this.panelNavigation.reopen() as BattleOverlaySection;
    await this.destroyHudWindows();
    this.rememberCurrentDisplay();
    const bounds = this.restoredPanelBounds();
    this.currentX = bounds.x; this.currentY = bounds.y;
    this.currentWidth = bounds.width; this.currentHeight = bounds.height;
    if (!this.panelWindow) {
      this.panelWindow = await window.createWindow({ name: PANEL_WINDOW_NAME,
        windowType: window.WindowType.TYPE_FLOAT, ctx: this.context as common.UIAbilityContext });
      await this.panelWindow.setWindowFocusable(false);
      await this.panelWindow.setWindowTouchable(true);
      await this.panelWindow.resize(bounds.width, bounds.height);
      await this.panelWindow.moveWindowTo(bounds.x, bounds.y);
      await this.panelWindow.loadContent('pages/BattleOverlay');
    } else {
      await this.panelWindow.resize(bounds.width, bounds.height);
      await this.panelWindow.moveWindowTo(bounds.x, bounds.y);
    }
    await this.panelWindow.showWindow();
    this.scheduleDisplayReflow(0);
    this.notifyChanged();
  }

  private setupMatchCount(team: StoredTeam, draft: TeamPreviewReviewDraft): number {
    const recognized = new Set<string>(draft.own.map((slot: TeamPreviewReviewSlot) =>
      normalizedId(slot.selected?.showdownId ?? '')).filter((value: string) => value.length > 0));
    return (team.pokemon ?? team.members ?? []).filter((member) =>
      recognized.has(normalizedId(member.species.showdownId))).length;
  }

  async showSetup(): Promise<void> {
    this.requireConfigured();
    this.panelNavigation.resetForTeamRecognition();
    this.currentSection = 'DAMAGE';
    const stored = this.storage?.loadCurrentTeamPreview() as TeamPreviewRecognitionResult | undefined;
    if (!stored) throw new Error('请先识别双方阵容');
    const draft = buildTeamPreviewReview(stored);
    const teams = (this.storage?.loadManagedState().savedTeams ?? []).filter((team: StoredTeam) => {
      const displayTeam = toTeamDisplay(team);
      return displayTeam.pokemon.length === 6 && displayTeam.damageReady;
    });
    if (teams.length === 0) throw new Error('还没有可用于对局的完整队伍，请先录入六只宝可梦的完整配置');
    this.setupDraft = draft;
    this.setupTeams = teams.map((team: StoredTeam): BattleSetupTeamOption => {
      const matchCount = this.setupMatchCount(team, draft);
      return { teamId: team.savedTeamId,
        label: `${team.teamName ?? team.teamSlotName ?? team.savedTeamId} · 与识别阵容匹配 ${matchCount}/6`, matchCount };
    });
    const suggested = this.setupTeams.slice().sort((left: BattleSetupTeamOption, right: BattleSetupTeamOption) =>
      right.matchCount - left.matchCount)[0];
    this.setupSelectedTeamId = suggested?.teamId ?? this.setupTeams[0].teamId;
    this.confirmOwnTeamSelection(this.setupSelectedTeamId);
    await this.show('setup');
  }

  setupSnapshot(): BattleSetupSnapshot {
    const opponents = this.setupDraft?.opponent ?? [];
    const pending = opponents.filter((slot: TeamPreviewReviewSlot) =>
      slot.recognitionRisk && !slot.confirmed).map((slot: TeamPreviewReviewSlot) => slot.slotIndex);
    return { ready: !!this.setupDraft && this.setupTeams.length > 0,
      message: this.lastMessage, teams: this.setupTeams.slice(), selectedTeamId: this.setupSelectedTeamId,
      opponents, pendingOpponentConfirmations: pending,
      canConfirm: !!this.setupDraft && this.setupSelectedTeamId.length > 0 &&
        teamPreviewReadyForSession(this.setupDraft) };
  }

  private confirmOwnTeamSelection(teamId: string): void {
    const team = (this.storage?.loadManagedState().savedTeams ?? [])
      .find((candidate: StoredTeam) => candidate.savedTeamId === teamId);
    if (!team || !this.setupDraft) return;
    const members = team.pokemon ?? team.members ?? [];
    let draft = this.setupDraft;
    draft.own.forEach((slot: TeamPreviewReviewSlot, index: number) => {
      const member = members[index];
      if (member) draft = replaceTeamPreviewSlot(draft, 'own', slot.slotIndex, entityFromStored(member.species), true);
    });
    this.setupDraft = draft;
  }

  setSetupTeam(teamId: string): void {
    if (this.setupTeams.some((team: BattleSetupTeamOption) => team.teamId === teamId)) {
      this.setupSelectedTeamId = teamId;
      this.confirmOwnTeamSelection(teamId);
      this.notifyChanged();
    }
  }

  setSetupOpponentCandidate(slotIndex: number, candidateIndex: number): void {
    const draft = this.setupDraft;
    const slot = draft?.opponent.find((entry: TeamPreviewReviewSlot) => entry.slotIndex === slotIndex);
    const candidate = slot?.candidates[candidateIndex];
    if (!draft || !candidate) return;
    const entity: EntityRef = { entityType: 'species', canonicalId: candidate.canonicalId,
      showdownId: candidate.showdownId, displayName: candidate.displayName, source: 'system' };
    this.setupDraft = replaceTeamPreviewSlot(draft, 'opponent', slotIndex, entity, true);
    this.notifyChanged();
  }

  setSetupOpponent(slotIndex: number, entity: EntityRef): void {
    if (!this.setupDraft) return;
    this.setupDraft = replaceTeamPreviewSlot(this.setupDraft, 'opponent', slotIndex, entity, true);
    this.notifyChanged();
  }

  confirmSetupOpponent(slotIndex: number): void {
    const slot = this.setupDraft?.opponent.find((entry: TeamPreviewReviewSlot) => entry.slotIndex === slotIndex);
    if (!this.setupDraft || !slot?.selected) return;
    this.setupDraft = replaceTeamPreviewSlot(this.setupDraft, 'opponent', slotIndex, slot.selected, true);
    this.notifyChanged();
  }

  confirmSetup(): StoredBattleSession {
    if (!this.storage || !this.setupDraft) throw new Error('双方阵容核对面板尚未准备完成');
    if (!teamPreviewReadyForSession(this.setupDraft)) throw new Error('请先逐项确认所有低置信度识别结果');
    const session = buildBattleSessionFromSetup(this.setupDraft, this.setupSelectedTeamId);
    this.storage.saveCurrentBattleSession(session);
    this.lastMessage = '本局阵容已确认';
    this.notifyChanged();
    return session;
  }

  async minimize(): Promise<void> {
    if (this.panelWindow) await this.panelWindow.minimize();
    for (const current of this.hudWindows.values()) await current.minimize();
  }

  async reveal(): Promise<void> {
    if (this.panelWindow) await this.panelWindow.showWindow();
    for (const current of this.hudWindows.values()) await current.showWindow();
  }

  async moveBy(deltaX: number, deltaY: number): Promise<void> {
    if (!this.panelWindow) return;
    const target = display.getDefaultDisplaySync();
    const next = this.safeBounds({ x: this.currentX + deltaX, y: this.currentY + deltaY,
      width: this.currentWidth, height: this.currentHeight }, target, this.panelWindow, 320, 220,
      this.panelInputActive);
    this.currentX = next.x;
    this.currentY = next.y;
    await this.panelWindow.moveWindowTo(this.currentX, this.currentY);
    this.saveWindowBounds();
  }

  async snapPanelToEdge(): Promise<void> {
    if (!this.panelWindow) return;
    const target = display.getDefaultDisplaySync();
    const snapped = snapWindowBoundsToEdge({ x: this.currentX, y: this.currentY, width: this.currentWidth,
      height: this.currentHeight }, target.width, target.height,
      this.safeInsetsFor(this.panelWindow, this.panelInputActive), 320, 220);
    const next = this.safeBounds(snapped, target, this.panelWindow, 320, 220, this.panelInputActive);
    this.currentX = next.x;
    this.currentY = next.y;
    await this.panelWindow.moveWindowTo(next.x, next.y);
    this.saveWindowBounds();
  }

  async setPanelInputActive(active: boolean): Promise<void> {
    if (!this.panelWindow || this.panelInputActive === active) return;
    this.panelInputActive = active;
    await this.panelWindow.setWindowTouchable(true);
    await this.panelWindow.setWindowFocusable(active);
    this.scheduleDisplayReflow(active ? DISPLAY_REFLOW_SETTLE_DELAY_MS : 0);
  }

  async resizeBy(deltaWidth: number, deltaHeight: number): Promise<void> {
    if (!this.panelWindow) return;
    const target = display.getDefaultDisplaySync();
    const next = this.safeBounds({ x: this.currentX, y: this.currentY,
      width: this.currentWidth + deltaWidth, height: this.currentHeight + deltaHeight },
      target, this.panelWindow, 320, 220, this.panelInputActive);
    this.currentX = next.x; this.currentY = next.y;
    this.currentWidth = next.width; this.currentHeight = next.height;
    await this.panelWindow.resize(this.currentWidth, this.currentHeight);
    await this.panelWindow.moveWindowTo(this.currentX, this.currentY);
    this.saveWindowBounds();
  }

  async setHudVisible(visible: boolean): Promise<void> {
    const snapshot = this.snapshot();
    if (snapshot.session) this.saveState({ ...snapshot.state, directHud: { ...snapshot.state.directHud, visible } });
    await this.rebuildHudWindows();
  }

  async toggleLayoutEditing(): Promise<void> {
    this.layoutEditing = !this.layoutEditing;
    await this.rebuildHudWindows();
  }

  async moveHudElementBy(element: BattleHudElement, deltaX: number, deltaY: number): Promise<void> {
    const current = this.hudWindows.get(element);
    const bounds = this.hudBounds.get(element);
    if (!current || !bounds || !this.layoutEditing || element === 'EDIT') return;
    const target = display.getDefaultDisplaySync();
    const next: OverlayWindowBounds = this.safeBounds({ ...bounds,
      x: bounds.x + Math.round(deltaX), y: bounds.y + Math.round(deltaY) }, target, current,
      bounds.width, bounds.height);
    await current.moveWindowTo(next.x, next.y);
    this.hudBounds.set(element, next);
    this.saveHudBounds(element, next);
  }

  async resizeHudElementBy(element: BattleHudElement, deltaWidth: number, deltaHeight: number): Promise<void> {
    const current = this.hudWindows.get(element);
    const bounds = this.hudBounds.get(element);
    if (!current || !bounds || !this.layoutEditing || element === 'EDIT') return;
    const snapshot = this.snapshot();
    const target = display.getDefaultDisplaySync();
    const minimum = this.minimumHudSize(element, this.hudDesiredSize(element, snapshot.ready, snapshot.state.battleType));
    const next: OverlayWindowBounds = this.safeBounds({ ...bounds,
      width: bounds.width + Math.round(deltaWidth), height: bounds.height + Math.round(deltaHeight) },
      target, current, minimum.width, minimum.height);
    await current.resize(next.width, next.height);
    await current.moveWindowTo(next.x, next.y);
    this.hudBounds.set(element, next);
    this.saveHudBounds(element, next);
  }

  async close(): Promise<void> {
    await this.destroyPanelWindow();
    await this.destroyHudWindows();
    this.panelNavigation.collapse();
    this.layoutEditing = false;
    this.notifyChanged();
  }

  async destroy(): Promise<void> {
    if (this.displayChangeListener) {
      display.off('change', this.displayChangeListener);
      this.displayChangeListener = undefined;
    }
    this.cancelDisplayReflowTimers();
    await this.close();
  }

  private savedSession(): StoredBattleSession | undefined {
    return this.storage?.loadManagedState().currentBattleSession;
  }

  private selectedOwnTeam(session: StoredBattleSession): StoredTeam | undefined {
    const teams = this.storage?.loadManagedState().savedTeams ?? [];
    return teams.find((team: StoredTeam) => team.savedTeamId === session.selectedOwnTeamId) ?? teams[0];
  }

  private userPresets(): UserOpponentPresetRoot {
    return this.storage?.loadUserOpponentPresets().root ??
      { schemaVersion: 1, kind: 'OpponentUserPresets', presets: [] };
  }

  private rememberSnapshot(snapshot: BattleOverlaySnapshot): BattleOverlaySnapshot {
    this.snapshotCache = snapshot;
    return snapshot;
  }

  snapshot(): BattleOverlaySnapshot {
    if (this.snapshotCache) return this.snapshotCache;
    const stateFallback = defaultBattleCalculation();
    if (!this.storage || !this.catalog) return this.rememberSnapshot({ ready: false, message: '对局浮窗尚未准备完成',
      mode: this.currentMode, section: this.currentSection, state: stateFallback, ownTeamName: '', ownNames: [],
      opponentNames: [], profiles: [], opponentForms: [], moves: [], speedActions: [] });
    const session = this.savedSession();
    if (!session) return this.rememberSnapshot({ ready: false, message: this.lastMessage || '请先识别并确认双方阵容',
      mode: this.currentMode, section: this.currentSection, state: stateFallback, ownTeamName: '', ownNames: [],
      opponentNames: [], profiles: [], opponentForms: [], moves: [], speedActions: [] });
    const team = this.selectedOwnTeam(session);
    const displayTeam = team ? toTeamDisplay(team) : undefined;
    if (!team || !displayTeam || displayTeam.pokemon.length !== 6) return this.rememberSnapshot({ ready: false,
      message: '没有可用于计算的完整我方队伍', mode: this.currentMode, section: this.currentSection,
      session, state: stateFallback, ownTeamName: '', ownNames: [], opponentNames: [], profiles: [], moves: [],
      opponentForms: [], speedActions: [] });
    const state = normalizeBattleCalculation(session.calculationSelection, displayTeam.pokemon.length,
      session.opponentTeam.length);
    const own = displayTeam.pokemon[state.ownSlot];
    const opponentOverride = state.opponentFormOverrides?.[String(state.opponentSlot)];
    const opponent = opponentOverride ? storedEntityToRef(opponentOverride, 'species') as EntityRef :
      entityFromStored(session.opponentTeam[state.opponentSlot]);
    const user = userProfilesForSpecies(this.userPresets(), opponent, this.catalog);
    const profiles = this.catalog.profilesFor(opponent.showdownId, user);
    const selectedBase = profiles.find((profile: OpponentProfile) => profile.profileId === state.selectedPresetId) ??
      profiles[0];
    const manual = state.opponentManualOverrides?.[String(state.opponentSlot)];
    const selectedProfile = selectedBase ? profileWithRuntime(this.catalog, opponent, selectedBase, manual) : undefined;
    const moves = state.direction === 'OWN_TO_OPPONENT' ? own.moves :
      ((selectedProfile?.moves ?? []).length > 0 ? selectedProfile?.moves ?? [] : this.catalog.legalMovesFor(opponent.showdownId));
    const correctedState = this.ensureMoveSelection({ ...state,
      selectedPresetId: selectedProfile?.profileId }, moves);
    const correctedSession: StoredBattleSession = { ...session, selectedOwnTeamId: team.savedTeamId,
      calculationSelection: correctedState };
    const speedActions = this.speedActions(correctedState, displayTeam.pokemon,
      session.opponentTeam.map((entry: StoredEntity, index: number) => {
        const form = correctedState.opponentFormOverrides?.[String(index)];
        return form ? storedEntityToRef(form, 'species') as EntityRef : entityFromStored(entry);
      }));
    return this.rememberSnapshot({ ready: true, message: this.lastMessage, mode: this.currentMode, section: this.currentSection,
      session: correctedSession, state: correctedState, ownTeam: team,
      ownTeamName: team.teamName ?? team.teamSlotName ?? team.savedTeamId,
      ownNames: displayTeam.pokemon.map((entry: PokemonBuild) => entry.species.displayName ?? entry.species.showdownId),
      opponentNames: session.opponentTeam.map((entry: StoredEntity, index: number) =>
        state.opponentFormOverrides?.[String(index)]?.displayName ?? entry.displayName ?? entry.showdownId),
      own, opponent, profiles, selectedProfile, opponentForms: this.catalog.formsFor(opponent.showdownId)
        .map((entry) => entry.species), moves, speedActions });
  }

  private ensureMoveSelection(state: BattleCalculationState, moves: MoveValue[]): BattleCalculationState {
    const selected = moves.find((move: MoveValue) => normalizedId(move.entity.showdownId) ===
      normalizedId(state.selectedMoveId ?? ''));
    return selected ? state : { ...state, selectedMoveId: selectMove(moves)?.entity.showdownId };
  }

  private saveState(state: BattleCalculationState): void {
    const session = this.savedSession();
    if (!session || !this.storage) throw new Error('当前没有可保存的对局');
    this.storage.saveCurrentBattleSession({ ...session, calculationSelection: state });
    this.notifyChanged();
  }

  async showPanelSection(section: BattleOverlaySection): Promise<void> {
    this.currentSection = section;
    this.panelNavigation.show(section);
    await this.show('panel');
  }

  async collapsePanel(): Promise<void> {
    this.panelNavigation.collapse();
    await this.destroyPanelWindow();
    this.notifyChanged();
  }

  async refreshHudStructure(): Promise<void> {
    await this.rebuildHudWindows();
  }

  setDirection(direction: string): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    const state = { ...snapshot.state, direction: direction === 'OPPONENT_TO_OWN' ?
      'OPPONENT_TO_OWN' : 'OWN_TO_OPPONENT', selectedMoveId: undefined };
    this.saveState(state);
  }

  setOwnSlot(slot: number): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    const target = Math.max(0, Math.min(snapshot.ownNames.length - 1, slot));
    this.saveState({ ...snapshot.state, ownSlot: target, selectedMoveId: undefined,
      directHud: { ...snapshot.state.directHud,
        ownSlots: includeBattleDirectHudSlot(snapshot.state.directHud.ownSlots, target, snapshot.ownNames.length) } });
  }

  setOpponentSlot(slot: number): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    const target = Math.max(0, Math.min(snapshot.opponentNames.length - 1, slot));
    const state = withOpponentSlot(snapshot.state, target);
    this.saveState({ ...state, selectedMoveId: undefined,
      directHud: { ...state.directHud,
        opponentSlots: includeBattleDirectHudSlot(state.directHud.opponentSlots, target,
          snapshot.opponentNames.length) } });
  }

  replaceHudSlot(side: BattleSide, displayIndex: number, slot: number): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    if (side === 'OWN') {
      const directHud = { ...snapshot.state.directHud, ownSlots: replaceBattleDirectHudSlot(
        snapshot.state.directHud.ownSlots, displayIndex, slot, snapshot.ownNames.length) };
      this.saveState({ ...snapshot.state, ownSlot: slot, selectedMoveId: undefined, directHud });
    } else {
      const state = withOpponentSlot(snapshot.state, slot);
      const directHud = { ...state.directHud, opponentSlots: replaceBattleDirectHudSlot(
        state.directHud.opponentSlots, displayIndex, slot, snapshot.opponentNames.length) };
      this.saveState({ ...state, selectedMoveId: undefined, directHud });
    }
  }

  setPreset(profileId: string): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    const profile = snapshot.profiles.find((entry: OpponentProfile) => entry.profileId === profileId);
    if (!profile) return;
    let state = withOpponentPreset(snapshot.state, profileId);
    const overrides = { ...(state.opponentManualOverrides ?? {}) };
    delete overrides[String(state.opponentSlot)];
    if (state.direction === 'OPPONENT_TO_OWN') state = { ...state,
      selectedMoveId: selectMove(profile.moves ?? [])?.entity.showdownId };
    this.saveState({ ...state, opponentManualOverrides: overrides });
  }

  searchEntities(type: string, query: string): EntityRef[] {
    if (!this.catalog || (type !== 'species' && type !== 'ability' && type !== 'item')) return [];
    return this.catalog.searchEntities(type as 'species' | 'ability' | 'item', query, 12);
  }

  setOpponentForm(entity: EntityRef): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready || !snapshot.opponent || !this.catalog) return;
    const sharesConfiguration = buildsShareConfiguration(this.catalog, snapshot.opponent, entity);
    const overrides = { ...(snapshot.state.opponentFormOverrides ?? {}),
      [String(snapshot.state.opponentSlot)]: entityToStored(entity) };
    const presetIds = { ...snapshot.state.opponentPresetIds };
    const manual = { ...(snapshot.state.opponentManualOverrides ?? {}) };
    const slot = String(snapshot.state.opponentSlot);
    if (sharesConfiguration) {
      const profiles = this.catalog.profilesFor(entity.showdownId,
        userProfilesForSpecies(this.userPresets(), entity, this.catalog));
      const selected = profiles.find((profile: OpponentProfile) =>
        profile.profileId === snapshot.selectedProfile?.profileId) ?? profiles[0];
      if (selected) presetIds[slot] = selected.profileId;
      const currentManual = manual[slot];
      if (currentManual && selected) {
        const currentAbility = storedEntityToRef(currentManual.ability, 'ability') ?? snapshot.selectedProfile?.ability;
        const nextAbility = defaultAbilityForTarget(currentAbility, this.catalog.abilitiesFor(entity.showdownId),
          this.catalog.formFor(entity.showdownId)?.defaultAbility);
        manual[slot] = { ...currentManual, baseProfileId: selected.profileId,
          ability: nextAbility ? entityToStored(nextAbility) : undefined };
      }
      this.saveState({ ...snapshot.state, opponentFormOverrides: overrides,
        opponentPresetIds: presetIds, selectedPresetId: selected?.profileId,
        opponentManualOverrides: manual, selectedMoveId: undefined });
      return;
    }
    delete presetIds[slot];
    delete manual[slot];
    this.saveState({ ...snapshot.state, opponentFormOverrides: overrides,
      opponentPresetIds: presetIds, selectedPresetId: undefined, opponentManualOverrides: manual,
      selectedMoveId: undefined });
  }

  setOpponentPoint(key: string, value: number): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready || !snapshot.selectedProfile) return;
    const slot = String(snapshot.state.opponentSlot);
    const current = snapshot.state.opponentManualOverrides?.[slot] ??
      { baseProfileId: snapshot.selectedProfile.profileId };
    const points = { ...(current.statPoints ?? snapshot.selectedProfile.statPoints ?? {}) };
    const normalized = Math.max(0, Math.min(32, Math.trunc(value)));
    if (key === 'hp') points.hp = normalized;
    else if (key === 'atk') points.atk = normalized;
    else if (key === 'def') points.def = normalized;
    else if (key === 'spa') points.spa = normalized;
    else if (key === 'spd') points.spd = normalized;
    else if (key === 'spe') points.spe = normalized;
    const overrides = { ...(snapshot.state.opponentManualOverrides ?? {}),
      [slot]: { ...current, baseProfileId: snapshot.selectedProfile.profileId, statPoints: points } };
    this.saveState({ ...snapshot.state, opponentManualOverrides: overrides });
  }

  setOpponentAbility(entity: EntityRef | undefined): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready || !snapshot.selectedProfile) return;
    const slot = String(snapshot.state.opponentSlot);
    const current = snapshot.state.opponentManualOverrides?.[slot] ??
      { baseProfileId: snapshot.selectedProfile.profileId };
    const next: StoredManualOverride = { ...current, baseProfileId: snapshot.selectedProfile.profileId };
    if (entity) next.ability = entityToStored(entity);
    else next.ability = undefined;
    const overrides = { ...(snapshot.state.opponentManualOverrides ?? {}), [slot]: next };
    this.saveState({ ...snapshot.state, opponentManualOverrides: overrides });
  }

  setOpponentItem(entity: EntityRef | undefined, overrideEnabled: boolean): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready || !snapshot.selectedProfile) return;
    const slot = String(snapshot.state.opponentSlot);
    const current = snapshot.state.opponentManualOverrides?.[slot] ??
      { baseProfileId: snapshot.selectedProfile.profileId };
    const next: StoredManualOverride = { ...current, baseProfileId: snapshot.selectedProfile.profileId,
      itemOverrideEnabled: overrideEnabled };
    next.item = entity ? entityToStored(entity) : undefined;
    const overrides = { ...(snapshot.state.opponentManualOverrides ?? {}), [slot]: next };
    this.saveState({ ...snapshot.state, opponentManualOverrides: overrides });
  }

  setMove(moveId: string): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    this.saveState({ ...snapshot.state, selectedMoveId: moveId });
  }

  async setBattleType(value: string): Promise<void> {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    let state = withBattleCalculationTypeDefaults(snapshot.state, value);
    if (state.battleType === 'SINGLE') state = { ...state, directHud: { ...state.directHud,
      ownSlots: prioritizeBattleDirectHudSlot(state.directHud.ownSlots, state.ownSlot, snapshot.ownNames.length),
      opponentSlots: prioritizeBattleDirectHudSlot(state.directHud.opponentSlots, state.opponentSlot,
        snapshot.opponentNames.length) } };
    this.saveState(state);
    if (this.currentMode === 'hud') await this.rebuildHudWindows();
  }

  setEnum(key: string, value: string): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    if (key === 'weather') this.saveState({ ...snapshot.state, weather: value });
    else if (key === 'terrain') this.saveState({ ...snapshot.state, terrain: value });
  }

  setFlag(key: string, value: boolean): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    let state = snapshot.state;
    if (key === 'ownReflect') state = { ...state, ownReflect: value };
    else if (key === 'ownLightScreen') state = { ...state, ownLightScreen: value };
    else if (key === 'ownAuroraVeil') state = { ...state, ownAuroraVeil: value };
    else if (key === 'opponentReflect') state = { ...state, opponentReflect: value };
    else if (key === 'opponentLightScreen') state = { ...state, opponentLightScreen: value };
    else if (key === 'opponentAuroraVeil') state = { ...state, opponentAuroraVeil: value };
    else if (key === 'ownProtected') state = { ...state, ownProtected: value };
    else if (key === 'opponentProtected') state = { ...state, opponentProtected: value };
    else if (key === 'helpingHand') state = { ...state, helpingHand: state.battleType === 'DOUBLE' && value };
    else if (key === 'critical') state = { ...state, critical: value };
    else if (key === 'spread') state = { ...state, spread: state.battleType === 'DOUBLE' && value };
    else if (key === 'ownTailwind') state = { ...state, speedLine: { ...state.speedLine, ownTailwind: value } };
    else if (key === 'opponentTailwind') state = { ...state,
      speedLine: { ...state.speedLine, opponentTailwind: value } };
    else if (key === 'trickRoom') state = { ...state, speedLine: { ...state.speedLine, trickRoom: value } };
    else if (key === 'ownBurned') state = withBattleCondition(state, 'OWN',
      { ...battleCondition(state, 'OWN'), burned: value });
    else if (key === 'opponentBurned') state = withBattleCondition(state, 'OPPONENT',
      { ...battleCondition(state, 'OPPONENT'), burned: value });
    this.saveState(state);
  }

  setStage(side: BattleSide, key: string, value: number): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    const condition = battleCondition(snapshot.state, side);
    const stages = { ...condition.stages };
    const normalized = Math.max(-6, Math.min(6, Math.trunc(value)));
    if (key === 'atk') stages.atk = normalized;
    else if (key === 'def') stages.def = normalized;
    else if (key === 'spa') stages.spa = normalized;
    else if (key === 'spd') stages.spd = normalized;
    else if (key === 'spe') stages.spe = normalized;
    this.saveState(withBattleCondition(snapshot.state, side, { ...condition, stages }));
  }

  setSpeedModifier(side: BattleSide, key: string, value: number | boolean): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    const modifiers = speedModifiers(snapshot.state, side);
    if (key === 'stage') modifiers.stage = Number(value);
    else if (key === 'paralyzed') modifiers.paralyzed = value === true;
    else if (key === 'doubled') modifiers.doubled = value === true;
    else if (key === 'choiceScarf') modifiers.choiceScarf = value === true;
    this.saveState(withSpeedModifiers(snapshot.state, side, modifiers));
  }

  private speedActions(state: BattleCalculationState, ownTeam: PokemonBuild[], opponents: EntityRef[]): SpeedLineAction[] {
    if (!this.catalog) return [];
    const perSide = battleDirectHudSlotsPerSide(state.battleType);
    const ownSlots = perSide === 1 ? prioritizeBattleDirectHudSlot(state.directHud.ownSlots,
      state.ownSlot, ownTeam.length) : state.directHud.ownSlots;
    const opponentSlots = perSide === 1 ? prioritizeBattleDirectHudSlot(state.directHud.opponentSlots,
      state.opponentSlot, opponents.length) : state.directHud.opponentSlots;
    const inputs: SpeedLinePokemonInput[] = [];
    for (const slot of ownSlots.slice(0, perSide)) {
      const pokemon = ownTeam[slot];
      if (!pokemon) continue;
      const known = pokemon.actualStats?.spe;
      const range: SpeedRange = known && known > 0 ? { minimum: known, maximum: known } :
        (this.catalog.speedRangeFor(pokemon.species.showdownId) ?? { minimum: 1, maximum: 1 });
      inputs.push({ side: 'OWN', slot, name: pokemon.species.displayName ?? pokemon.species.showdownId,
        baseSpeed: range, modifiers: speedModifiers(state, 'OWN', slot), tailwind: state.speedLine.ownTailwind,
        knownChoiceScarf: normalizedId(pokemon.item?.showdownId ?? '') === 'choicescarf',
        priorityMoves: pokemon.moves.filter((move: MoveValue) => isSpeedLinePriorityMove(move)),
        exactBaseSpeed: !!known });
    }
    for (const slot of opponentSlots.slice(0, perSide)) {
      const pokemon = opponents[slot];
      if (!pokemon) continue;
      const profiles = this.catalog.profilesFor(pokemon.showdownId,
        userProfilesForSpecies(this.userPresets(), pokemon, this.catalog));
      const selectedId = state.opponentPresetIds[String(slot)];
      const selectedBase = profiles.find((profile: OpponentProfile) => profile.profileId === selectedId) ?? profiles[0];
      const selected = selectedBase ? profileWithRuntime(this.catalog, pokemon, selectedBase,
        state.opponentManualOverrides?.[String(slot)]) : undefined;
      const known = selected?.actualStats?.spe;
      const range = known && known > 0 ? { minimum: known, maximum: known } :
        (this.catalog.speedRangeFor(pokemon.showdownId) ?? { minimum: 1, maximum: 1 });
      inputs.push({ side: 'OPPONENT', slot, name: pokemon.displayName ?? pokemon.showdownId,
        baseSpeed: range,
        modifiers: speedModifiers(state, 'OPPONENT', slot), tailwind: state.speedLine.opponentTailwind,
        knownChoiceScarf: normalizedId(selected?.item?.showdownId ?? '') === 'choicescarf',
        priorityMoves: priorityMovesForSpecies(this.catalog, pokemon, selected?.moves ?? []),
        exactBaseSpeed: !!known });
    }
    return buildSpeedLineActions(inputs, state.speedLine.trickRoom);
  }

  prepareDamage(allOwnMoves: boolean = false): PreparedBattleDamage {
    const snapshot = this.snapshot();
    if (!snapshot.ready || !snapshot.own || !snapshot.opponent || !snapshot.selectedProfile) {
      throw new Error(snapshot.message || '当前对局不能计算');
    }
    const request = buildBattleDamageRequest({ own: snapshot.own, opponent: snapshot.opponent,
      preset: snapshot.selectedProfile, legalMoves: this.catalog?.legalMovesFor(snapshot.opponent.showdownId) ?? [],
      calculation: snapshot.state, allOwnMoves });
    return { request, configuredMoves: snapshot.own.moves.slice(0, 4) };
  }

  setMessage(message: string): void {
    this.lastMessage = message;
    this.notifyChanged();
  }
}

export const battleOverlayCoordinator: BattleOverlayCoordinator = new BattleOverlayCoordinator();
