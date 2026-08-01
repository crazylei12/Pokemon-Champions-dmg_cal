export const APP_BACKUP_KIND = 'PokemonChampionsAssistantBackup';
export const PRESET_SHARE_KIND = 'PokemonChampionsOpponentPresetShare';
export const USER_PRESET_KIND = 'OpponentUserPresets';
export const APP_BACKUP_MAX_BYTES = 16 * 1024 * 1024;
export const PRESET_SHARE_MAX_BYTES = 4 * 1024 * 1024;
export const USER_PRESET_MAX_COUNT = 500;

export interface StoredEntity {
  entityType?: string;
  canonicalId: string;
  showdownId: string;
  displayName?: string;
}

export interface StoredStats {
  hp?: number;
  atk?: number;
  def?: number;
  spa?: number;
  spd?: number;
  spe?: number;
}

export interface StoredMove {
  move: StoredEntity;
  source?: string;
  basePower?: number;
  type?: string;
}

export interface StoredPokemon {
  species: StoredEntity;
  level?: number;
  actualStats?: StoredStats;
  statPoints?: StoredStats;
  ability?: StoredEntity;
  item?: StoredEntity;
  moves?: StoredMove[];
  build?: StoredPokemon;
}

export interface StoredTeam {
  schemaVersion?: number;
  kind?: string;
  savedTeamId: string;
  teamName?: string;
  teamSlotName?: string;
  pokemon?: StoredPokemon[];
  members?: StoredPokemon[];
}

export interface StoredOpponentPreset {
  profileId: string;
  profileName: string;
  source: string;
  level: number;
  statPoints: StoredStats;
  actualStats?: StoredStats;
  statAlignment?: StoredEntity;
  ability?: StoredEntity;
  item?: StoredEntity;
  moves: StoredMove[];
}

export interface StoredOpponentPresetEntry {
  speciesId: string;
  preset: StoredOpponentPreset;
}

export interface UserOpponentPresetRoot {
  schemaVersion: number;
  kind: string;
  presets: StoredOpponentPresetEntry[];
}

export interface OpponentPresetShareEnvelope {
  schemaVersion: number;
  kind: string;
  exportedAt: string;
  appVersion: string;
  userOpponentPresets: UserOpponentPresetRoot;
}

export interface StoredManualOverride {
  baseProfileId?: string;
}

export interface StoredCalculationSelection {
  direction?: string;
  ownSlot?: number;
  opponentSlot?: number;
  selectedPresetId?: string;
  opponentPresetIds?: Record<string, string>;
  opponentManualOverrides?: Record<string, StoredManualOverride>;
  battleType?: string;
  weather?: string;
  terrain?: string;
}

export interface StoredBattleSession {
  schemaVersion?: number;
  kind: string;
  sessionId: string;
  createdAt: string;
  selectedOwnTeamId: string;
  opponentTeam: StoredEntity[];
  calculationSelection?: StoredCalculationSelection;
}

export interface StoredTeamPreview {
  kind: string;
}

export interface StoredOwnTeamImportDraft {
  kind: string;
}

export interface AppBackupData {
  savedTeams: StoredTeam[];
  currentBattleSession?: StoredBattleSession;
  currentTeamPreview?: StoredTeamPreview;
  pendingOwnTeam?: StoredTeam;
  ownTeamImportDraft?: StoredOwnTeamImportDraft;
  userOpponentPresets?: UserOpponentPresetRoot;
  updateChannel?: string;
}

export interface AppBackupEnvelope {
  schemaVersion: number;
  kind: string;
  exportedAt: string;
  appVersion: string;
  data: AppBackupData;
}

export interface ManagedAppState {
  savedTeams: StoredTeam[];
  currentBattleSession?: StoredBattleSession;
  currentTeamPreview?: StoredTeamPreview;
  pendingOwnTeam?: StoredTeam;
  ownTeamImportDraft?: StoredOwnTeamImportDraft;
  userOpponentPresets: UserOpponentPresetRoot;
  updateChannel: string;
}

export interface ValidatedBackup {
  envelope: AppBackupEnvelope;
  hasUserOpponentPresets: boolean;
}

export interface PresetMergeSummary {
  imported: number;
  added: number;
  updated: number;
  unchanged: number;
}

export interface PresetMergeResult {
  root: UserOpponentPresetRoot;
  summary: PresetMergeSummary;
}

export interface HudPlacement {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface HudLayoutProfile {
  elements: Record<string, HudPlacement>;
}

export interface HudLayoutRoot {
  schemaVersion: number;
  kind: string;
  portrait?: HudLayoutProfile;
  landscape?: HudLayoutProfile;
}

export interface AppSettingsRoot {
  schemaVersion: number;
  kind: string;
  updateChannel: string;
}

const TEAM_ID_PATTERN = /^[A-Za-z0-9._-]{1,120}$/;
const STAT_KEYS: string[] = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];

function requireValue(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

function requireString(value: string | undefined, message: string): string {
  requireValue(typeof value === 'string' && value.length > 0, message);
  return value as string;
}

function normalizeSpeciesId(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function normalizeEntity(entity: StoredEntity, strict: boolean): StoredEntity {
  requireValue(entity !== undefined && entity !== null, '实体结构无效');
  const canonicalId = requireString(entity.canonicalId, '实体缺少 canonicalId');
  const showdownId = requireString(entity.showdownId, '实体缺少 showdownId');
  if (strict) {
    requireString(entity.entityType, '实体缺少 entityType');
    requireString(entity.displayName, '实体缺少 displayName');
  }
  const normalized: StoredEntity = { canonicalId, showdownId };
  if (entity.entityType) normalized.entityType = entity.entityType.toLowerCase();
  if (entity.displayName) normalized.displayName = entity.displayName;
  return normalized;
}

function statValue(stats: StoredStats | undefined, key: string): number | undefined {
  if (!stats) return undefined;
  const values = stats as Record<string, number>;
  const value = values[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function normalizeStatPoints(stats: StoredStats | undefined): StoredStats {
  const result: Record<string, number> = {};
  for (const key of STAT_KEYS) {
    const value = statValue(stats, key) ?? 0;
    requireValue(Number.isInteger(value) && value >= 0 && value <= 32, '预设能力点无效');
    result[key] = value;
  }
  return result as StoredStats;
}

function normalizeActualStats(stats: StoredStats | undefined): StoredStats | undefined {
  if (!stats) return undefined;
  const result: Record<string, number> = {};
  for (const key of STAT_KEYS) {
    const value = statValue(stats, key);
    if (value !== undefined && value > 0) result[key] = value;
  }
  return Object.keys(result).length > 0 ? result as StoredStats : undefined;
}

function normalizeStoredMove(value: StoredMove): StoredMove {
  const result: StoredMove = { move: normalizeEntity(value.move, true) };
  if (value.source) result.source = value.source;
  if (value.basePower !== undefined) result.basePower = value.basePower;
  if (value.type && value.type.trim().length > 0) result.type = value.type;
  return result;
}

function normalizePreset(value: StoredOpponentPreset): StoredOpponentPreset {
  const profileId = requireString(value.profileId, '用户对手预设缺少 ID');
  const profileName = requireString(value.profileName, '用户对手预设缺少名称').trim();
  requireValue(profileId.startsWith('user.') && profileId.length <= 120, '用户对手预设 ID 无效');
  requireValue(profileName.length > 0 && profileName.length <= 24, '预设名称应为 1-24 个字符');
  requireValue(value.source === 'USER_SAVED', '对手预设来源无效');
  requireValue(Number.isInteger(value.level) && value.level >= 1 && value.level <= 100, '预设等级无效');
  requireValue(Array.isArray(value.moves) && value.moves.length <= 4, '预设招式数量无效');
  const result: StoredOpponentPreset = {
    profileId,
    profileName,
    source: 'USER_SAVED',
    level: value.level,
    statPoints: normalizeStatPoints(value.statPoints),
    moves: value.moves.map((move: StoredMove) => normalizeStoredMove(move))
  };
  const actualStats = normalizeActualStats(value.actualStats);
  if (actualStats) result.actualStats = actualStats;
  if (value.statAlignment) result.statAlignment = normalizeEntity(value.statAlignment, true);
  if (value.ability) result.ability = normalizeEntity(value.ability, true);
  if (value.item) result.item = normalizeEntity(value.item, true);
  return result;
}

function pokemonMembers(team: StoredTeam): StoredPokemon[] {
  const members = team.pokemon ?? team.members;
  requireValue(Array.isArray(members), '队伍文件缺少宝可梦列表');
  return members as StoredPokemon[];
}

function validatePokemon(value: StoredPokemon): void {
  const pokemon = value.build ?? value;
  normalizeEntity(pokemon.species, false);
  if (pokemon.ability) normalizeEntity(pokemon.ability, false);
  if (pokemon.item) normalizeEntity(pokemon.item, false);
  if (pokemon.moves !== undefined) {
    requireValue(Array.isArray(pokemon.moves) && pokemon.moves.length <= 4, '队伍招式数量无效');
    pokemon.moves.forEach((move: StoredMove) => normalizeEntity(move.move, false));
  }
}

export function validateSavedTeam(team: StoredTeam, requireSix: boolean = true): StoredTeam {
  const id = requireString(team.savedTeamId, '队伍缺少 savedTeamId');
  requireValue(TEAM_ID_PATTERN.test(id), '队伍 ID 含有不安全字符');
  const members = pokemonMembers(team);
  if (requireSix) requireValue(members.length === 6, '保存队伍必须恰好包含 6 只宝可梦');
  members.forEach((member: StoredPokemon) => validatePokemon(member));
  return team;
}

export function emptyUserOpponentPresetRoot(): UserOpponentPresetRoot {
  return { schemaVersion: 1, kind: USER_PRESET_KIND, presets: [] };
}

export function validateUserOpponentPresetRoot(root: UserOpponentPresetRoot): UserOpponentPresetRoot {
  requireValue(root !== undefined && root !== null, '保存配置字段类型无效');
  requireValue(root.schemaVersion === 1, '不支持的对手预设版本');
  requireValue(root.kind === USER_PRESET_KIND, '对手预设文件类型无效');
  requireValue(Array.isArray(root.presets), '对手预设列表结构无效');
  requireValue(root.presets.length <= USER_PRESET_MAX_COUNT, '用户对手预设数量异常');
  const ids = new Set<string>();
  const entries: StoredOpponentPresetEntry[] = root.presets.map((entry: StoredOpponentPresetEntry) => {
    const speciesId = normalizeSpeciesId(requireString(entry.speciesId, '用户对手预设缺少宝可梦 ID'));
    requireValue(speciesId.length > 0, '用户对手预设缺少宝可梦 ID');
    const preset = normalizePreset(entry.preset);
    requireValue(!ids.has(preset.profileId), '用户对手预设 ID 重复');
    ids.add(preset.profileId);
    return { speciesId, preset };
  });
  return { schemaVersion: 1, kind: USER_PRESET_KIND, presets: entries };
}

export function parseUserOpponentPresetJson(json: string): UserOpponentPresetRoot {
  return validateUserOpponentPresetRoot(JSON.parse(json) as UserOpponentPresetRoot);
}

export function mergeUserOpponentPresetRoots(
  localRoot: UserOpponentPresetRoot,
  incomingRoot: UserOpponentPresetRoot
): PresetMergeResult {
  const local = validateUserOpponentPresetRoot(localRoot);
  const incoming = validateUserOpponentPresetRoot(incomingRoot);
  const merged: StoredOpponentPresetEntry[] = local.presets.slice();
  let added = 0;
  let updated = 0;
  let unchanged = 0;
  for (const entry of incoming.presets) {
    const index = merged.findIndex((candidate: StoredOpponentPresetEntry) =>
      candidate.preset.profileId === entry.preset.profileId);
    if (index < 0) {
      merged.push(entry);
      added += 1;
    } else if (JSON.stringify(merged[index]) === JSON.stringify(entry)) {
      unchanged += 1;
    } else {
      merged[index] = entry;
      updated += 1;
    }
  }
  requireValue(merged.length <= USER_PRESET_MAX_COUNT, '合并后用户对手预设数量异常');
  return {
    root: { schemaVersion: 1, kind: USER_PRESET_KIND, presets: merged },
    summary: { imported: incoming.presets.length, added, updated, unchanged }
  };
}

export function utf8ByteLength(value: string): number {
  let bytes = 0;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code <= 0x7f) bytes += 1;
    else if (code <= 0x7ff) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff && index + 1 < value.length &&
      value.charCodeAt(index + 1) >= 0xdc00 && value.charCodeAt(index + 1) <= 0xdfff) {
      bytes += 4;
      index += 1;
    } else bytes += 3;
  }
  return bytes;
}

export function buildOpponentPresetShareEnvelope(
  root: UserOpponentPresetRoot,
  exportedAt: string,
  appVersion: string
): OpponentPresetShareEnvelope {
  return {
    schemaVersion: 1,
    kind: PRESET_SHARE_KIND,
    exportedAt,
    appVersion,
    userOpponentPresets: validateUserOpponentPresetRoot(root)
  };
}

export function parseOpponentPresetShareJson(json: string): UserOpponentPresetRoot {
  requireValue(utf8ByteLength(json) <= PRESET_SHARE_MAX_BYTES, '配置分享文件超过 4 MB，已拒绝导入');
  const envelope = JSON.parse(json) as OpponentPresetShareEnvelope;
  requireValue(envelope.schemaVersion === 1, '不支持的宝可梦配置分享版本');
  requireValue(envelope.kind === PRESET_SHARE_KIND, '所选文件不是宝可梦配置分享文件');
  return validateUserOpponentPresetRoot(envelope.userOpponentPresets);
}

function validateBattleSession(session: StoredBattleSession, teamIds: Set<string>): void {
  requireValue(session.kind === 'BattleSession', '当前对局结构无效');
  requireString(session.sessionId, '当前对局缺少 sessionId');
  requireString(session.createdAt, '当前对局缺少 createdAt');
  const teamId = requireString(session.selectedOwnTeamId, '当前对局缺少我方队伍引用');
  requireValue(teamIds.has(teamId), '当前对局引用的我方队伍不在备份中');
  requireValue(Array.isArray(session.opponentTeam) && session.opponentTeam.length === 6,
    '当前对局的对方阵容不是 6 只');
  session.opponentTeam.forEach((entity: StoredEntity) => normalizeEntity(entity, false));
  if (session.calculationSelection?.battleType) {
    requireValue(session.calculationSelection.battleType === 'SINGLE' ||
      session.calculationSelection.battleType === 'DOUBLE', '当前对局的对战类型无效');
  }
}

export function validateAppBackupJson(json: string): ValidatedBackup {
  requireValue(utf8ByteLength(json) <= APP_BACKUP_MAX_BYTES, '备份文件超过 16 MB，已拒绝导入');
  const envelope = JSON.parse(json) as AppBackupEnvelope;
  requireValue(envelope.schemaVersion === 1, '不支持的备份版本');
  requireValue(envelope.kind === APP_BACKUP_KIND, '所选文件不是冠军伤害计算器备份');
  requireValue(envelope.data !== undefined && envelope.data !== null, '备份缺少 data');
  const data = envelope.data;
  requireValue(Array.isArray(data.savedTeams) && data.savedTeams.length <= 100, '备份中的队伍数量异常');
  const ids = new Set<string>();
  data.savedTeams.forEach((team: StoredTeam) => {
    validateSavedTeam(team, true);
    requireValue(!ids.has(team.savedTeamId), `备份包含重复队伍 ID：${team.savedTeamId}`);
    ids.add(team.savedTeamId);
  });
  if (data.currentBattleSession) validateBattleSession(data.currentBattleSession, ids);
  if (data.currentTeamPreview) {
    requireValue(data.currentTeamPreview.kind === 'TeamPreviewRecognitionResult', '当前队伍预览结构无效');
  }
  if (data.pendingOwnTeam) validateSavedTeam(data.pendingOwnTeam, false);
  if (data.ownTeamImportDraft) {
    requireValue(data.ownTeamImportDraft.kind === 'OwnTeamImportDraft', '我方队伍导入草稿结构无效');
  }
  const hasUserOpponentPresets = data.userOpponentPresets !== undefined;
  if (hasUserOpponentPresets) {
    data.userOpponentPresets = validateUserOpponentPresetRoot(data.userOpponentPresets as UserOpponentPresetRoot);
  }
  data.updateChannel = data.updateChannel === 'preview' ? 'preview' : 'stable';
  return { envelope, hasUserOpponentPresets };
}

export function buildAppBackupEnvelope(
  state: ManagedAppState,
  exportedAt: string,
  appVersion: string
): AppBackupEnvelope {
  const data: AppBackupData = {
    savedTeams: state.savedTeams,
    userOpponentPresets: validateUserOpponentPresetRoot(state.userOpponentPresets),
    updateChannel: state.updateChannel === 'preview' ? 'preview' : 'stable'
  };
  if (state.currentBattleSession) data.currentBattleSession = state.currentBattleSession;
  if (state.currentTeamPreview) data.currentTeamPreview = state.currentTeamPreview;
  if (state.pendingOwnTeam) data.pendingOwnTeam = state.pendingOwnTeam;
  if (state.ownTeamImportDraft) data.ownTeamImportDraft = state.ownTeamImportDraft;
  return { schemaVersion: 1, kind: APP_BACKUP_KIND, exportedAt, appVersion, data };
}

export function restoreStateFromValidatedBackup(
  current: ManagedAppState,
  validated: ValidatedBackup
): ManagedAppState {
  const data = validated.envelope.data;
  return {
    savedTeams: data.savedTeams,
    currentBattleSession: data.currentBattleSession,
    currentTeamPreview: data.currentTeamPreview,
    pendingOwnTeam: data.pendingOwnTeam,
    ownTeamImportDraft: data.ownTeamImportDraft,
    userOpponentPresets: validated.hasUserOpponentPresets ?
      data.userOpponentPresets as UserOpponentPresetRoot : current.userOpponentPresets,
    updateChannel: data.updateChannel === 'preview' ? 'preview' : 'stable'
  };
}

export function removeOpponentPresetReferences(
  selection: StoredCalculationSelection,
  profileId: string
): StoredCalculationSelection {
  const presetIds: Record<string, string> = {};
  const removedSlots = new Set<string>();
  const existing = selection.opponentPresetIds ?? {};
  for (const slot of Object.keys(existing)) {
    if (existing[slot] === profileId) removedSlots.add(slot);
    else presetIds[slot] = existing[slot];
  }
  const overrides: Record<string, StoredManualOverride> = {};
  const existingOverrides = selection.opponentManualOverrides ?? {};
  for (const slot of Object.keys(existingOverrides)) {
    const override = existingOverrides[slot];
    if (!removedSlots.has(slot) && override.baseProfileId !== profileId) overrides[slot] = override;
  }
  return {
    ...selection,
    selectedPresetId: selection.selectedPresetId === profileId ? undefined : selection.selectedPresetId,
    opponentPresetIds: presetIds,
    opponentManualOverrides: overrides
  };
}

export function validateAppSettings(root: AppSettingsRoot): AppSettingsRoot {
  requireValue(root.schemaVersion === 1 && root.kind === 'AppSettings', '设置文件结构无效');
  return { schemaVersion: 1, kind: 'AppSettings', updateChannel: root.updateChannel === 'preview' ? 'preview' : 'stable' };
}

function validateHudProfile(profile: HudLayoutProfile | undefined): HudLayoutProfile | undefined {
  if (!profile) return undefined;
  const elements: Record<string, HudPlacement> = {};
  for (const key of Object.keys(profile.elements)) {
    const value = profile.elements[key];
    if (Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.width) &&
      Number.isFinite(value.height) && value.width > 0 && value.height > 0) {
      elements[key] = value;
    }
  }
  return { elements };
}

export function validateHudLayouts(root: HudLayoutRoot): HudLayoutRoot {
  requireValue(root.schemaVersion === 1 && root.kind === 'BattleDirectHudLayouts', 'HUD 布局文件结构无效');
  const result: HudLayoutRoot = { schemaVersion: 1, kind: 'BattleDirectHudLayouts' };
  const portrait = validateHudProfile(root.portrait);
  const landscape = validateHudProfile(root.landscape);
  if (portrait) result.portrait = portrait;
  if (landscape) result.landscape = landscape;
  return result;
}
