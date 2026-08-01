import { common } from '@kit.AbilityKit';
import { display, window } from '@kit.ArkUI';
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
import { isSpeedLinePriorityMove } from '../domain/PresetLogic';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
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
  toTeamDisplay,
  userProfilesForSpecies
} from '../ui/AppUiModels';

const WINDOW_NAME: string = 'pokemon-champions-battle-overlay';
const ELEMENT_KEY: string = 'BATTLE_OVERLAY';

export type BattleOverlayMode = 'panel' | 'hud';
export type BattleOverlaySection = 'DAMAGE' | 'CONDITIONS' | 'SPEED' | 'OPPONENT';

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
  const ability = storedEntityToRef(manual?.ability, 'ability') ?? profile.ability ??
    repository.abilitiesFor(species.showdownId)[0];
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
  private overlayWindow?: window.Window;
  private currentMode: BattleOverlayMode = 'panel';
  private currentSection: BattleOverlaySection = 'DAMAGE';
  private lastMessage: string = '';
  private currentX: number = 24;
  private currentY: number = 80;
  private currentWidth: number = 720;
  private currentHeight: number = 980;

  configure(context: common.UIAbilityContext, catalog: RuntimeDataRepository, storage: AppStorageRepository): void {
    this.context = context;
    this.catalog = catalog;
    this.storage = storage;
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
  }

  private requireConfigured(): void {
    if (!this.context || !this.catalog || !this.storage) throw new Error('对局浮窗尚未准备完成');
  }

  private defaultWindowSize(mode: BattleOverlayMode): { width: number; height: number } {
    const target = display.getDefaultDisplaySync();
    const landscape = target.width >= target.height;
    if (mode === 'hud') {
      return { width: Math.min(landscape ? 1180 : 820, target.width - 48),
        height: Math.min(landscape ? 620 : 1120, target.height - 96) };
    }
    return { width: Math.min(760, target.width - 48), height: Math.min(1080, target.height - 96) };
  }

  private restoredWindowBounds(mode: BattleOverlayMode): { x: number; y: number; width: number; height: number } {
    const target = display.getDefaultDisplaySync();
    const profileKey = battleDirectHudLayoutProfileKey({ left: 0, top: 0, right: target.width, bottom: target.height });
    const profile = profileKey === 'landscape' ? this.storage?.loadHudLayouts().landscape :
      this.storage?.loadHudLayouts().portrait;
    const placement = profile?.elements[ELEMENT_KEY];
    const fallback = this.defaultWindowSize(mode);
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

  async show(mode: BattleOverlayMode): Promise<void> {
    this.requireConfigured();
    this.currentMode = mode;
    this.currentSection = mode === 'hud' ? 'DAMAGE' : this.currentSection;
    const bounds = this.restoredWindowBounds(mode);
    this.currentX = bounds.x; this.currentY = bounds.y;
    this.currentWidth = bounds.width; this.currentHeight = bounds.height;
    if (!this.overlayWindow) {
      this.overlayWindow = await window.createWindow({ name: WINDOW_NAME,
        windowType: window.WindowType.TYPE_FLOAT, ctx: this.context as common.UIAbilityContext });
      await this.overlayWindow.resize(bounds.width, bounds.height);
      await this.overlayWindow.moveWindowTo(bounds.x, bounds.y);
      await this.overlayWindow.loadContent('pages/BattleOverlay');
    } else {
      await this.overlayWindow.resize(bounds.width, bounds.height);
      await this.overlayWindow.moveWindowTo(bounds.x, bounds.y);
    }
    await this.overlayWindow.showWindow();
  }

  async minimize(): Promise<void> {
    if (this.overlayWindow) await this.overlayWindow.minimize();
  }

  async reveal(): Promise<void> {
    if (this.overlayWindow) await this.overlayWindow.showWindow();
  }

  async moveBy(deltaX: number, deltaY: number): Promise<void> {
    if (!this.overlayWindow) return;
    const target = display.getDefaultDisplaySync();
    this.currentX = Math.max(24, Math.min(target.width - this.currentWidth - 24, this.currentX + deltaX));
    this.currentY = Math.max(48, Math.min(target.height - this.currentHeight - 48, this.currentY + deltaY));
    await this.overlayWindow.moveWindowTo(this.currentX, this.currentY);
    this.saveWindowBounds();
  }

  async resizeBy(deltaWidth: number, deltaHeight: number): Promise<void> {
    if (!this.overlayWindow) return;
    const target = display.getDefaultDisplaySync();
    this.currentWidth = Math.max(320, Math.min(target.width - this.currentX - 24, this.currentWidth + deltaWidth));
    this.currentHeight = Math.max(220, Math.min(target.height - this.currentY - 48, this.currentHeight + deltaHeight));
    await this.overlayWindow.resize(this.currentWidth, this.currentHeight);
    this.saveWindowBounds();
  }

  async setHudVisible(visible: boolean): Promise<void> {
    const snapshot = this.snapshot();
    if (snapshot.session) this.saveState({ ...snapshot.state, directHud: { ...snapshot.state.directHud, visible } });
    if (!this.overlayWindow) return;
    if (visible) {
      const size = this.defaultWindowSize('hud');
      this.currentWidth = size.width; this.currentHeight = size.height;
    } else {
      this.currentWidth = 300; this.currentHeight = 120;
    }
    await this.overlayWindow.resize(this.currentWidth, this.currentHeight);
  }

  async close(): Promise<void> {
    if (this.overlayWindow) {
      await this.overlayWindow.destroyWindow();
      this.overlayWindow = undefined;
    }
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

  snapshot(): BattleOverlaySnapshot {
    const stateFallback = defaultBattleCalculation();
    if (!this.storage || !this.catalog) return { ready: false, message: '对局浮窗尚未准备完成',
      mode: this.currentMode, section: this.currentSection, state: stateFallback, ownTeamName: '', ownNames: [],
      opponentNames: [], profiles: [], opponentForms: [], moves: [], speedActions: [] };
    const session = this.savedSession();
    if (!session) return { ready: false, message: this.lastMessage || '请先识别并确认双方阵容',
      mode: this.currentMode, section: this.currentSection, state: stateFallback, ownTeamName: '', ownNames: [],
      opponentNames: [], profiles: [], opponentForms: [], moves: [], speedActions: [] };
    const team = this.selectedOwnTeam(session);
    const displayTeam = team ? toTeamDisplay(team) : undefined;
    if (!team || !displayTeam || displayTeam.pokemon.length !== 6) return { ready: false,
      message: '没有可用于计算的完整我方队伍', mode: this.currentMode, section: this.currentSection,
      session, state: stateFallback, ownTeamName: '', ownNames: [], opponentNames: [], profiles: [], moves: [],
      opponentForms: [], speedActions: [] };
    const state = normalizeBattleCalculation(session.calculationSelection, displayTeam.pokemon.length,
      session.opponentTeam.length);
    const own = displayTeam.pokemon[state.ownSlot];
    const opponentOverride = state.opponentFormOverrides?.[String(state.opponentSlot)];
    const opponent = opponentOverride ? storedEntityToRef(opponentOverride, 'species') as EntityRef :
      entityFromStored(session.opponentTeam[state.opponentSlot]);
    const user = userProfilesForSpecies(this.userPresets(), opponent);
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
      session.opponentTeam.map((entry: StoredEntity) => entityFromStored(entry)));
    return { ready: true, message: this.lastMessage, mode: this.currentMode, section: this.currentSection,
      session: correctedSession, state: correctedState, ownTeam: team,
      ownTeamName: team.teamName ?? team.teamSlotName ?? team.savedTeamId,
      ownNames: displayTeam.pokemon.map((entry: PokemonBuild) => entry.species.displayName ?? entry.species.showdownId),
      opponentNames: session.opponentTeam.map((entry: StoredEntity, index: number) =>
        state.opponentFormOverrides?.[String(index)]?.displayName ?? entry.displayName ?? entry.showdownId),
      own, opponent, profiles, selectedProfile, opponentForms: this.catalog.formsFor(opponent.showdownId)
        .map((entry) => entry.species), moves, speedActions };
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
    if (!this.catalog || (type !== 'ability' && type !== 'item')) return [];
    return this.catalog.searchEntities(type, query, 12);
  }

  setOpponentForm(entity: EntityRef): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    const overrides = { ...(snapshot.state.opponentFormOverrides ?? {}),
      [String(snapshot.state.opponentSlot)]: entityToStored(entity) };
    const presetIds = { ...snapshot.state.opponentPresetIds };
    const manual = { ...(snapshot.state.opponentManualOverrides ?? {}) };
    delete presetIds[String(snapshot.state.opponentSlot)];
    delete manual[String(snapshot.state.opponentSlot)];
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

  setBattleType(value: string): void {
    const snapshot = this.snapshot();
    if (!snapshot.ready) return;
    let state = withBattleCalculationTypeDefaults(snapshot.state, value);
    if (state.battleType === 'SINGLE') state = { ...state, directHud: { ...state.directHud,
      ownSlots: prioritizeBattleDirectHudSlot(state.directHud.ownSlots, state.ownSlot, snapshot.ownNames.length),
      opponentSlots: prioritizeBattleDirectHudSlot(state.directHud.opponentSlots, state.opponentSlot,
        snapshot.opponentNames.length) } };
    this.saveState(state);
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
      inputs.push({ side: 'OPPONENT', slot, name: pokemon.displayName ?? pokemon.showdownId,
        baseSpeed: this.catalog.speedRangeFor(pokemon.showdownId) ?? { minimum: 1, maximum: 1 },
        modifiers: speedModifiers(state, 'OPPONENT', slot), tailwind: state.speedLine.opponentTailwind,
        knownChoiceScarf: false, priorityMoves: [], exactBaseSpeed: false });
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
  }
}

export const battleOverlayCoordinator: BattleOverlayCoordinator = new BattleOverlayCoordinator();
