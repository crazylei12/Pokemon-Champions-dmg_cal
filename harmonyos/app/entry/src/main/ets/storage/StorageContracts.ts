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
  source?: string;
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
  priority?: number;
}

export interface StoredPokemon {
  slotIndex?: number;
  species: StoredEntity;
  level?: number;
  actualStats?: StoredStats;
  statPoints?: StoredStats;
  ability?: StoredEntity;
  item?: StoredEntity;
  moves?: StoredMove[];
  build?: StoredPokemon;
  warnings?: string[];
}

export interface StoredTeam {
  schemaVersion?: number;
  kind?: string;
  savedTeamId: string;
  teamName?: string;
  teamSlotName?: string;
  status?: string;
  importStatus?: string;
  importSource?: string;
  damageReady?: boolean;
  userConfirmed?: boolean;
  generatedAt?: string;
  createdAt?: string;
  updatedAt?: string;
  source?: Record<string, string | number | boolean>;
  pokemon?: StoredPokemon[];
  members?: StoredPokemon[];
  warnings?: string[];
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
  statPoints?: StoredStats;
  statAlignment?: StoredEntity;
  ability?: StoredEntity;
  itemOverrideEnabled?: boolean;
  item?: StoredEntity;
}

export interface StoredStatStages {
  atk?: number;
  def?: number;
  spa?: number;
  spd?: number;
  spe?: number;
}

export interface StoredPokemonCondition {
  burned?: boolean;
  stages?: StoredStatStages;
}

export interface StoredSpeedModifiers {
  stage?: number;
  paralyzed?: boolean;
  doubled?: boolean;
  choiceScarf?: boolean;
}

export interface StoredSpeedLine {
  ownTailwind?: boolean;
  opponentTailwind?: boolean;
  trickRoom?: boolean;
  ownPokemon?: Record<string, StoredSpeedModifiers>;
  opponentPokemon?: Record<string, StoredSpeedModifiers>;
}

export interface StoredDirectHudState {
  ownSlots?: number[];
  opponentSlots?: number[];
  mode?: 'TYPE_MATCHUP' | 'CALCULATION' | 'HIDDEN';
  visible?: boolean;
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
  selectedMoveId?: string;
  ownFormOverrides?: Record<string, StoredEntity>;
  opponentFormOverrides?: Record<string, StoredEntity>;
  ownReflect?: boolean;
  ownLightScreen?: boolean;
  ownAuroraVeil?: boolean;
  opponentReflect?: boolean;
  opponentLightScreen?: boolean;
  opponentAuroraVeil?: boolean;
  ownProtected?: boolean;
  opponentProtected?: boolean;
  helpingHand?: boolean;
  critical?: boolean;
  spread?: boolean;
  ownConditions?: Record<string, StoredPokemonCondition>;
  opponentConditions?: Record<string, StoredPokemonCondition>;
  ownBurned?: boolean;
  opponentBurned?: boolean;
  ownStages?: StoredStatStages;
  opponentStages?: StoredStatStages;
  speedLine?: StoredSpeedLine;
  directHud?: StoredDirectHudState;
}

export interface StoredBattleSession {
  schemaVersion?: number;
  kind: string;
  sessionId: string;
  createdAt: string;
  previewCapturedAt?: string;
  selectedOwnTeamId: string;
  opponentTeam: StoredEntity[];
  calculationSelection?: StoredCalculationSelection;
}

export interface StoredTeamPreview {
  kind: string;
  capturedAt?: string;
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
const PROFILE_ID_PATTERN = /^user\.[A-Za-z0-9._-]{1,115}$/;
const STAT_KEYS: string[] = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];
const MAX_JSON_NESTING_DEPTH = 64;
const MAX_ENTITY_TEXT_LENGTH = 160;

function requireValue(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

function requireString(value: string | undefined, message: string): string {
  requireValue(typeof value === 'string' && value.length > 0, message);
  return value as string;
}

function requireBoundedString(value: string | undefined, maximum: number, message: string): string {
  const result = requireString(value, message);
  requireValue(result.length <= maximum, message);
  return result;
}

function requireFiniteInteger(value: number | undefined, minimum: number, maximum: number, message: string): number {
  requireValue(typeof value === 'number' && Number.isFinite(value) && Number.isInteger(value) &&
    value >= minimum && value <= maximum, message);
  return value as number;
}

function requireSafeJsonText(json: string, maximumBytes: number, sizeMessage: string): void {
  requireValue(typeof json === 'string', '导入内容必须是 UTF-8 JSON 文本');
  requireValue(utf8ByteLength(json) <= maximumBytes, sizeMessage);
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = 0; index < json.length; index += 1) {
    const value = json[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (value === '\\') escaped = true;
      else if (value === '"') inString = false;
      continue;
    }
    if (value === '"') inString = true;
    else if (value === '{' || value === '[') {
      depth += 1;
      requireValue(depth <= MAX_JSON_NESTING_DEPTH, 'JSON 嵌套层级过深，已拒绝导入');
    } else if (value === '}' || value === ']') {
      depth -= 1;
      requireValue(depth >= 0, 'JSON 结构无效');
    }
  }
  requireValue(!inString && depth === 0, 'JSON 结构无效');
}

function normalizeSpeciesId(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function normalizeEntity(entity: StoredEntity, strict: boolean): StoredEntity {
  requireValue(entity !== undefined && entity !== null, '实体结构无效');
  const canonicalId = requireBoundedString(entity.canonicalId, MAX_ENTITY_TEXT_LENGTH, '实体 canonicalId 无效');
  const showdownId = requireBoundedString(entity.showdownId, MAX_ENTITY_TEXT_LENGTH, '实体 showdownId 无效');
  if (strict) {
    requireBoundedString(entity.entityType, 24, '实体缺少 entityType');
    requireBoundedString(entity.displayName, MAX_ENTITY_TEXT_LENGTH, '实体缺少 displayName');
  }
  if (entity.entityType) requireValue(['species', 'move', 'ability', 'item', 'nature', 'type']
    .includes(entity.entityType.toLowerCase()), '实体类型无效');
  if (entity.displayName) requireValue(entity.displayName.length <= MAX_ENTITY_TEXT_LENGTH, '实体显示名称过长');
  const normalized: StoredEntity = { canonicalId, showdownId };
  if (entity.entityType) normalized.entityType = entity.entityType.toLowerCase();
  if (entity.displayName) normalized.displayName = entity.displayName;
  // Android's persisted preset round trip canonicalizes every entity to the
  // user source, even when an older share omitted the optional field.
  normalized.source = 'user';
  return normalized;
}

function statValue(stats: StoredStats | undefined, key: string): number | undefined {
  if (!stats) return undefined;
  requireValue(typeof stats === 'object' && !Array.isArray(stats), '能力值结构无效');
  const values = stats as Record<string, number>;
  const value = values[key];
  return value;
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
    if (value === undefined) continue;
    requireValue(Number.isFinite(value) && Number.isInteger(value) && value >= 1 && value <= 10000,
      '实际能力值无效');
    result[key] = value;
  }
  return Object.keys(result).length > 0 ? result as StoredStats : undefined;
}

function normalizeStoredMove(value: StoredMove): StoredMove {
  requireValue(value !== undefined && value !== null && typeof value === 'object', '招式结构无效');
  const result: StoredMove = { move: normalizeEntity(value.move, true) };
  if (value.source) result.source = requireBoundedString(value.source, 48, '招式来源无效');
  if (value.basePower !== undefined) result.basePower = requireFiniteInteger(value.basePower, 0, 1000, '招式威力无效');
  if (value.priority !== undefined) result.priority = requireFiniteInteger(value.priority, -7, 7, '招式先制度无效');
  if (value.type && value.type.trim().length > 0) {
    requireValue(value.type.length <= 32, '招式属性无效');
    result.type = value.type;
  }
  return result;
}

function normalizePreset(value: StoredOpponentPreset): StoredOpponentPreset {
  const profileId = requireBoundedString(value.profileId, 120, '用户对手预设缺少 ID');
  const profileName = requireBoundedString(value.profileName, 24, '用户对手预设缺少名称').trim();
  requireValue(PROFILE_ID_PATTERN.test(profileId), '用户对手预设 ID 无效');
  requireValue(profileName.length > 0 && profileName.length <= 24, '预设名称应为 1-24 个字符');
  requireValue(value.source === 'USER_SAVED', '对手预设来源无效');
  requireFiniteInteger(value.level, 1, 100, '预设等级无效');
  requireValue(Array.isArray(value.moves) && value.moves.length <= 4, '预设招式数量无效');
  const normalizedMoves = value.moves.map((move: StoredMove) => normalizeStoredMove(move));
  const moveIds = normalizedMoves.map((move: StoredMove) => normalizeSpeciesId(move.move.showdownId));
  requireValue(new Set<string>(moveIds).size === moveIds.length, '预设包含重复招式');
  const result: StoredOpponentPreset = {
    profileId,
    profileName,
    source: 'USER_SAVED',
    level: value.level,
    statPoints: normalizeStatPoints(value.statPoints),
    moves: normalizedMoves
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
  requireValue(value !== undefined && value !== null && typeof value === 'object', '队伍宝可梦结构无效');
  const pokemon = value.build ?? value;
  normalizeEntity(pokemon.species, false);
  if (pokemon.level !== undefined) requireFiniteInteger(pokemon.level, 1, 100, '宝可梦等级无效');
  if (pokemon.statPoints) normalizeStatPoints(pokemon.statPoints);
  if (pokemon.actualStats) normalizeActualStats(pokemon.actualStats);
  if (pokemon.ability) normalizeEntity(pokemon.ability, false);
  if (pokemon.item) normalizeEntity(pokemon.item, false);
  if (pokemon.moves !== undefined) {
    requireValue(Array.isArray(pokemon.moves) && pokemon.moves.length <= 4, '队伍招式数量无效');
    const moveIds: string[] = [];
    pokemon.moves.forEach((move: StoredMove) => {
      requireValue(move !== undefined && move !== null && typeof move === 'object' && move.move !== undefined,
        '队伍招式结构无效');
      const normalized = normalizeEntity(move.move, false);
      moveIds.push(normalizeSpeciesId(normalized.showdownId));
      if (move.basePower !== undefined) requireFiniteInteger(move.basePower, 0, 1000, '队伍招式威力无效');
      if (move.priority !== undefined) requireFiniteInteger(move.priority, -7, 7, '队伍招式先制度无效');
    });
    requireValue(new Set<string>(moveIds).size === moveIds.length, '队伍包含重复招式');
  }
}

export function validateSavedTeam(team: StoredTeam, requireSix: boolean = true): StoredTeam {
  requireValue(team !== undefined && team !== null && typeof team === 'object', '队伍结构无效');
  const id = requireString(team.savedTeamId, '队伍缺少 savedTeamId');
  requireValue(TEAM_ID_PATTERN.test(id), '队伍 ID 含有不安全字符');
  const members = pokemonMembers(team);
  if (requireSix) requireValue(members.length === 6, '保存队伍必须恰好包含 6 只宝可梦');
  if (requireSix) {
    const name = (team.teamName ?? team.teamSlotName ?? '').trim();
    requireValue(name.length >= 1 && name.length <= 30, '保存队伍名称应为 1–30 个字符');
  }
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
  requireSafeJsonText(json, PRESET_SHARE_MAX_BYTES, '保存配置文件超过 4 MB，已拒绝读取');
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
  requireSafeJsonText(json, PRESET_SHARE_MAX_BYTES, '配置分享文件超过 4 MB，已拒绝导入');
  const envelope = JSON.parse(json) as OpponentPresetShareEnvelope;
  requireValue(envelope !== undefined && envelope !== null && typeof envelope === 'object', '配置分享结构无效');
  requireValue(envelope.schemaVersion === 1, '不支持的宝可梦配置分享版本');
  requireValue(envelope.kind === PRESET_SHARE_KIND, '所选文件不是宝可梦配置分享文件');
  return validateUserOpponentPresetRoot(envelope.userOpponentPresets);
}

function validateConditionMap(value: Record<string, StoredPokemonCondition> | undefined, message: string): void {
  if (!value) return;
  requireValue(typeof value === 'object' && !Array.isArray(value), message);
  for (const key of Object.keys(value)) {
    requireValue(/^[0-5]$/.test(key), message);
    const condition = value[key];
    requireValue(condition !== undefined && condition !== null && typeof condition === 'object', message);
    for (const stat of ['atk', 'def', 'spa', 'spd', 'spe']) {
      const stage = condition.stages?.[stat as keyof StoredStatStages];
      if (stage !== undefined) requireFiniteInteger(stage, -6, 6, message);
    }
  }
}

function validateCalculationSelection(selection: StoredCalculationSelection | undefined): void {
  if (!selection) return;
  requireValue(typeof selection === 'object' && !Array.isArray(selection), '当前对局计算状态无效');
  if (selection.direction) requireValue(selection.direction === 'OWN_TO_OPPONENT' ||
    selection.direction === 'OPPONENT_TO_OWN', '当前对局计算方向无效');
  if (selection.ownSlot !== undefined) requireFiniteInteger(selection.ownSlot, 0, 5, '当前对局我方槽位无效');
  if (selection.opponentSlot !== undefined) {
    requireFiniteInteger(selection.opponentSlot, 0, 5, '当前对局对方槽位无效');
  }
  if (selection.battleType) requireValue(selection.battleType === 'SINGLE' || selection.battleType === 'DOUBLE',
    '当前对局的对战类型无效');
  if (selection.weather) requireValue(['NONE', 'Sun', 'Rain', 'Sand', 'Snow'].includes(selection.weather),
    '当前对局天气无效');
  if (selection.terrain) requireValue(['NONE', 'Electric', 'Grassy', 'Psychic', 'Misty'].includes(selection.terrain),
    '当前对局场地无效');
  if (selection.directHud?.mode) requireValue(
    ['TYPE_MATCHUP', 'CALCULATION', 'HIDDEN'].includes(selection.directHud.mode),
    '当前对局 HUD 模式无效');
  validateConditionMap(selection.ownConditions, '当前对局我方状态无效');
  validateConditionMap(selection.opponentConditions, '当前对局对方状态无效');
  for (const stages of [selection.ownStages, selection.opponentStages]) {
    if (!stages) continue;
    for (const stat of ['atk', 'def', 'spa', 'spd', 'spe']) {
      const stage = stages[stat as keyof StoredStatStages];
      if (stage !== undefined) requireFiniteInteger(stage, -6, 6, '当前对局旧版能力等级无效');
    }
  }
}

function validateBattleSession(session: StoredBattleSession, teamIds: Set<string>): void {
  requireValue(session !== undefined && session !== null && typeof session === 'object', '当前对局结构无效');
  requireValue(session.kind === 'BattleSession', '当前对局结构无效');
  requireBoundedString(session.sessionId, 160, '当前对局缺少 sessionId');
  requireBoundedString(session.createdAt, 80, '当前对局缺少 createdAt');
  const teamId = requireString(session.selectedOwnTeamId, '当前对局缺少我方队伍引用');
  requireValue(teamIds.has(teamId), '当前对局引用的我方队伍不在备份中');
  requireValue(Array.isArray(session.opponentTeam) && session.opponentTeam.length === 6,
    '当前对局的对方阵容不是 6 只');
  session.opponentTeam.forEach((entity: StoredEntity) => normalizeEntity(entity, false));
  validateCalculationSelection(session.calculationSelection);
}

export function validateAppBackupJson(json: string): ValidatedBackup {
  requireSafeJsonText(json, APP_BACKUP_MAX_BYTES, '备份文件超过 16 MB，已拒绝导入');
  const envelope = JSON.parse(json) as AppBackupEnvelope;
  requireValue(envelope !== undefined && envelope !== null && typeof envelope === 'object', '备份结构无效');
  requireValue(envelope.schemaVersion === 1, '不支持的备份版本');
  requireValue(envelope.kind === APP_BACKUP_KIND, '所选文件不是冠军伤害计算器备份');
  requireBoundedString(envelope.exportedAt, 80, '备份缺少 exportedAt');
  requireBoundedString(envelope.appVersion, 80, '备份缺少 appVersion');
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
  requireValue(root !== undefined && root !== null && typeof root === 'object', '设置文件结构无效');
  requireValue(root.schemaVersion === 1 && root.kind === 'AppSettings', '设置文件结构无效');
  return { schemaVersion: 1, kind: 'AppSettings', updateChannel: root.updateChannel === 'preview' ? 'preview' : 'stable' };
}

function validateHudProfile(profile: HudLayoutProfile | undefined,
  discardLegacyOwnRecognition: boolean): HudLayoutProfile | undefined {
  if (!profile) return undefined;
  requireValue(typeof profile === 'object' && !Array.isArray(profile) && profile.elements !== undefined &&
    profile.elements !== null && typeof profile.elements === 'object' && !Array.isArray(profile.elements),
  'HUD 布局分组结构无效');
  const elements: Record<string, HudPlacement> = {};
  for (const key of Object.keys(profile.elements)) {
    requireValue(key.length > 0 && key.length <= 80 && key !== '__proto__' && key !== 'constructor',
      'HUD 布局元素 ID 无效');
    if (discardLegacyOwnRecognition && key === 'OWN_RECOGNITION') continue;
    const value = profile.elements[key];
    if (value && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.width) &&
      Number.isFinite(value.height) && value.width > 0 && value.height > 0) {
      elements[key] = value;
    }
  }
  return { elements };
}

export function validateHudLayouts(root: HudLayoutRoot): HudLayoutRoot {
  requireValue(root !== undefined && root !== null && typeof root === 'object', 'HUD 布局文件结构无效');
  requireValue((root.schemaVersion === 1 || root.schemaVersion === 2) && root.kind === 'BattleDirectHudLayouts',
    'HUD 布局文件结构无效');
  const result: HudLayoutRoot = { schemaVersion: 2, kind: 'BattleDirectHudLayouts' };
  const discardLegacyOwnRecognition = root.schemaVersion === 1;
  const portrait = validateHudProfile(root.portrait, discardLegacyOwnRecognition);
  const landscape = validateHudProfile(root.landscape, discardLegacyOwnRecognition);
  if (portrait) result.portrait = portrait;
  if (landscape) result.landscape = landscape;
  return result;
}
