import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { webview } from '@kit.ArkWeb';
import { RELEASE_VARIANT, REPLAY_ENABLED } from 'BuildProfile';
import pcbridge, { BridgeInfo } from 'libpcbridge.so';
import { DamageEngineRuntime } from '../domain/DamageEngineRuntime';
import { EntityRef, EntityType, MoveValue, OpponentProfile, PokemonBuild, StatValues } from '../domain/Models';
import { RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import { getSelectedReplayMode } from '../domain/ReplaySession';
import {
  OwnTeamCorrectionDraft,
  OwnTeamCorrectionSlot,
  ownTeamCorrectionComplete,
  requiredOwnTeamMoveCount,
  unresolvedOwnTeamFields
} from '../domain/OwnTeamRecognition';
import {
  allTeamPreviewSlotsConfirmed,
  buildBattleSession,
  buildTeamPreviewReview,
  matchingSavedOwnTeams,
  replaceTeamPreviewSlot,
  TeamPreviewCandidate,
  TeamPreviewReviewDraft,
  TeamPreviewReviewSlot
} from '../domain/TeamPreviewRecognition';
import { DocumentTransferService } from '../services/DocumentTransferService';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import { battleOverlayCoordinator } from '../services/BattleOverlayCoordinator';
import { replayRecordingCoordinator } from '../services/ReplayRecordingCoordinator';
import {
  RELEASES_PAGE_URL,
  ReleaseInfo,
  UpdateChannel,
  UpdateCheckResult,
  UpdateService
} from '../services/UpdateService';
import { AppStorageRepository, USER_PRESETS_FILE } from '../storage/AppStorageRepository';
import {
  StoredEntity,
  StoredMove,
  StoredOpponentPresetEntry,
  StoredStats,
  StoredTeam,
  UserOpponentPresetRoot
} from '../storage/StorageContracts';
import {
  buildManualDamageRequest,
  CalculationDisplayResult,
  CalculationMoveResult,
  makePokemonBuild,
  ManualBattleOptions,
  parseCalculationResult,
  pokemonToStored,
  presetSearchMatches,
  profileFromStored,
  statSummary,
  TeamDisplayModel,
  teamWithName,
  teamWithPokemon,
  toTeamDisplay,
  userProfilesForSpecies
} from '../ui/AppUiModels';

const DOMAIN: number = 0x5043;
const PRIMARY: string = '#F4C542';
const ACCENT: string = '#167D6D';
const BACKGROUND: string = '#F4F7FB';
const TEXT: string = '#17233A';
const MUTED: string = '#667085';
const DANGER: string = '#B42318';

function placeholderEntity(type: EntityType, id: string): EntityRef {
  return { entityType: type, canonicalId: `${type}.${id.toLowerCase()}`, showdownId: id, displayName: id };
}

function placeholderPokemon(): PokemonBuild {
  return { species: placeholderEntity('species', 'Pikachu'), level: 50, actualStats: {}, statPoints: {}, moves: [] };
}

function statValue(stats: StatValues | undefined, key: string): string {
  let value: number | undefined;
  if (key === 'hp') value = stats?.hp;
  else if (key === 'atk') value = stats?.atk;
  else if (key === 'def') value = stats?.def;
  else if (key === 'spa') value = stats?.spa;
  else if (key === 'spd') value = stats?.spd;
  else value = stats?.spe;
  return value === undefined ? '' : String(value);
}

function setStatValue(stats: StatValues | undefined, key: string, value: number | undefined): StatValues {
  return {
    hp: key === 'hp' ? value : stats?.hp,
    atk: key === 'atk' ? value : stats?.atk,
    def: key === 'def' ? value : stats?.def,
    spa: key === 'spa' ? value : stats?.spa,
    spd: key === 'spd' ? value : stats?.spd,
    spe: key === 'spe' ? value : stats?.spe
  };
}

function pokemonWithItem(value: PokemonBuild, item: EntityRef | undefined): PokemonBuild {
  return { species: value.species, level: value.level, actualStats: value.actualStats, statPoints: value.statPoints,
    ability: value.ability, item, moves: value.moves };
}

function pokemonWithMoves(value: PokemonBuild, moves: MoveValue[]): PokemonBuild {
  return { species: value.species, level: value.level, actualStats: value.actualStats, statPoints: value.statPoints,
    ability: value.ability, item: value.item, moves };
}

function pokemonWithLevel(value: PokemonBuild, level: number): PokemonBuild {
  return { species: value.species, level, actualStats: value.actualStats, statPoints: value.statPoints,
    ability: value.ability, item: value.item, moves: value.moves };
}

function pokemonWithAbility(value: PokemonBuild, ability: EntityRef | undefined): PokemonBuild {
  return { species: value.species, level: value.level, actualStats: value.actualStats, statPoints: value.statPoints,
    ability, item: value.item, moves: value.moves };
}

function pokemonWithStats(value: PokemonBuild, stats: StatValues, points: boolean): PokemonBuild {
  return { species: value.species, level: value.level, actualStats: points ? value.actualStats : stats,
    statPoints: points ? stats : value.statPoints, ability: value.ability, item: value.item, moves: value.moves };
}

function selectOption(value: string): SelectOption {
  return { value };
}

function selectOptions(values: string[]): SelectOption[] {
  return values.map((value: string): SelectOption => selectOption(value));
}

function manualMove(entity: EntityRef): MoveValue {
  return { entity, source: 'MANUAL_OVERRIDE' };
}

function statPlaceholder(key: string): string {
  if (key === 'hp') return '生命';
  if (key === 'atk') return '攻击';
  if (key === 'def') return '防御';
  if (key === 'spa') return '特攻';
  if (key === 'spd') return '特防';
  return '速度';
}

function displayEntity(value: EntityRef | undefined, empty: string = '未设置'): string {
  return value?.displayName ?? value?.showdownId ?? empty;
}

function storedEntity(value: EntityRef): StoredEntity {
  return {
    entityType: value.entityType,
    canonicalId: value.canonicalId,
    showdownId: value.showdownId,
    displayName: value.displayName ?? value.showdownId
  };
}

function storedMove(value: MoveValue): StoredMove {
  return {
    move: storedEntity(value.entity),
    source: value.source ?? 'PROFILE_PRESET',
    basePower: value.basePower,
    type: value.type
  };
}

function updateChannelLabel(channel: UpdateChannel): string {
  return channel === 'preview' ? '预览版' : '稳定版';
}

@Entry
@Component
struct Index {
  @State loaded: boolean = false;
  @State loadError: string = '';
  @State bridgeReady: boolean = false;
  @State engineReady: boolean = false;
  @State selectedTab: string = 'HOME';
  @State subpage: string = '';
  @State teams: TeamDisplayModel[] = [];
  @State presets: UserOpponentPresetRoot = { schemaVersion: 1, kind: 'OpponentUserPresets', presets: [] };
  @State storageProblem: string = '';
  @State message: string = '';
  @State selectedTeamId: string = '';
  @State renameText: string = '';
  @State renameTeamId: string = '';
  @State deleteTeamId: string = '';
  @State teamEditSlot: number = 0;
  @State teamEditPokemon: PokemonBuild = placeholderPokemon();
  @State presetQuery: string = '';
  @State deletePresetId: string = '';
  @State presetSpecies: EntityRef = placeholderEntity('species', 'Pikachu');
  @State presetProfileId: string = '';
  @State presetName: string = '';
  @State presetPoints: StatValues = {};
  @State presetNature: EntityRef | undefined = undefined;
  @State presetAbility: EntityRef | undefined = undefined;
  @State presetItem: EntityRef | undefined = undefined;
  @State presetMoves: MoveValue[] = [];
  @State ownPokemon: PokemonBuild = placeholderPokemon();
  @State opponentPokemon: PokemonBuild = placeholderPokemon();
  @State calculatorDirection: string = 'OWN_TO_OPPONENT';
  @State selectedMoveId: string = '';
  @State battleType: string = 'SINGLE';
  @State weather: string = 'NONE';
  @State terrain: string = 'NONE';
  @State ownReflect: boolean = false;
  @State ownLightScreen: boolean = false;
  @State opponentReflect: boolean = false;
  @State opponentLightScreen: boolean = false;
  @State critical: boolean = false;
  @State spread: boolean = false;
  @State showAdvanced: boolean = false;
  @State calculating: boolean = false;
  @State calculationResult: CalculationDisplayResult | undefined = undefined;
  @State calculatorError: string = '';
  @State editorSide: string = '';
  @State searchMode: string = '';
  @State searchType: EntityType = 'species';
  @State searchQuery: string = '';
  @State searchReturnPage: string = '';
  @State updateChannel: UpdateChannel = 'stable';
  @State checkingUpdate: boolean = false;
  @State updateResult: UpdateCheckResult | undefined = undefined;
  @State pendingBackupJson: string = '';
  @State pendingPresetJson: string = '';
  @State captureStarting: boolean = false;
  @State replaySilentFallbackAvailable: boolean = false;
  @State replayRetryAssistantMode: string = 'standard';
  @State importCorrection: OwnTeamCorrectionDraft | undefined = undefined;
  @State importTeamName: string = '';
  @State importEditSlot: number = 0;
  @State teamPreviewDraft: TeamPreviewReviewDraft | undefined = undefined;
  @State previewEditSide: string = 'own';
  @State previewEditSlot: number = 0;
  @State previewSelectedTeamId: string = '';

  private context?: common.UIAbilityContext;
  private storage?: AppStorageRepository;
  private catalog?: RuntimeDataRepository;
  private engine?: DamageEngineRuntime;
  private documentTransfer?: DocumentTransferService;
  private updateService: UpdateService = new UpdateService();
  private controller: webview.WebviewController = new webview.WebviewController('pc-main-damage');

  aboutToAppear(): void {
    const info: BridgeInfo = pcbridge.getBridgeInfo();
    this.bridgeReady = info.api >= 1 && info.native && info.name === 'pcbridge';
    this.context = getContext(this) as common.UIAbilityContext;
    if (REPLAY_ENABLED) replayRecordingCoordinator.configure(this.context);
    this.storage = new AppStorageRepository(this.context.filesDir);
    this.catalog = new RuntimeDataRepository(this.context);
    this.documentTransfer = new DocumentTransferService(this.context);
    this.loadApplication();
    hilog.info(DOMAIN, 'PCApp', 'APP_NATIVE_BRIDGE_%{public}s variant=%{public}s',
      this.bridgeReady ? 'READY' : 'FAIL', RELEASE_VARIANT);
  }

  private async loadApplication(): Promise<void> {
    try {
      await this.catalog?.load();
      ownTeamCaptureCoordinator.configure(this.context as common.UIAbilityContext,
        this.catalog as RuntimeDataRepository, this.storage as AppStorageRepository);
      battleOverlayCoordinator.configure(this.context as common.UIAbilityContext,
        this.catalog as RuntimeDataRepository, this.storage as AppStorageRepository);
      this.reloadStorage();
      const pikachu = this.catalog?.localize('species', 'Pikachu') ?? placeholderEntity('species', 'Pikachu');
      const armarouge = this.catalog?.localize('species', 'Armarouge') ?? placeholderEntity('species', 'Armarouge');
      this.ownPokemon = makePokemonBuild(this.catalog as RuntimeDataRepository, pikachu);
      this.opponentPokemon = makePokemonBuild(this.catalog as RuntimeDataRepository, armarouge,
        this.opponentProfilesFor(armarouge)[0]);
      this.refreshSelectedMove();
      this.loaded = true;
      hilog.info(DOMAIN, 'PCApp', 'APP_STAGE5_DATA_READY teams=%{public}d presets=%{public}d',
        this.teams.length, this.presets.presets.length);
    } catch (error) {
      this.loadError = String(error);
      hilog.error(DOMAIN, 'PCApp', 'APP_STAGE5_DATA_FAIL %{public}s', this.loadError);
    }
  }

  private onEngineLoaded(): void {
    this.engine = new DamageEngineRuntime(this.controller);
    this.engine.getEngineInfo().then(() => {
      this.engineReady = true;
      hilog.info(DOMAIN, 'PCApp', 'APP_DAMAGE_ENGINE_READY variant=%{public}s', RELEASE_VARIANT);
    }).catch((error: Error) => {
      this.engineReady = false;
      this.calculatorError = `伤害引擎初始化失败：${String(error)}`;
    });
  }

  private reloadStorage(): void {
    if (!this.storage) return;
    const state = this.storage.loadManagedState();
    this.teams = state.savedTeams.map((team: StoredTeam) => toTeamDisplay(team));
    const loadedPresets = this.storage.loadUserOpponentPresets();
    this.presets = loadedPresets.root;
    this.storageProblem = loadedPresets.problem ?? '';
    this.updateChannel = state.updateChannel === 'preview' ? 'preview' : 'stable';
  }

  private selectedTeam(): TeamDisplayModel | undefined {
    return this.teams.find((team: TeamDisplayModel) => team.id === this.selectedTeamId);
  }

  private currentAttackMoves(): MoveValue[] {
    return this.calculatorDirection === 'OWN_TO_OPPONENT' ? this.ownPokemon.moves : this.opponentPokemon.moves;
  }

  private dismissKeyboard(): void {
    this.getUIContext().getFocusController().clearFocus();
  }

  private natureSelectOptions(): SelectOption[] {
    const options: SelectOption[] = [selectOption('中性（不修正）')];
    for (const entry of this.catalog?.natureOptions() ?? []) options.push(selectOption(displayEntity(entry.entity)));
    return options;
  }

  private abilitySelectOptions(species: EntityRef, empty: string = ''): SelectOption[] {
    const options: SelectOption[] = [];
    if (empty.length > 0) options.push(selectOption(empty));
    for (const entry of this.catalog?.abilitiesFor(species.showdownId) ?? []) options.push(selectOption(displayEntity(entry)));
    return options;
  }

  private moveSelectOptions(): SelectOption[] {
    return this.currentAttackMoves().map((move: MoveValue): SelectOption =>
      selectOption(`[${move.type ?? '—'}] ${displayEntity(move.entity)}`));
  }

  private teamPokemonSelectOptions(): SelectOption[] {
    const options: SelectOption[] = [selectOption('选择已保存配置')];
    for (const team of this.teams) {
      team.pokemon.forEach((entry: PokemonBuild, index: number) =>
        options.push(selectOption(`${team.name} · ${index + 1}. ${displayEntity(entry.species)}`)));
    }
    return options;
  }

  private profileSelectOptions(): SelectOption[] {
    return this.opponentProfilesFor().map((profile: OpponentProfile): SelectOption => selectOption(profile.profileName));
  }

  private refreshSelectedMove(): void {
    const moves = this.currentAttackMoves();
    if (!moves.some((move: MoveValue) => move.entity.showdownId === this.selectedMoveId)) {
      this.selectedMoveId = moves.find((move: MoveValue) => (move.basePower ?? 0) > 0)?.entity.showdownId ??
        moves[0]?.entity.showdownId ?? '';
    }
  }

  private opponentProfilesFor(species: EntityRef = this.opponentPokemon.species): OpponentProfile[] {
    if (!this.catalog) return [];
    return this.catalog.profilesFor(species.showdownId, userProfilesForSpecies(this.presets, species));
  }

  private speciesForPreset(entry: StoredOpponentPresetEntry): EntityRef | undefined {
    return this.catalog?.localize('species', entry.speciesId);
  }

  private filteredPresets(): StoredOpponentPresetEntry[] {
    return this.presets.presets.filter((entry: StoredOpponentPresetEntry) =>
      presetSearchMatches(entry, this.speciesForPreset(entry), this.presetQuery));
  }

  private searchResults(): EntityRef[] {
    return this.catalog?.searchEntities(this.searchType, this.searchQuery, 100) ?? [];
  }

  private openSearch(mode: string, type: EntityType, returnPage: string): void {
    this.searchMode = mode;
    this.searchType = type;
    this.searchQuery = '';
    this.searchReturnPage = returnPage;
    this.subpage = 'SEARCH';
  }

  private applySearchResult(entity: EntityRef): void {
    if (!this.catalog) return;
    if (this.searchMode === 'OWN_SPECIES') {
      this.ownPokemon = makePokemonBuild(this.catalog, entity);
    } else if (this.searchMode === 'OPPONENT_SPECIES') {
      this.opponentPokemon = makePokemonBuild(this.catalog, entity, this.opponentProfilesFor(entity)[0]);
    } else if (this.searchMode === 'OWN_ITEM') {
      this.ownPokemon = pokemonWithItem(this.ownPokemon, entity);
    } else if (this.searchMode === 'OPPONENT_ITEM') {
      this.opponentPokemon = pokemonWithItem(this.opponentPokemon, entity);
    } else if (this.searchMode === 'OWN_MOVE') {
      this.ownPokemon = pokemonWithMoves(this.ownPokemon, this.addMove(this.ownPokemon.moves, entity));
    } else if (this.searchMode === 'OPPONENT_MOVE') {
      this.opponentPokemon = pokemonWithMoves(this.opponentPokemon, this.addMove(this.opponentPokemon.moves, entity));
    } else if (this.searchMode === 'TEAM_SPECIES') {
      this.teamEditPokemon = makePokemonBuild(this.catalog, entity);
    } else if (this.searchMode === 'TEAM_ITEM') {
      this.teamEditPokemon = pokemonWithItem(this.teamEditPokemon, entity);
    } else if (this.searchMode === 'TEAM_MOVE') {
      this.teamEditPokemon = pokemonWithMoves(this.teamEditPokemon, this.addMove(this.teamEditPokemon.moves, entity));
    } else if (this.searchMode === 'PRESET_SPECIES') {
      this.beginPresetEditor(entity, undefined);
      this.subpage = 'PRESET_EDIT';
      return;
    } else if (this.searchMode === 'PRESET_ITEM') {
      this.presetItem = entity;
    } else if (this.searchMode === 'IMPORT_SPECIES') {
      this.updateImportSpecies(entity);
    } else if (this.searchMode === 'IMPORT_ITEM') {
      this.updateImportItem(entity);
    } else if (this.searchMode === 'IMPORT_MOVE') {
      this.addImportMove(entity);
    } else if (this.searchMode === 'PREVIEW_SPECIES') {
      this.updateTeamPreviewSpecies(entity);
    }
    this.subpage = this.searchReturnPage;
    this.refreshSelectedMove();
  }

  private addMove(existing: MoveValue[], entity: EntityRef): MoveValue[] {
    if (existing.some((move: MoveValue) => move.entity.canonicalId === entity.canonicalId)) return existing;
    const legal = this.catalog?.legalMovesFor(entity.showdownId) ?? [];
    const matched = legal.find((move: MoveValue) => move.entity.canonicalId === entity.canonicalId);
    return [...existing, matched ?? manualMove(entity)].slice(0, 4);
  }

  private legalMoveSearchFor(mode: string): EntityRef[] {
    const importSpecies = this.importCorrection?.slots[this.importEditSlot]?.species;
    const species = mode === 'OWN_MOVE' ? this.ownPokemon.species : mode === 'OPPONENT_MOVE' ?
      this.opponentPokemon.species : mode === 'IMPORT_MOVE' && importSpecies ? importSpecies : this.teamEditPokemon.species;
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return (this.catalog?.legalMovesFor(species.showdownId) ?? [])
      .filter((move: MoveValue) => query.length === 0 ||
        (move.entity.displayName ?? '').toLocaleLowerCase().includes(query) ||
        move.entity.showdownId.toLocaleLowerCase().includes(query))
      .slice(0, 100).map((move: MoveValue) => move.entity);
  }

  private async startOwnTeamAssistant(mode: string = 'standard'): Promise<void> {
    if (this.captureStarting) return;
    this.captureStarting = true;
    this.replayRetryAssistantMode = mode;
    this.replaySilentFallbackAvailable = false;
    try {
      if (REPLAY_ENABLED && getSelectedReplayMode() === 'RECOGNIZE_AND_RECORD') {
        const replayStatus = replayRecordingCoordinator.startCaptureSession('RECOGNIZE_AND_RECORD');
        this.message = await ownTeamCaptureCoordinator.attachPreparedAssistant(mode, replayStatus);
      } else {
        this.message = await ownTeamCaptureCoordinator.startAssistant(mode);
      }
      if (mode === 'hud') await battleOverlayCoordinator.show('hud');
    } catch (error) {
      this.message = `启动失败：${String(error)}`;
      if (REPLAY_ENABLED) this.replaySilentFallbackAvailable = replayRecordingCoordinator.canRetrySilently();
    } finally {
      this.captureStarting = false;
    }
  }

  private async retryReplaySilently(): Promise<void> {
    if (this.captureStarting) return;
    this.captureStarting = true;
    try {
      const replayStatus = replayRecordingCoordinator.continueSilently();
      this.message = await ownTeamCaptureCoordinator.attachPreparedAssistant(this.replayRetryAssistantMode, replayStatus);
      if (this.replayRetryAssistantMode === 'hud') await battleOverlayCoordinator.show('hud');
      this.replaySilentFallbackAvailable = false;
    } catch (error) {
      this.message = `无声回退失败：${String(error)}`;
    } finally {
      this.captureStarting = false;
    }
  }

  private cancelReplayStart(): void {
    this.message = replayRecordingCoordinator.cancelRecording();
    this.replaySilentFallbackAvailable = false;
  }

  private openOwnTeamCorrection(): void {
    try {
      this.importCorrection = ownTeamCaptureCoordinator.loadCorrectionDraft();
      this.importTeamName = '';
      this.importEditSlot = 0;
      this.subpage = 'OWN_TEAM_CORRECTION';
      this.message = '';
    } catch (error) {
      this.message = String(error);
    }
  }

  private replaceImportSlot(slot: OwnTeamCorrectionSlot): void {
    const correction = this.importCorrection;
    if (!correction) return;
    const slots = correction.slots.slice();
    slots[slot.slotIndex] = slot;
    this.importCorrection = {
      moveRecognized: correction.moveRecognized,
      moveTotal: correction.moveTotal,
      statsRecognized: correction.statsRecognized,
      statsTotal: correction.statsTotal,
      moveItemCapturedAt: correction.moveItemCapturedAt,
      statsCapturedAt: correction.statsCapturedAt,
      slots
    };
  }

  private currentImportSlot(): OwnTeamCorrectionSlot | undefined {
    return this.importCorrection?.slots[this.importEditSlot];
  }

  private updateImportSpecies(entity: EntityRef): void {
    const slot = this.currentImportSlot();
    if (!slot) return;
    this.replaceImportSlot({ slotIndex: slot.slotIndex, species: entity, speciesConfirmed: true,
      ability: undefined, item: slot.item, itemResolved: slot.itemResolved, moves: [],
      recognizedMoveSlotIndexes: [], actualStats: slot.actualStats });
  }

  private confirmImportSpecies(): void {
    const slot = this.currentImportSlot();
    if (!slot || !slot.species) return;
    this.replaceImportSlot({ slotIndex: slot.slotIndex, species: slot.species, speciesConfirmed: true,
      ability: slot.ability, item: slot.item, itemResolved: slot.itemResolved, moves: slot.moves,
      recognizedMoveSlotIndexes: slot.recognizedMoveSlotIndexes, actualStats: slot.actualStats });
  }

  private updateImportAbility(index: number): void {
    const slot = this.currentImportSlot();
    if (!slot?.species) return;
    const ability = index === 0 ? undefined : this.catalog?.abilitiesFor(slot.species.showdownId)[index - 1];
    this.replaceImportSlot({ slotIndex: slot.slotIndex, species: slot.species, speciesConfirmed: slot.speciesConfirmed,
      ability, item: slot.item, itemResolved: slot.itemResolved, moves: slot.moves,
      recognizedMoveSlotIndexes: slot.recognizedMoveSlotIndexes, actualStats: slot.actualStats });
  }

  private updateImportItem(item: EntityRef | undefined): void {
    const slot = this.currentImportSlot();
    if (!slot) return;
    this.replaceImportSlot({ slotIndex: slot.slotIndex, species: slot.species,
      speciesConfirmed: slot.speciesConfirmed, ability: slot.ability, item, itemResolved: true, moves: slot.moves,
      recognizedMoveSlotIndexes: slot.recognizedMoveSlotIndexes, actualStats: slot.actualStats });
  }

  private addImportMove(entity: EntityRef): void {
    const slot = this.currentImportSlot();
    if (!slot?.species || slot.moves.some((move: MoveValue) => move.entity.canonicalId === entity.canonicalId)) return;
    const required = requiredOwnTeamMoveCount(slot.species);
    if (slot.moves.length >= required) return;
    const legal = this.catalog?.legalMovesFor(slot.species.showdownId) ?? [];
    const found = legal.find((move: MoveValue) => move.entity.canonicalId === entity.canonicalId);
    const move: MoveValue = found ? { entity: found.entity, basePower: found.basePower, type: found.type,
      priority: found.priority, source: 'OWN_BUILD' } : { entity, source: 'OWN_BUILD' };
    this.replaceImportSlot({ slotIndex: slot.slotIndex, species: slot.species,
      speciesConfirmed: slot.speciesConfirmed, ability: slot.ability, item: slot.item,
      itemResolved: slot.itemResolved, moves: [...slot.moves, move],
      recognizedMoveSlotIndexes: [...slot.recognizedMoveSlotIndexes, slot.moves.length], actualStats: slot.actualStats });
  }

  private removeImportMove(moveIndex: number): void {
    const slot = this.currentImportSlot();
    if (!slot) return;
    this.replaceImportSlot({ slotIndex: slot.slotIndex, species: slot.species,
      speciesConfirmed: slot.speciesConfirmed, ability: slot.ability, item: slot.item,
      itemResolved: slot.itemResolved, moves: slot.moves.filter((_move: MoveValue, index: number) => index !== moveIndex),
      recognizedMoveSlotIndexes: [], actualStats: slot.actualStats });
  }

  private updateImportStat(key: string, text: string): void {
    const slot = this.currentImportSlot();
    if (!slot) return;
    const digits = text.replace(/[^0-9]/g, '');
    const value = digits.length === 0 ? undefined : Math.min(999, Number(digits));
    const stats = setStatValue(slot.actualStats, key, value);
    this.replaceImportSlot({ slotIndex: slot.slotIndex, species: slot.species,
      speciesConfirmed: slot.speciesConfirmed, ability: slot.ability, item: slot.item,
      itemResolved: slot.itemResolved, moves: slot.moves,
      recognizedMoveSlotIndexes: slot.recognizedMoveSlotIndexes, actualStats: stats });
  }

  private saveImportedTeam(): void {
    const correction = this.importCorrection;
    if (!correction) return;
    try {
      ownTeamCaptureCoordinator.saveCorrection(this.importTeamName, correction);
      this.reloadStorage();
      this.message = `队伍“${this.importTeamName.trim()}”已保存，可在首页和计算页使用。`;
      this.importCorrection = undefined;
      this.subpage = '';
      this.selectedTab = 'HOME';
    } catch (error) {
      this.message = `保存失败：${String(error)}`;
    }
  }

  private restartOwnTeamImport(): void {
    this.message = ownTeamCaptureCoordinator.restartImport();
    this.importCorrection = undefined;
    this.subpage = '';
    this.selectedTab = 'BATTLE';
  }

  private importAbilityIndex(slot: OwnTeamCorrectionSlot): number {
    if (!slot.species || !slot.ability) return 0;
    const index = (this.catalog?.abilitiesFor(slot.species.showdownId) ?? [])
      .findIndex((ability: EntityRef) => ability.canonicalId === slot.ability?.canonicalId);
    return index < 0 ? 0 : index + 1;
  }

  private openBattlePreviewReview(): void {
    try {
      const result = ownTeamCaptureCoordinator.loadTeamPreview();
      if (!result) throw new Error('还没有双方阵容识别结果，请先在悬浮入口点击“识别双方阵容”');
      this.teamPreviewDraft = buildTeamPreviewReview(result);
      const matches = this.matchingPreviewTeams();
      this.previewSelectedTeamId = matches.length === 1 ? matches[0].savedTeamId : '';
      this.subpage = 'BATTLE_PREVIEW_REVIEW';
      this.message = '';
    } catch (error) {
      this.message = String(error);
    }
  }

  private currentTeamPreviewSlot(): TeamPreviewReviewSlot | undefined {
    const draft = this.teamPreviewDraft;
    if (!draft) return undefined;
    const slots = this.previewEditSide === 'opponent' ? draft.opponent : draft.own;
    return slots.find((slot: TeamPreviewReviewSlot) => slot.slotIndex === this.previewEditSlot);
  }

  private updateTeamPreviewSpecies(entity: EntityRef): void {
    const draft = this.teamPreviewDraft;
    if (!draft) return;
    const side = this.previewEditSide === 'opponent' ? 'opponent' : 'own';
    this.teamPreviewDraft = replaceTeamPreviewSlot(draft, side, this.previewEditSlot, entity, true);
    this.refreshPreviewTeamSelection();
  }

  private chooseTeamPreviewCandidate(side: string, slotIndex: number, candidate: TeamPreviewCandidate): void {
    const localized: EntityRef = this.catalog?.localize('species', candidate.showdownId) ?? {
      entityType: 'species', canonicalId: candidate.canonicalId, showdownId: candidate.showdownId,
      displayName: candidate.displayName, confidence: candidate.confidence, source: 'system'
    };
    this.previewEditSide = side;
    this.previewEditSlot = slotIndex;
    this.updateTeamPreviewSpecies(localized);
  }

  private confirmTeamPreviewSlot(side: string, slotIndex: number): void {
    const draft = this.teamPreviewDraft;
    const slots = side === 'opponent' ? draft?.opponent : draft?.own;
    const slot = slots?.find((value: TeamPreviewReviewSlot) => value.slotIndex === slotIndex);
    if (!draft || !slot?.selected) return;
    this.teamPreviewDraft = replaceTeamPreviewSlot(draft, side === 'opponent' ? 'opponent' : 'own',
      slotIndex, slot.selected, true);
    this.refreshPreviewTeamSelection();
  }

  private matchingPreviewTeams(): StoredTeam[] {
    if (!this.teamPreviewDraft) return [];
    return matchingSavedOwnTeams(this.teamPreviewDraft, this.teams.map((team: TeamDisplayModel) => team.stored));
  }

  private refreshPreviewTeamSelection(): void {
    const matches = this.matchingPreviewTeams();
    if (!matches.some((team: StoredTeam) => team.savedTeamId === this.previewSelectedTeamId)) {
      this.previewSelectedTeamId = matches.length === 1 ? matches[0].savedTeamId : '';
    }
  }

  private createRecognizedBattle(): void {
    const draft = this.teamPreviewDraft;
    if (!draft || !this.storage) return;
    try {
      const matches = this.matchingPreviewTeams();
      if (!matches.some((team: StoredTeam) => team.savedTeamId === this.previewSelectedTeamId)) {
        throw new Error('请选择与预览六只宝可梦完全一致的已保存我方队伍');
      }
      this.storage.saveCurrentBattleSession(buildBattleSession(draft, this.previewSelectedTeamId));
      this.message = '新对局已建立，可进入计算页或继续使用悬浮助手';
      this.teamPreviewDraft = undefined;
      this.subpage = '';
      this.selectedTab = 'BATTLE';
    } catch (error) {
      this.message = `建立对局失败：${String(error)}`;
    }
  }

  private beginTeamEdit(team: TeamDisplayModel): void {
    this.selectedTeamId = team.id;
    this.teamEditSlot = 0;
    this.teamEditPokemon = team.pokemon[0] ?? placeholderPokemon();
    this.subpage = 'TEAM_EDIT';
  }

  private selectTeamSlot(slot: number): void {
    const team = this.selectedTeam();
    if (!team || !team.pokemon[slot]) return;
    this.teamEditSlot = slot;
    this.teamEditPokemon = team.pokemon[slot];
  }

  private saveTeamEdit(): void {
    const team = this.selectedTeam();
    if (!team || !this.storage) return;
    try {
      this.storage.saveTeam(teamWithPokemon(team.stored, this.teamEditSlot, this.teamEditPokemon));
      this.reloadStorage();
      this.message = '队伍调整已保存。';
      this.subpage = '';
    } catch (error) {
      this.message = `保存失败：${String(error)}`;
    }
  }

  private renameTeam(team: TeamDisplayModel): void {
    this.renameTeamId = team.id;
    this.renameText = team.name;
    this.message = '';
  }

  private confirmRename(): void {
    const team = this.teams.find((entry: TeamDisplayModel) => entry.id === this.renameTeamId);
    if (!team || !this.storage) return;
    try {
      this.storage.saveTeam(teamWithName(team.stored, this.renameText));
      this.renameTeamId = '';
      this.reloadStorage();
      this.message = '队伍名称已保存。';
    } catch (error) {
      this.message = String(error);
    }
  }

  private confirmDeleteTeam(): void {
    if (!this.storage || this.deleteTeamId.length === 0) return;
    try {
      this.storage.deleteTeam(this.deleteTeamId);
      this.deleteTeamId = '';
      this.reloadStorage();
      this.message = '队伍已删除。';
    } catch (error) {
      this.message = `删除失败：${String(error)}`;
    }
  }

  private beginPresetEditor(species: EntityRef, entry?: StoredOpponentPresetEntry): void {
    this.presetSpecies = species;
    this.presetProfileId = entry?.preset.profileId ?? '';
    this.presetName = entry?.preset.profileName ?? '';
    this.presetPoints = entry?.preset.statPoints ?? {};
    this.presetNature = entry?.preset.statAlignment ? this.catalog?.localize('nature',
      entry.preset.statAlignment.showdownId) : undefined;
    this.presetAbility = entry?.preset.ability ? this.catalog?.localize('ability',
      entry.preset.ability.showdownId) : undefined;
    this.presetItem = entry?.preset.item ? this.catalog?.localize('item', entry.preset.item.showdownId) : undefined;
    this.presetMoves = entry ? profileFromStored(entry).moves ?? [] : [];
  }

  private editPreset(entry: StoredOpponentPresetEntry): void {
    const species = this.speciesForPreset(entry) ?? placeholderEntity('species', entry.speciesId);
    this.beginPresetEditor(species, entry);
    this.subpage = 'PRESET_EDIT';
  }

  private savePreset(): void {
    if (!this.storage || !this.catalog) return;
    const name = this.presetName.trim();
    if (name.length === 0) {
      this.message = '请填写预设名称。';
      return;
    }
    if (name.length > 24) {
      this.message = '预设名称最多 24 个字符。';
      return;
    }
    const points: StoredStats = {
      hp: this.presetPoints.hp ?? 0, atk: this.presetPoints.atk ?? 0, def: this.presetPoints.def ?? 0,
      spa: this.presetPoints.spa ?? 0, spd: this.presetPoints.spd ?? 0, spe: this.presetPoints.spe ?? 0
    };
    const entry: StoredOpponentPresetEntry = {
      speciesId: this.presetSpecies.showdownId,
      preset: {
        profileId: this.presetProfileId || `user.harmony-${Date.now()}`,
        profileName: name,
        source: 'USER_SAVED',
        level: 50,
        statPoints: points,
        actualStats: this.catalog.actualStatsFor(this.presetSpecies.showdownId, points, this.presetNature),
        statAlignment: this.presetNature ? storedEntity(this.presetNature) : undefined,
        ability: this.presetAbility ? storedEntity(this.presetAbility) : undefined,
        item: this.presetItem ? storedEntity(this.presetItem) : undefined,
        moves: this.presetMoves.map((move: MoveValue) => storedMove(move))
      }
    };
    try {
      this.storage.upsertUserOpponentPreset(entry);
      this.reloadStorage();
      this.message = this.presetProfileId ? '配置修改已保存。' : '配置已创建。';
      this.subpage = 'PRESETS';
    } catch (error) {
      this.message = `保存失败：${String(error)}`;
    }
  }

  private confirmDeletePreset(): void {
    if (!this.storage || this.deletePresetId.length === 0) return;
    try {
      if (!this.storage.deleteUserOpponentPreset(this.deletePresetId)) throw new Error('配置已不存在');
      this.deletePresetId = '';
      this.reloadStorage();
      this.message = '配置已删除；当前对局中的失效引用也已清理。';
    } catch (error) {
      this.message = `删除失败：${String(error)}`;
    }
  }

  private useTeamPokemon(team: TeamDisplayModel, slot: number): void {
    if (!team.pokemon[slot]) return;
    this.ownPokemon = team.pokemon[slot];
    this.selectedTab = 'CALCULATOR';
    this.subpage = '';
    this.refreshSelectedMove();
  }

  private selectOpponentProfile(profileId: string): void {
    const profile = this.opponentProfilesFor().find((entry: OpponentProfile) => entry.profileId === profileId);
    if (profile && this.catalog) this.opponentPokemon = makePokemonBuild(this.catalog, this.opponentPokemon.species, profile);
    this.refreshSelectedMove();
  }

  private updatePokemonStat(side: string, key: string, raw: string, points: boolean = false): void {
    const digits = raw.replace(/[^0-9]/g, '').slice(0, 3);
    const value = digits.length > 0 ? Number(digits) : undefined;
    const current = side === 'OWN' ? this.ownPokemon : side === 'OPPONENT' ? this.opponentPokemon : this.teamEditPokemon;
    const updated: PokemonBuild = points ? pokemonWithStats(current, setStatValue(current.statPoints, key,
      value === undefined ? undefined : Math.min(32, value)), true) :
      pokemonWithStats(current, setStatValue(current.actualStats, key, value), false);
    if (side === 'OWN') this.ownPokemon = updated;
    else if (side === 'OPPONENT') this.opponentPokemon = updated;
    else this.teamEditPokemon = updated;
  }

  private updateLevel(side: string, raw: string): void {
    const value = Math.max(1, Math.min(100, Number(raw.replace(/[^0-9]/g, '').slice(0, 3)) || 1));
    if (side === 'OWN') this.ownPokemon = pokemonWithLevel(this.ownPokemon, value);
    else if (side === 'OPPONENT') this.opponentPokemon = pokemonWithLevel(this.opponentPokemon, value);
    else this.teamEditPokemon = pokemonWithLevel(this.teamEditPokemon, value);
  }

  private setAbility(side: string, value: string): void {
    const pokemon = side === 'OWN' ? this.ownPokemon : side === 'OPPONENT' ? this.opponentPokemon : this.teamEditPokemon;
    const ability = this.catalog?.abilitiesFor(pokemon.species.showdownId)
      .find((entry: EntityRef) => entry.showdownId === value);
    const updated: PokemonBuild = pokemonWithAbility(pokemon, ability);
    if (side === 'OWN') this.ownPokemon = updated;
    else if (side === 'OPPONENT') this.opponentPokemon = updated;
    else this.teamEditPokemon = updated;
  }

  private removeMove(side: string, canonicalId: string): void {
    const pokemon = side === 'OWN' ? this.ownPokemon : side === 'OPPONENT' ? this.opponentPokemon : this.teamEditPokemon;
    const updated: PokemonBuild = pokemonWithMoves(pokemon,
      pokemon.moves.filter((move: MoveValue) => move.entity.canonicalId !== canonicalId));
    if (side === 'OWN') this.ownPokemon = updated;
    else if (side === 'OPPONENT') this.opponentPokemon = updated;
    else this.teamEditPokemon = updated;
    this.refreshSelectedMove();
  }

  private async calculate(): Promise<void> {
    if (!this.engine || !this.engineReady || this.selectedMoveId.length === 0) return;
    this.calculating = true;
    this.calculatorError = '';
    this.calculationResult = undefined;
    const options: ManualBattleOptions = {
      direction: this.calculatorDirection,
      selectedMoveId: this.selectedMoveId,
      battleType: this.battleType,
      weather: this.weather,
      terrain: this.terrain,
      ownReflect: this.ownReflect,
      ownLightScreen: this.ownLightScreen,
      opponentReflect: this.opponentReflect,
      opponentLightScreen: this.opponentLightScreen,
      critical: this.critical,
      spread: this.spread
    };
    try {
      this.calculationResult = parseCalculationResult(await this.engine.calculateRaw(
        buildManualDamageRequest(this.ownPokemon, this.opponentPokemon, options)));
    } catch (error) {
      this.calculatorError = `计算失败：${String(error)}`;
    } finally {
      this.calculating = false;
    }
  }

  private async checkUpdate(): Promise<void> {
    this.checkingUpdate = true;
    this.updateResult = undefined;
    this.updateResult = await this.updateService.check('1.1.4', this.updateChannel);
    this.checkingUpdate = false;
  }

  private async openLink(url: string): Promise<void> {
    try {
      await this.context?.openLink(url);
    } catch (error) {
      this.message = '无法打开链接，请检查系统默认浏览器设置。';
    }
  }

  private fileDate(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private async exportBackup(): Promise<void> {
    try {
      const body = this.storage?.buildAppBackupJson(new Date().toISOString(), '1.1.4');
      if (!body) return;
      const uri = await this.documentTransfer?.exportJson(`pokemon-champions-backup-${this.fileDate()}.json`, body);
      if (uri) this.message = `备份已导出：${this.teams.length} 支队伍、${this.presets.presets.length} 个保存配置。`;
    } catch (error) {
      this.message = `导出失败：${String(error)}`;
    }
  }

  private async selectBackup(): Promise<void> {
    try {
      this.pendingBackupJson = await this.documentTransfer?.importJson() ?? '';
      if (this.pendingBackupJson.length > 0) this.message = '备份已完整读取；请确认后恢复。';
    } catch (error) {
      this.message = `读取备份失败：${String(error)}`;
    }
  }

  private restoreBackup(): void {
    if (!this.storage || this.pendingBackupJson.length === 0) return;
    try {
      const summary = this.storage.restoreAppBackupJson(this.pendingBackupJson);
      this.pendingBackupJson = '';
      this.reloadStorage();
      this.message = `恢复完成：${summary.teamCount} 支队伍、${summary.userOpponentPresetCount} 个保存配置。`;
    } catch (error) {
      this.message = `恢复失败，原数据已保留：${String(error)}`;
    }
  }

  private async exportPresets(): Promise<void> {
    try {
      const body = this.storage?.exportOpponentPresetShareJson(new Date().toISOString(), '1.1.4');
      if (!body) return;
      const uri = await this.documentTransfer?.exportJson(`pokemon-champions-presets-${this.fileDate()}.json`, body);
      if (uri) this.message = `配置分享文件已导出：${this.presets.presets.length} 个保存配置。`;
    } catch (error) {
      this.message = `配置导出失败：${String(error)}`;
    }
  }

  private async selectPresetImport(): Promise<void> {
    try {
      this.pendingPresetJson = await this.documentTransfer?.importJson() ?? '';
      if (this.pendingPresetJson.length > 0) this.message = '配置分享文件已读取；请确认后合并。';
    } catch (error) {
      this.message = `读取配置失败：${String(error)}`;
    }
  }

  private importPresets(): void {
    if (!this.storage || this.pendingPresetJson.length === 0) return;
    try {
      const summary = this.storage.mergeOpponentPresetShareJson(this.pendingPresetJson);
      this.pendingPresetJson = '';
      this.reloadStorage();
      this.message = `配置导入完成：新增 ${summary.added} 个，更新 ${summary.updated} 个，未变化 ${summary.unchanged} 个。`;
    } catch (error) {
      this.message = `配置导入失败，原数据已保留：${String(error)}`;
    }
  }

  @Builder
  private header(title: string, subtitle: string) {
    Column({ space: 3 }) {
      Text(title).fontSize(26).fontWeight(FontWeight.Bold).fontColor(TEXT)
      Text(subtitle).fontSize(14).fontColor(MUTED)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private section(title: string) {
    Text(title).fontSize(19).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
  }

  @Builder
  private statusMessage() {
    if (this.message.length > 0) {
      Text(this.message).fontSize(13).fontColor(this.message.includes('失败') ? DANGER : ACCENT)
        .width('100%').padding(10).backgroundColor('#FFFFFF').borderRadius(10)
    }
  }

  @Builder
  private navButton(tab: string, glyph: string, label: string, id: string) {
    Column({ space: 2 }) {
      Text(glyph).fontSize(20).fontColor(this.selectedTab === tab ? ACCENT : MUTED)
      Text(label).fontSize(12).fontColor(this.selectedTab === tab ? ACCENT : MUTED)
    }
    .id(id)
    .layoutWeight(1)
    .padding({ top: 8, bottom: 8 })
    .onClick(() => {
      this.selectedTab = tab;
      this.subpage = '';
      this.message = '';
      this.reloadStorage();
    })
  }

  @Builder
  private bottomNavigation() {
    Row() {
      this.navButton('HOME', '⌂', '首页', 'nav-home')
      this.navButton('CALCULATOR', '⚔', '计算', 'nav-calculator')
      this.navButton('BATTLE', '◉', '对局', 'nav-battle')
      this.navButton('SETTINGS', '⚙', '设置', 'nav-settings')
    }.width('100%').backgroundColor(Color.White).border({ width: { top: 1 }, color: '#E4E7EC' })
  }

  @Builder
  private homePage() {
    Scroll() {
      Column({ space: 14 }) {
        Row() {
          this.header('Pokémon Champions', REPLAY_ENABLED ? '离线伤害计算器 · 录屏功能版' : '离线伤害计算器 · 标准版')
          Text('v1.1.4').fontSize(12).fontColor(MUTED)
        }.width('100%')
        Text(this.engineReady ? '本地伤害引擎已就绪' : '正在准备本地伤害引擎…')
          .fontColor(this.engineReady ? ACCENT : '#B54708').width('100%')
        Button('开始自由计算').id('home-start-calculator').width('100%').backgroundColor(PRIMARY).fontColor(TEXT)
          .enabled(this.engineReady).onClick(() => { this.selectedTab = 'CALCULATOR'; })
        this.statusMessage()
        this.section(`我的队伍（${this.teams.length}）`)
        if (this.teams.length === 0) {
          Text('还没有保存自己的队伍。请在“对局”页完成我方队伍录入。').fontColor(MUTED).width('100%')
        }
        ForEach(this.teams, (team: TeamDisplayModel) => {
          Column({ space: 7 }) {
            Text(team.name).fontSize(17).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
            Text(team.speciesSummary).fontSize(13).fontColor(MUTED).width('100%')
            Text(team.damageReady ? '可用于计算' : `暂不可用于计算：${team.issues[0] ?? '存在缺失项'}`)
              .fontSize(13).fontColor(team.damageReady ? ACCENT : '#B54708').width('100%')
            Row({ space: 8 }) {
              Button('用于计算').enabled(team.damageReady && this.engineReady).onClick(() => this.useTeamPokemon(team, 0))
              Button('预览').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => {
                this.selectedTeamId = team.id; this.subpage = 'TEAM_PREVIEW';
              })
              Button('手动调整').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => this.beginTeamEdit(team))
            }.width('100%')
            Row({ space: 8 }) {
              Button('重命名').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => this.renameTeam(team))
              Button('删除').backgroundColor('#FEE4E2').fontColor(DANGER).onClick(() => { this.deleteTeamId = team.id; })
            }.width('100%')
            if (this.renameTeamId === team.id) {
              TextInput({ text: this.renameText, placeholder: '队伍名称' }).maxLength(30)
                .enterKeyType(EnterKeyType.Done).onSubmit(() => this.dismissKeyboard())
                .onChange((value: string) => { this.renameText = value; })
              Row({ space: 8 }) {
                Button('保存名称').onClick(() => this.confirmRename())
                Button('取消').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => { this.renameTeamId = ''; })
              }
            }
            if (this.deleteTeamId === team.id) {
              Text(`确定删除“${team.name}”吗？此操作无法撤销。`).fontColor(DANGER).width('100%')
              Row({ space: 8 }) {
                Button('确认删除').backgroundColor(DANGER).onClick(() => this.confirmDeleteTeam())
                Button('取消').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => { this.deleteTeamId = ''; })
              }
            }
          }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        }, (team: TeamDisplayModel) => team.id)
        this.section(`我保存的宝可梦配置（${this.presets.presets.length}）`)
        Column({ space: 8 }) {
          Text('可搜索、新建、修改或删除在对局与自由计算中使用的对手配置。').fontColor(MUTED).width('100%')
          if (this.storageProblem.length > 0) {
            Text(this.storageProblem).fontColor(DANGER).width('100%')
            Text('为防止覆盖原文件，当前已禁止修改、删除和导出。').fontSize(13).fontColor(MUTED).width('100%')
            Button('保留损坏文件副本并重置').backgroundColor(DANGER).onClick(() => {
              try {
                const recovery = this.storage?.preserveCorruptedUserPresetsAndReset();
                this.reloadStorage(); this.message = `已保留原文件副本：${recovery}`;
              } catch (error) { this.message = String(error); }
            })
          } else {
            Button('新建或管理配置').id('home-manage-presets').width('100%').onClick(() => { this.subpage = 'PRESETS'; })
          }
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
      }.width('100%').padding({ left: 16, right: 16, top: 18, bottom: 28 })
    }.width('100%').height('100%')
  }

  @Builder
  private teamPreviewPage() {
    Scroll() {
      Column({ space: 12 }) {
        Button('返回').onClick(() => { this.subpage = ''; })
        this.header(this.selectedTeam()?.name ?? '队伍预览', '六只宝可梦完整配置')
        ForEach(this.selectedTeam()?.pokemon ?? [], (pokemon: PokemonBuild, index: number) => {
          Column({ space: 5 }) {
            Text(`${index + 1}. ${displayEntity(pokemon.species)}`).fontSize(17).fontWeight(FontWeight.Bold).width('100%')
            Text(`等级 ${pokemon.level} · 特性 ${displayEntity(pokemon.ability)} · 道具 ${displayEntity(pokemon.item, '无')}`)
              .fontSize(13).fontColor(MUTED).width('100%')
            Text(`招式：${pokemon.moves.map((move: MoveValue) => displayEntity(move.entity)).join(' / ') || '未设置'}`)
              .fontSize(13).fontColor(MUTED).width('100%')
            Text(`实际能力：${statSummary(pokemon.actualStats)}`).fontSize(13).fontColor(MUTED).width('100%')
          }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        })
      }.width('100%').padding(16)
    }.width('100%').height('100%')
  }

  @Builder
  private teamEditPage() {
    Scroll() {
      Column({ space: 12 }) {
        Button('返回').onClick(() => { this.subpage = ''; })
        this.header('手动调整队伍', this.selectedTeam()?.name ?? '')
        Scroll() {
          Row({ space: 8 }) {
            ForEach(this.selectedTeam()?.pokemon ?? [], (pokemon: PokemonBuild, index: number) => {
              Button(`${index + 1}. ${displayEntity(pokemon.species)}`)
                .backgroundColor(this.teamEditSlot === index ? ACCENT : '#EEF2F6')
                .fontColor(this.teamEditSlot === index ? Color.White : TEXT)
                .onClick(() => this.selectTeamSlot(index))
            })
          }
        }.scrollable(ScrollDirection.Horizontal).width('100%')
        this.pokemonEditor('TEAM', this.teamEditPokemon, 'TEAM_EDIT')
        Button('保存当前宝可梦调整').width('100%').backgroundColor(PRIMARY).fontColor(TEXT)
          .onClick(() => this.saveTeamEdit())
      }.width('100%').padding(16)
    }.width('100%').height('100%')
  }

  @Builder
  private presetManagerPage() {
    Column({ space: 12 }) {
      Row({ space: 10 }) {
        Button('返回').onClick(() => { this.subpage = ''; })
        Text('我保存的宝可梦配置').fontSize(22).fontWeight(FontWeight.Bold).fontColor(TEXT)
      }.width('100%')
      Button('新建配置').id('preset-create').width('100%')
        .onClick(() => this.openSearch('PRESET_SPECIES', 'species', 'PRESETS'))
      TextInput({ text: this.presetQuery, placeholder: '搜索中文名、英文 / Showdown ID 或配置名称' })
        .id('preset-search').enterKeyType(EnterKeyType.Search).onSubmit(() => this.dismissKeyboard())
        .onChange((value: string) => { this.presetQuery = value; })
      this.statusMessage()
      List({ space: 10 }) {
        ForEach(this.filteredPresets(), (entry: StoredOpponentPresetEntry) => {
          ListItem() {
            Column({ space: 6 }) {
              Text(displayEntity(this.speciesForPreset(entry), entry.speciesId)).fontSize(17).fontWeight(FontWeight.Bold).width('100%')
              Text(entry.preset.profileName).fontColor(ACCENT).width('100%')
              Text(`${statSummary(entry.preset.statPoints)}\n性格：${entry.preset.statAlignment?.displayName ?? '中性'} · 特性：${entry.preset.ability?.displayName ?? '未指定'} · 道具：${entry.preset.item?.displayName ?? '无道具'}`)
                .fontSize(13).fontColor(MUTED).width('100%')
              Row({ space: 8 }) {
                Button('修改').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => this.editPreset(entry))
                Button('删除').backgroundColor('#FEE4E2').fontColor(DANGER).onClick(() => { this.deletePresetId = entry.preset.profileId; })
              }
              if (this.deletePresetId === entry.preset.profileId) {
                Text('删除后，当前对局中的相关引用会自动回退。').fontColor(DANGER).fontSize(13).width('100%')
                Row({ space: 8 }) {
                  Button('确认删除').backgroundColor(DANGER).onClick(() => this.confirmDeletePreset())
                  Button('取消').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => { this.deletePresetId = ''; })
                }
              }
            }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
          }
        }, (entry: StoredOpponentPresetEntry) => entry.preset.profileId)
      }.layoutWeight(1).width('100%')
    }.width('100%').height('100%').padding(16)
  }

  @Builder
  private presetEditorPage() {
    Scroll() {
      Column({ space: 12 }) {
        Button('返回').onClick(() => { this.subpage = 'PRESETS'; })
        this.header(this.presetProfileId ? '修改保存的配置' : '新建宝可梦配置', displayEntity(this.presetSpecies))
        TextInput({ text: this.presetName, placeholder: '预设名称' }).maxLength(24)
          .enterKeyType(EnterKeyType.Done).onSubmit(() => this.dismissKeyboard())
          .onChange((value: string) => { this.presetName = value; })
        this.section('能力点（每项 0–32）')
        this.statInputs('PRESET', this.presetPoints, true)
        Text('性格数值修正').fontColor(MUTED).width('100%')
        Select(this.natureSelectOptions())
          .value(displayEntity(this.presetNature, '中性（不修正）'))
          .onSelect((index: number) => { this.presetNature = index === 0 ? undefined : this.catalog?.natureOptions()[index - 1]?.entity; })
        Text('特性').fontColor(MUTED).width('100%')
        Select(this.abilitySelectOptions(this.presetSpecies, '未指定'))
          .value(displayEntity(this.presetAbility))
          .onSelect((index: number) => { this.presetAbility = index === 0 ? undefined :
            this.catalog?.abilitiesFor(this.presetSpecies.showdownId)[index - 1]; })
        Row({ space: 8 }) {
          Button(`道具：${displayEntity(this.presetItem, '无道具')}`).layoutWeight(1)
            .onClick(() => this.openSearch('PRESET_ITEM', 'item', 'PRESET_EDIT'))
          if (this.presetItem) Button('清除').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => { this.presetItem = undefined; })
        }.width('100%')
        Text(this.presetMoves.length > 0 ? `保存时的配置招式：${this.presetMoves.map((move: MoveValue) => displayEntity(move.entity)).join(' / ')}` :
          '新配置不固定招式；对局中仍从该宝可梦合法招式池选择。').fontSize(13).fontColor(MUTED).width('100%')
        this.statusMessage()
        Button(this.presetProfileId ? '保存修改' : '创建配置').id('preset-save').width('100%')
          .backgroundColor(PRIMARY).fontColor(TEXT).onClick(() => this.savePreset())
      }.width('100%').padding(16)
    }.width('100%').height('100%')
  }

  @Builder
  private calculatorPage() {
    Scroll() {
      Column({ space: 14 }) {
        this.header('自由伤害计算', '无需先建立两支队伍，可从完整图鉴自由选择双方宝可梦。')
        Text(this.engineReady ? '本地伤害引擎已就绪' : '正在准备本地伤害引擎…')
          .fontColor(this.engineReady ? ACCENT : '#B54708').width('100%')
        this.section('计算方向')
        Row({ space: 8 }) {
          Button('我方输出').backgroundColor(this.calculatorDirection === 'OWN_TO_OPPONENT' ? ACCENT : '#EEF2F6')
            .fontColor(this.calculatorDirection === 'OWN_TO_OPPONENT' ? Color.White : TEXT)
            .onClick(() => { this.calculatorDirection = 'OWN_TO_OPPONENT'; this.refreshSelectedMove(); })
          Button('我方承伤').backgroundColor(this.calculatorDirection === 'OPPONENT_TO_OWN' ? ACCENT : '#EEF2F6')
            .fontColor(this.calculatorDirection === 'OPPONENT_TO_OWN' ? Color.White : TEXT)
            .onClick(() => { this.calculatorDirection = 'OPPONENT_TO_OWN'; this.refreshSelectedMove(); })
        }.width('100%')
        this.calculatorPokemonCard('OWN', '我方宝可梦', this.ownPokemon)
        this.calculatorPokemonCard('OPPONENT', '对方宝可梦', this.opponentPokemon)
        this.section('招式与战场条件')
        if (this.currentAttackMoves().length === 0) {
          Text('当前攻击方还没有招式，请在宝可梦卡片的“详细调整”中添加。').fontColor('#B54708').width('100%')
        } else {
          Select(this.moveSelectOptions())
            .value(displayEntity(this.currentAttackMoves().find((move: MoveValue) => move.entity.showdownId === this.selectedMoveId)?.entity))
            .onSelect((index: number) => { this.selectedMoveId = this.currentAttackMoves()[index].entity.showdownId; })
        }
        Row({ space: 8 }) {
          Button('单打').backgroundColor(this.battleType === 'SINGLE' ? ACCENT : '#EEF2F6')
            .fontColor(this.battleType === 'SINGLE' ? Color.White : TEXT)
            .onClick(() => { this.battleType = 'SINGLE'; this.spread = false; })
          Button('双打').backgroundColor(this.battleType === 'DOUBLE' ? ACCENT : '#EEF2F6')
            .fontColor(this.battleType === 'DOUBLE' ? Color.White : TEXT)
            .onClick(() => { this.battleType = 'DOUBLE'; this.spread = true; })
        }
        Text('天气').fontColor(MUTED).width('100%')
        Select(selectOptions(['无天气', '晴天', '雨天', '沙暴', '下雪']))
          .selected(['NONE', 'Sun', 'Rain', 'Sand', 'Snow'].indexOf(this.weather))
          .onSelect((index: number) => { this.weather = ['NONE', 'Sun', 'Rain', 'Sand', 'Snow'][index]; })
        Text('场地').fontColor(MUTED).width('100%')
        Select(selectOptions(['无场地', '电气场地', '青草场地', '精神场地', '薄雾场地']))
          .selected(['NONE', 'Electric', 'Grassy', 'Psychic', 'Misty'].indexOf(this.terrain))
          .onSelect((index: number) => { this.terrain = ['NONE', 'Electric', 'Grassy', 'Psychic', 'Misty'][index]; })
        Button(this.showAdvanced ? '收起高级条件' : '能力 / 墙 / 状态').backgroundColor('#EEF2F6').fontColor(TEXT)
          .onClick(() => { this.showAdvanced = !this.showAdvanced; })
        if (this.showAdvanced) {
          this.conditionToggle('我方反射壁', this.ownReflect, 'OWN_REFLECT')
          this.conditionToggle('我方光墙', this.ownLightScreen, 'OWN_LIGHT')
          this.conditionToggle('对方反射壁', this.opponentReflect, 'OPP_REFLECT')
          this.conditionToggle('对方光墙', this.opponentLightScreen, 'OPP_LIGHT')
          this.conditionToggle('会心', this.critical, 'CRITICAL')
          this.conditionToggle('按双打范围招式修正', this.spread, 'SPREAD')
        }
        Button(this.calculating ? '计算中…' : '计算伤害').id('calculator-submit').width('100%')
          .enabled(this.engineReady && !this.calculating && this.selectedMoveId.length > 0)
          .backgroundColor(PRIMARY).fontColor(TEXT).onClick(() => this.calculate())
        if (this.calculatorError.length > 0) {
          Text(this.calculatorError).fontColor(DANGER).width('100%').padding(12).backgroundColor('#FEE4E2').borderRadius(10)
        }
        if (this.calculationResult) this.calculationResultCard(this.calculationResult)
      }.width('100%').padding({ left: 16, right: 16, top: 18, bottom: 28 })
    }.width('100%').height('100%')
  }

  @Builder
  private calculatorPokemonCard(side: string, title: string, pokemon: PokemonBuild) {
    Column({ space: 8 }) {
      Text(title).fontSize(18).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
      Button(`${displayEntity(pokemon.species)}　更换`).width('100%')
        .onClick(() => this.openSearch(side === 'OWN' ? 'OWN_SPECIES' : 'OPPONENT_SPECIES', 'species', ''))
      if (side === 'OWN' && this.teams.length > 0) {
        Text('从我的队伍带入（可选）').fontColor(MUTED).width('100%')
        Select(this.teamPokemonSelectOptions())
          .onSelect((index: number) => {
            if (index === 0) return;
            let offset = index - 1;
            for (const team of this.teams) {
              if (offset < team.pokemon.length) { this.ownPokemon = team.pokemon[offset]; this.refreshSelectedMove(); return; }
              offset -= team.pokemon.length;
            }
          })
      }
      if (side === 'OPPONENT' && this.opponentProfilesFor().length > 0) {
        Text('配置假设').fontColor(MUTED).width('100%')
        Select(this.profileSelectOptions())
          .onSelect((index: number) => this.selectOpponentProfile(this.opponentProfilesFor()[index].profileId))
      }
      Text(`等级 ${pokemon.level} · 特性 ${displayEntity(pokemon.ability)} · 道具 ${displayEntity(pokemon.item, '无')}`)
        .fontSize(13).fontColor(MUTED).width('100%')
      Text(`招式：${pokemon.moves.map((move: MoveValue) => displayEntity(move.entity)).join(' / ') || '未设置'}`)
        .fontSize(13).fontColor(MUTED).width('100%')
      Button(this.editorSide === side ? '收起详细调整' : '详细调整').backgroundColor('#EEF2F6').fontColor(TEXT)
        .onClick(() => { this.editorSide = this.editorSide === side ? '' : side; })
      if (this.editorSide === side) this.pokemonEditor(side, pokemon, '')
    }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
  }

  @Builder
  private pokemonEditor(side: string, pokemon: PokemonBuild, returnPage: string) {
    Column({ space: 8 }) {
      Button(`宝可梦：${displayEntity(pokemon.species)}`).width('100%')
        .onClick(() => this.openSearch(side === 'OWN' ? 'OWN_SPECIES' : side === 'OPPONENT' ? 'OPPONENT_SPECIES' : 'TEAM_SPECIES',
          'species', returnPage))
      TextInput({ text: String(pokemon.level), placeholder: '等级' }).type(InputType.Number).enterKeyType(EnterKeyType.Done)
        .onSubmit(() => this.dismissKeyboard()).onChange((value: string) => this.updateLevel(side, value))
      Text('特性').fontColor(MUTED).width('100%')
      Select(this.abilitySelectOptions(pokemon.species))
        .value(displayEntity(pokemon.ability)).onSelect((index: number, value: string) => this.setAbility(side, value))
      Row({ space: 8 }) {
        Button(`道具：${displayEntity(pokemon.item, '无')}`).layoutWeight(1)
          .onClick(() => this.openSearch(side === 'OWN' ? 'OWN_ITEM' : side === 'OPPONENT' ? 'OPPONENT_ITEM' : 'TEAM_ITEM',
            'item', returnPage))
        if (pokemon.item) Button('清除').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => {
          if (side === 'OWN') this.ownPokemon = pokemonWithItem(this.ownPokemon, undefined);
          else if (side === 'OPPONENT') this.opponentPokemon = pokemonWithItem(this.opponentPokemon, undefined);
          else this.teamEditPokemon = pokemonWithItem(this.teamEditPokemon, undefined);
        })
      }.width('100%')
      Text('招式').fontWeight(FontWeight.Bold).width('100%')
      ForEach(pokemon.moves, (move: MoveValue, index: number) => {
        Row({ space: 8 }) {
          Text(`${index + 1}. ${displayEntity(move.entity)}`).layoutWeight(1).fontColor(TEXT)
          Button('移除').backgroundColor('#FEE4E2').fontColor(DANGER).onClick(() => this.removeMove(side, move.entity.canonicalId))
        }.width('100%')
      }, (move: MoveValue) => move.entity.canonicalId)
      if (pokemon.moves.length < 4) {
        Button('添加招式').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
          .onClick(() => this.openSearch(side === 'OWN' ? 'OWN_MOVE' : side === 'OPPONENT' ? 'OPPONENT_MOVE' : 'TEAM_MOVE',
            'move', returnPage))
      }
      Text('实际能力值').fontWeight(FontWeight.Bold).width('100%')
      this.statInputs(side, pokemon.actualStats ?? {}, false)
      Text('能力加点（可留空）').fontWeight(FontWeight.Bold).width('100%')
      this.statInputs(side, pokemon.statPoints ?? {}, true)
    }.width('100%')
  }

  @Builder
  private statInputs(side: string, stats: StatValues, points: boolean) {
    Row({ space: 6 }) {
      ForEach(['hp', 'atk', 'def'], (key: string) => {
        TextInput({ text: statValue(stats, key), placeholder: statPlaceholder(key) })
          .layoutWeight(1).type(InputType.Number).enterKeyType(EnterKeyType.Done)
          .onSubmit(() => this.dismissKeyboard())
          .onChange((value: string) => {
            if (side === 'PRESET') this.presetPoints = setStatValue(this.presetPoints, key,
              value.length > 0 ? Math.min(32, Number(value.replace(/[^0-9]/g, ''))) : undefined);
            else this.updatePokemonStat(side, key, value, points);
          })
      })
    }.width('100%')
    Row({ space: 6 }) {
      ForEach(['spa', 'spd', 'spe'], (key: string) => {
        TextInput({ text: statValue(stats, key), placeholder: statPlaceholder(key) })
          .layoutWeight(1).type(InputType.Number).enterKeyType(EnterKeyType.Done)
          .onSubmit(() => this.dismissKeyboard())
          .onChange((value: string) => {
            if (side === 'PRESET') this.presetPoints = setStatValue(this.presetPoints, key,
              value.length > 0 ? Math.min(32, Number(value.replace(/[^0-9]/g, ''))) : undefined);
            else this.updatePokemonStat(side, key, value, points);
          })
      })
    }.width('100%')
  }

  @Builder
  private conditionToggle(label: string, value: boolean, key: string) {
    Row() {
      Text(label).layoutWeight(1).fontColor(TEXT)
      Toggle({ type: ToggleType.Switch, isOn: value }).onChange((checked: boolean) => {
        if (key === 'OWN_REFLECT') this.ownReflect = checked;
        else if (key === 'OWN_LIGHT') this.ownLightScreen = checked;
        else if (key === 'OPP_REFLECT') this.opponentReflect = checked;
        else if (key === 'OPP_LIGHT') this.opponentLightScreen = checked;
        else if (key === 'CRITICAL') this.critical = checked;
        else if (key === 'SPREAD') this.spread = checked && this.battleType === 'DOUBLE';
      })
    }.width('100%')
  }

  @Builder
  private calculationResultCard(result: CalculationDisplayResult) {
    Column({ space: 8 }) {
      Text(result.direction === 'OWN_TO_OPPONENT' ? '结果：我方输出' : '结果：我方承伤')
        .fontSize(19).fontWeight(FontWeight.Bold).width('100%')
      Text(`${result.attacker} → ${result.defender}`).fontWeight(FontWeight.Bold).width('100%')
      ForEach(result.moves, (move: CalculationMoveResult) => {
        Divider()
        Text(move.name).fontSize(17).fontWeight(FontWeight.Bold).width('100%')
        Text(`${move.minPercent.toFixed(1)}% – ${move.maxPercent.toFixed(1)}%`)
          .fontSize(23).fontColor(ACCENT).fontWeight(FontWeight.Bold).width('100%')
        Text(`${move.minDamage} – ${move.maxDamage} 点生命值`).width('100%')
        Text(move.koText).width('100%')
        ForEach(move.assumptions, (assumption: string) => { Text(`• ${assumption}`).fontSize(13).fontColor(MUTED).width('100%') })
      })
      if (result.warnings.length > 0) {
        Divider()
        Text('计算提示').fontWeight(FontWeight.Bold).fontColor('#B54708').width('100%')
        ForEach(result.warnings, (warning: string) => { Text(`• ${warning}`).fontSize(13).fontColor(MUTED).width('100%') })
      }
    }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
  }

  @Builder
  private battlePage() {
    Scroll() {
      Column({ space: 14 }) {
        this.header('对局助手', '在用户主动授权后读取游戏画面，识别与计算均在本机完成。')
        Column({ space: 8 }) {
          Text('1. 启动对局助手').fontSize(18).fontWeight(FontWeight.Bold).width('100%')
          if (REPLAY_ENABLED) {
            Text(getSelectedReplayMode() === 'RECOGNIZE_AND_RECORD' ?
              '本次模式：识别并录屏；启动后录像立即开始，识别仍读取同一会话的原分辨率帧。' :
              '本次模式：仅识别；不会创建 MP4，可稍后从悬浮入口独立开始录像。')
              .id('battle-replay-mode').fontSize(13).fontColor(ACCENT).width('100%')
          }
          Text('系统画面授权必须由你在系统界面决定；拒绝后可返回本页重新发起。授权后请选择系统相册窗口。')
            .fontColor(MUTED).width('100%')
          Button(this.captureStarting ? '正在请求系统授权…' : '启动对局助手（普通模式）').id('battle-start-assistant')
            .width('100%').enabled(!this.captureStarting).backgroundColor(ACCENT)
            .onClick(() => this.startOwnTeamAssistant('standard'))
          Button(this.captureStarting ? '正在请求系统授权…' : '启动对局助手（HUD 模式）')
            .id('battle-start-hud').width('100%').enabled(!this.captureStarting)
            .backgroundColor('#8B5CF6').onClick(() => this.startOwnTeamAssistant('hud'))
          if (this.replaySilentFallbackAvailable) {
            Text('内部声音初始化失败；不会自动改成无声录像。').fontSize(13).fontColor('#B42318').width('100%')
            Button('明确继续无声录制').id('battle-replay-continue-silent').width('100%')
              .enabled(!this.captureStarting).backgroundColor('#F4C542').fontColor(TEXT)
              .onClick(() => this.retryReplaySilently())
            Button('取消本次录像').id('battle-replay-cancel-audio-fallback').width('100%')
              .enabled(!this.captureStarting).backgroundColor('#FEE4E2').fontColor('#B42318')
              .onClick(() => this.cancelReplayStart())
          }
          Text('普通模式使用悬浮入口；HUD 模式直接打开原位战斗信息，不创建悬浮球。')
            .fontSize(13).fontColor(MUTED).width('100%')
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        Column({ space: 8 }) {
          Text('2. 录入我的队伍').fontSize(18).fontWeight(FontWeight.Bold).width('100%')
          Text('先识别显示特性、道具和招式的“能力”页，再识别显示六项能力值的“状态”页；两次必须是同一支队伍。')
            .fontColor(MUTED).width('100%')
          Text('在相册中把 2772×1240 图片切为横屏全屏，再点悬浮入口中的“录入我的队伍”。').fontColor(MUTED).width('100%')
          Button('核对识别结果').id('battle-review-own-team').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
            .onClick(() => this.openOwnTeamCorrection())
          Text(`本机已保存 ${this.teams.length} 支队伍。`).fontColor(ACCENT).width('100%')
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        Column({ space: 8 }) {
          Text('3. 开始一场对局').fontSize(18).fontWeight(FontWeight.Bold).width('100%')
          Text('双方队伍预览出现后识别并逐项确认，再选择本局使用的我方队伍。新的预览会使上一局临时状态失效。')
            .fontColor(MUTED).width('100%')
          Text('在相册中把 2772×1240 的队伍预览图片横屏全屏显示，然后点击悬浮入口中的“识别双方阵容”。')
            .fontColor(MUTED).width('100%')
          Button('核对双方阵容并开始对局').id('battle-review-team-preview').width('100%')
            .backgroundColor('#8B5CF6').onClick(() => this.openBattlePreviewReview())
          Button('打开当前对局伤害面板').id('battle-open-damage-panel').width('100%')
            .backgroundColor(ACCENT).onClick(async () => {
              try { await battleOverlayCoordinator.show('panel'); }
              catch (error) { this.message = String(error); }
            })
          Button('显示当前对局 HUD').id('battle-open-direct-hud').width('100%')
            .backgroundColor('#EEF2F6').fontColor(TEXT).onClick(async () => {
              try { await battleOverlayCoordinator.show('hud'); }
              catch (error) { this.message = String(error); }
            })
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        Text('隐私说明：只有在你主动点击识别按钮后才读取当前画面；结果保存在本机，不上传，也不会修改或操作游戏。')
          .fontSize(13).fontColor(ACCENT).width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
      }.width('100%').padding(16)
    }.id('battle-page').width('100%').height('100%')
  }

  @Builder
  private settingsPage() {
    Scroll() {
      Column({ space: 14 }) {
        this.header('设置与诊断', `${REPLAY_ENABLED ? '录屏功能版' : '标准版'} · 1.1.4（9）`)
        this.statusMessage()
        Column({ space: 9 }) {
          this.section('应用版本')
          Text(`发布源：${'crazylei12/Pokemon-Champions-dmg_cal'}`).fontSize(13).fontColor(MUTED).width('100%')
          Row({ space: 8 }) {
            Button('稳定版').backgroundColor(this.updateChannel === 'stable' ? ACCENT : '#EEF2F6')
              .fontColor(this.updateChannel === 'stable' ? Color.White : TEXT).onClick(() => {
                this.updateChannel = 'stable'; this.storage?.saveUpdateChannel('stable'); this.updateResult = undefined;
              })
            Button('预览版').backgroundColor(this.updateChannel === 'preview' ? ACCENT : '#EEF2F6')
              .fontColor(this.updateChannel === 'preview' ? Color.White : TEXT).onClick(() => {
                this.updateChannel = 'preview'; this.storage?.saveUpdateChannel('preview'); this.updateResult = undefined;
              })
          }
          Text(this.updateChannel === 'stable' ? '稳定版只接收正式 Release，适合日常使用。' :
            '预览版也会接收标记为 Pre-release 的测试版本，可能不稳定。').fontSize(13).fontColor(MUTED).width('100%')
          Button(this.checkingUpdate ? '正在连接 GitHub…' : '检查更新').id('settings-check-update').width('100%')
            .enabled(!this.checkingUpdate).onClick(() => this.checkUpdate())
          Button('打开 GitHub 发布页').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
            .onClick(() => this.openLink(RELEASES_PAGE_URL))
          if (this.updateResult) this.updateResultView(this.updateResult)
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        Column({ space: 9 }) {
          this.section('本地状态')
          Text(`伤害引擎：${this.engineReady ? '已就绪' : '未就绪'}`).width('100%')
          Text(`队伍资产：${this.teams.length} 份 · 保存配置：${this.presets.presets.length} 份`).width('100%')
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        Column({ space: 9 }) {
          this.section('数据备份与恢复')
          Text('系统备份与主动 JSON 备份都不包含截图、识别缓存、临时帧或授权令牌。').fontSize(13).fontColor(MUTED).width('100%')
          Button('导出我的数据').id('settings-export-backup').width('100%').onClick(() => this.exportBackup())
          Button('导入我的数据').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => this.selectBackup())
          if (this.pendingBackupJson.length > 0) {
            Text('恢复会先完整校验，再替换队伍、当前对局、配置与草稿；失败时会回滚。').fontColor('#B54708').width('100%')
            Row({ space: 8 }) {
              Button('校验并恢复').onClick(() => this.restoreBackup())
              Button('取消').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => { this.pendingBackupJson = ''; })
            }
          }
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        Column({ space: 9 }) {
          this.section('宝可梦配置分享')
          Text('只导出或合并保存的宝可梦配置，不包含队伍、当前对局或截图。').fontSize(13).fontColor(MUTED).width('100%')
          Button('只导出保存的配置').width('100%').enabled(this.storageProblem.length === 0).onClick(() => this.exportPresets())
          Button('只导入保存的配置').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
            .enabled(this.storageProblem.length === 0).onClick(() => this.selectPresetImport())
          if (this.pendingPresetJson.length > 0) {
            Text('新配置会追加；相同 ID 会更新；本机其他配置保留。').fontColor('#B54708').width('100%')
            Row({ space: 8 }) {
              Button('合并导入').onClick(() => this.importPresets())
              Button('取消').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => { this.pendingPresetJson = ''; })
            }
          }
        }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        Text('App 自身只在你手动检查更新时访问 GitHub，不主动上传截图、队伍或计算输入；安装包下载与安装确认由系统处理。')
          .fontSize(13).fontColor(ACCENT).width('100%')
      }.width('100%').padding(16)
    }.id('settings-page').width('100%').height('100%')
  }

  @Builder
  private updateResultView(result: UpdateCheckResult) {
    Divider()
    Text(result.message).fontWeight(FontWeight.Bold)
      .fontColor(result.kind === 'failure' ? DANGER : result.kind === 'available' ? '#B54708' : ACCENT).width('100%')
    if (result.release) this.releaseLinks(result.release)
  }

  @Builder
  private releaseLinks(release: ReleaseInfo) {
    if (release.notes.trim().length > 0) {
      Text(release.notes.trim().slice(0, 600)).fontSize(13).fontColor(MUTED).maxLines(8).width('100%')
    }
    if (release.standardPackageUrl) {
      Button(!REPLAY_ENABLED ? '下载标准版（当前版本默认）' : '下载标准版')
        .width('100%').onClick(() => this.openLink(release.standardPackageUrl as string))
    }
    if (release.replayPackageUrl) {
      Button(REPLAY_ENABLED ? '下载录屏功能版（当前版本默认）' : '下载录屏功能版')
        .width('100%').onClick(() => this.openLink(release.replayPackageUrl as string))
    }
    if (!release.standardPackageUrl && !release.replayPackageUrl) {
      Text('这个 Release 没有附带 HarmonyOS 安装包，请从发布页查看文件。').fontSize(13).fontColor(MUTED).width('100%')
    }
    Button('查看版本说明').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
      .onClick(() => this.openLink(release.pageUrl))
  }

  @Builder
  private ownTeamCorrectionPage() {
    Scroll() {
      Column({ space: 12 }) {
        Row({ space: 10 }) {
          Button('取消核对').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => {
            this.subpage = '';
            this.selectedTab = 'BATTLE';
          })
          Text('核对我的队伍').fontSize(23).fontWeight(FontWeight.Bold).fontColor(TEXT)
        }.width('100%')
        Text('识别结果不会直接保存。请逐项核对；橙色提示全部消失后，再命名保存。')
          .fontColor(MUTED).width('100%')
        if (this.importCorrection) {
          Text(`招式与道具页：${this.importCorrection.moveRecognized}/${this.importCorrection.moveTotal}　` +
            `能力值页：${this.importCorrection.statsRecognized}/${this.importCorrection.statsTotal}`)
            .fontColor(ACCENT).width('100%')
          ForEach(this.importCorrection.slots, (slot: OwnTeamCorrectionSlot, slotIndex: number) => {
            Column({ space: 8 }) {
              Row() {
                Text(`${slotIndex + 1}. ${displayEntity(slot.species, '未识别宝可梦')}`)
                  .fontSize(18).fontWeight(FontWeight.Bold).fontColor(TEXT).layoutWeight(1)
                Text(unresolvedOwnTeamFields(slot).length === 0 ? '已完整' : `${unresolvedOwnTeamFields(slot).length} 项待核对`)
                  .fontSize(13).fontColor(unresolvedOwnTeamFields(slot).length === 0 ? ACCENT : '#B54708')
              }.width('100%')
              if (unresolvedOwnTeamFields(slot).length > 0) {
                Text(unresolvedOwnTeamFields(slot).join('；')).fontSize(13).fontColor('#B54708').width('100%')
              }
              Button(`宝可梦：${displayEntity(slot.species, '点击选择')}`).width('100%')
                .backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => {
                  this.importEditSlot = slotIndex;
                  this.openSearch('IMPORT_SPECIES', 'species', 'OWN_TEAM_CORRECTION');
                })
              if (slot.species && !slot.speciesConfirmed) {
                Button('确认当前宝可梦').width('100%').backgroundColor('#FFF4E5').fontColor('#B54708')
                  .onClick(() => { this.importEditSlot = slotIndex; this.confirmImportSpecies(); })
              }
              Text('特性').fontSize(13).fontColor(MUTED).width('100%')
              Select(this.abilitySelectOptions(slot.species ?? placeholderEntity('species', ''), '未设置'))
                .selected(this.importAbilityIndex(slot)).width('100%')
                .onSelect((index: number) => { this.importEditSlot = slotIndex; this.updateImportAbility(index); })
              Row({ space: 8 }) {
                Button(`道具：${slot.itemResolved ? displayEntity(slot.item, '无道具') : '待确认'}`)
                  .layoutWeight(1).backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => {
                    this.importEditSlot = slotIndex;
                    this.openSearch('IMPORT_ITEM', 'item', 'OWN_TEAM_CORRECTION');
                  })
                Button('确认无道具').backgroundColor('#EEF2F6').fontColor(TEXT)
                  .onClick(() => { this.importEditSlot = slotIndex; this.updateImportItem(undefined); })
              }.width('100%')
              Text(`招式（需要 ${requiredOwnTeamMoveCount(slot.species)} 个不同招式）`)
                .fontSize(13).fontColor(MUTED).width('100%')
              ForEach(slot.moves, (move: MoveValue, moveIndex: number) => {
                Row({ space: 8 }) {
                  Text(`${moveIndex + 1}. ${displayEntity(move.entity)}`).layoutWeight(1).fontColor(TEXT)
                  Button('移除').fontSize(12).backgroundColor('#FEE4E2').fontColor(DANGER)
                    .onClick(() => { this.importEditSlot = slotIndex; this.removeImportMove(moveIndex); })
                }.width('100%')
              }, (move: MoveValue, moveIndex: number) => `${move.entity.canonicalId}-${moveIndex}`)
              if (slot.moves.length < requiredOwnTeamMoveCount(slot.species)) {
                Button('添加招式').width('100%').backgroundColor('#EEF2F6').fontColor(TEXT)
                  .onClick(() => {
                    this.importEditSlot = slotIndex;
                    this.openSearch('IMPORT_MOVE', 'move', 'OWN_TEAM_CORRECTION');
                  })
              }
              Text('实际能力值').fontSize(13).fontColor(MUTED).width('100%')
              Row({ space: 6 }) {
                TextInput({ text: statValue(slot.actualStats, 'hp'), placeholder: '生命' }).layoutWeight(1)
                  .type(InputType.Number).onChange((value: string) => {
                    this.importEditSlot = slotIndex; this.updateImportStat('hp', value);
                  })
                TextInput({ text: statValue(slot.actualStats, 'atk'), placeholder: '攻击' }).layoutWeight(1)
                  .type(InputType.Number).onChange((value: string) => {
                    this.importEditSlot = slotIndex; this.updateImportStat('atk', value);
                  })
                TextInput({ text: statValue(slot.actualStats, 'def'), placeholder: '防御' }).layoutWeight(1)
                  .type(InputType.Number).onChange((value: string) => {
                    this.importEditSlot = slotIndex; this.updateImportStat('def', value);
                  })
              }.width('100%')
              Row({ space: 6 }) {
                TextInput({ text: statValue(slot.actualStats, 'spa'), placeholder: '特攻' }).layoutWeight(1)
                  .type(InputType.Number).onChange((value: string) => {
                    this.importEditSlot = slotIndex; this.updateImportStat('spa', value);
                  })
                TextInput({ text: statValue(slot.actualStats, 'spd'), placeholder: '特防' }).layoutWeight(1)
                  .type(InputType.Number).onChange((value: string) => {
                    this.importEditSlot = slotIndex; this.updateImportStat('spd', value);
                  })
                TextInput({ text: statValue(slot.actualStats, 'spe'), placeholder: '速度' }).layoutWeight(1)
                  .type(InputType.Number).onChange((value: string) => {
                    this.importEditSlot = slotIndex; this.updateImportStat('spe', value);
                  })
              }.width('100%')
            }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
          }, (slot: OwnTeamCorrectionSlot) => String(slot.slotIndex))
          this.section('命名并保存')
          TextInput({ text: this.importTeamName, placeholder: '队伍名称（1–30 个字符）' }).maxLength(30)
            .enterKeyType(EnterKeyType.Done).onSubmit(() => this.dismissKeyboard())
            .onChange((value: string) => { this.importTeamName = value; })
          Button('保存这支队伍').id('own-team-save').width('100%').backgroundColor(ACCENT)
            .enabled(ownTeamCorrectionComplete(this.importCorrection.slots) &&
              this.importTeamName.trim().length >= 1 && this.importTeamName.trim().length <= 30)
            .onClick(() => this.saveImportedTeam())
          Button('放弃本次录入并重新开始').width('100%').backgroundColor('#FEE4E2').fontColor(DANGER)
            .onClick(() => this.restartOwnTeamImport())
        }
      }.width('100%').padding(16)
    }.id('own-team-correction-page').width('100%').height('100%')
  }

  @Builder
  private teamPreviewSide(title: string, slots: TeamPreviewReviewSlot[]) {
    Column({ space: 9 }) {
      Text(title).fontSize(20).fontWeight(FontWeight.Bold).fontColor(TEXT).width('100%')
      ForEach(slots, (slot: TeamPreviewReviewSlot) => {
        Column({ space: 7 }) {
          Row() {
            Text(`${slot.slotIndex + 1}. ${displayEntity(slot.selected, '未识别')}`)
              .fontSize(17).fontWeight(FontWeight.Bold).fontColor(TEXT).layoutWeight(1)
            Text(slot.confirmed ? '已确认' : slot.recognitionRisk ? '重点核对' : '待确认')
              .fontSize(13).fontColor(slot.confirmed ? ACCENT : '#B54708')
          }.width('100%')
          Text(`置信度 ${(slot.confidence * 100).toFixed(1)}% · 分差 ${slot.scoreMargin.toFixed(3)}`)
            .fontSize(13).fontColor(slot.recognitionRisk ? '#B54708' : MUTED).width('100%')
          if (slot.candidates.length > 0) {
            Row({ space: 6 }) {
              ForEach(slot.candidates, (candidate: TeamPreviewCandidate, candidateIndex: number) => {
                Button(`${candidateIndex + 1}. ${candidate.displayName}`).fontSize(12).layoutWeight(1)
                  .backgroundColor(slot.selected?.canonicalId === candidate.canonicalId ? '#D1FAE5' : '#EEF2F6')
                  .fontColor(TEXT).onClick(() => this.chooseTeamPreviewCandidate(slot.side, slot.slotIndex, candidate))
              }, (candidate: TeamPreviewCandidate, candidateIndex: number) =>
                `${slot.side}-${slot.slotIndex}-${candidate.canonicalId}-${candidateIndex}`)
            }.width('100%')
          }
          Row({ space: 7 }) {
            Button('搜索更正').layoutWeight(1).backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => {
              this.previewEditSide = slot.side;
              this.previewEditSlot = slot.slotIndex;
              this.openSearch('PREVIEW_SPECIES', 'species', 'BATTLE_PREVIEW_REVIEW');
            })
            Button(slot.confirmed ? '已确认' : '确认此位置').layoutWeight(1)
              .enabled(!!slot.selected && !slot.confirmed).backgroundColor(slot.confirmed ? '#D1FAE5' : ACCENT)
              .fontColor(slot.confirmed ? ACCENT : Color.White)
              .onClick(() => this.confirmTeamPreviewSlot(slot.side, slot.slotIndex))
          }.width('100%')
        }.width('100%').padding(12).backgroundColor(Color.White).borderRadius(12)
      }, (slot: TeamPreviewReviewSlot) => `${slot.side}-${slot.slotIndex}`)
    }.width('100%')
  }

  @Builder
  private battlePreviewReviewPage() {
    Scroll() {
      Column({ space: 12 }) {
        Row({ space: 10 }) {
          Button('返回').backgroundColor('#EEF2F6').fontColor(TEXT).onClick(() => {
            this.subpage = '';
            this.selectedTab = 'BATTLE';
          })
          Text('核对双方阵容').fontSize(23).fontWeight(FontWeight.Bold).fontColor(TEXT)
        }.width('100%')
        Text('十二个位置都必须由你逐项确认。橙色位置置信度低或候选分差小；可以选前三个候选，也可以搜索更正。')
          .fontColor(MUTED).width('100%')
        if (this.teamPreviewDraft) {
          this.teamPreviewSide('我方六只', this.teamPreviewDraft.own)
          this.teamPreviewSide('对手六只', this.teamPreviewDraft.opponent)
          Column({ space: 8 }) {
            Text('选择本局我方完整配置').fontSize(18).fontWeight(FontWeight.Bold).width('100%')
            if (this.matchingPreviewTeams().length === 0) {
              Text('没有找到六只宝可梦完全一致的已保存队伍，请返回先录入或修正我方队伍。')
                .fontColor('#B54708').width('100%')
            } else {
              ForEach(this.matchingPreviewTeams(), (team: StoredTeam) => {
                Button(`${this.previewSelectedTeamId === team.savedTeamId ? '✓ ' : ''}${team.teamName ?? team.teamSlotName ?? team.savedTeamId}`)
                  .width('100%').backgroundColor(this.previewSelectedTeamId === team.savedTeamId ? '#D1FAE5' : '#EEF2F6')
                  .fontColor(TEXT).onClick(() => { this.previewSelectedTeamId = team.savedTeamId; })
              }, (team: StoredTeam) => team.savedTeamId)
            }
            Button('确认并建立新对局').id('team-preview-create-battle').width('100%').backgroundColor(ACCENT)
              .enabled(allTeamPreviewSlotsConfirmed(this.teamPreviewDraft) && this.previewSelectedTeamId.length > 0)
              .onClick(() => this.createRecognizedBattle())
          }.width('100%').padding(14).backgroundColor(Color.White).borderRadius(14)
        }
      }.width('100%').padding(16)
    }.id('battle-preview-review-page').width('100%').height('100%')
  }

  @Builder
  private searchPage() {
    Column({ space: 12 }) {
      Row({ space: 10 }) {
        Button('取消').onClick(() => { this.subpage = this.searchReturnPage; })
        Text(this.searchType === 'species' ? '选择宝可梦' : this.searchType === 'move' ? '选择招式' : '选择道具')
          .fontSize(22).fontWeight(FontWeight.Bold).fontColor(TEXT)
      }.width('100%')
      TextInput({ text: this.searchQuery, placeholder: '搜索中文名、英文名称或 Showdown ID' })
        .id('entity-search').enterKeyType(EnterKeyType.Search).onSubmit(() => this.dismissKeyboard())
        .onChange((value: string) => { this.searchQuery = value; })
      List({ space: 4 }) {
        ForEach(this.searchType === 'move' ? this.legalMoveSearchFor(this.searchMode) : this.searchResults(), (entity: EntityRef) => {
          ListItem() {
            Button(displayEntity(entity)).width('100%').backgroundColor(Color.White).fontColor(TEXT)
              .onClick(() => this.applySearchResult(entity))
          }
        }, (entity: EntityRef) => entity.canonicalId)
      }.layoutWeight(1).width('100%')
      if ((this.searchType === 'move' ? this.legalMoveSearchFor(this.searchMode) : this.searchResults()).length === 0) {
        Text('没有找到匹配项。').fontColor('#B54708').width('100%')
      }
    }.width('100%').height('100%').padding(16).backgroundColor(BACKGROUND)
  }

  @Builder
  private content() {
    if (this.subpage === 'SEARCH') this.searchPage()
    else if (this.subpage === 'OWN_TEAM_CORRECTION') this.ownTeamCorrectionPage()
    else if (this.subpage === 'BATTLE_PREVIEW_REVIEW') this.battlePreviewReviewPage()
    else if (this.subpage === 'TEAM_PREVIEW') this.teamPreviewPage()
    else if (this.subpage === 'TEAM_EDIT') this.teamEditPage()
    else if (this.subpage === 'PRESETS') this.presetManagerPage()
    else if (this.subpage === 'PRESET_EDIT') this.presetEditorPage()
    else if (this.selectedTab === 'HOME') this.homePage()
    else if (this.selectedTab === 'CALCULATOR') this.calculatorPage()
    else if (this.selectedTab === 'BATTLE') this.battlePage()
    else this.settingsPage()
  }

  build() {
    Column() {
      if (this.loadError.length > 0) {
        Column({ space: 10 }) {
          Text('应用数据加载失败').fontSize(22).fontWeight(FontWeight.Bold)
          Text(this.loadError).fontColor(DANGER)
        }.layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (!this.loaded) {
        Column({ space: 10 }) {
          LoadingProgress().width(42).height(42).color(ACCENT)
          Text('正在加载本地数据…').fontColor(MUTED)
        }.layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        Column() { this.content() }.layoutWeight(1).width('100%')
        if (this.subpage.length === 0) this.bottomNavigation()
      }
      Web({ src: $rawfile('runtime/damage/engine-host.html'), controller: this.controller })
        .javaScriptAccess(true).onPageEnd(() => this.onEngineLoaded()).width(1).height(1).opacity(0)
    }.width('100%').height('100%').backgroundColor(BACKGROUND)
  }
}
