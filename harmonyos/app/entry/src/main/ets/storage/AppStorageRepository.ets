import { fileIo } from '@kit.CoreFileKit';
import {
  AppBackupEnvelope,
  AppSettingsRoot,
  buildAppBackupEnvelope,
  buildOpponentPresetShareEnvelope,
  emptyUserOpponentPresetRoot,
  HudLayoutRoot,
  ManagedAppState,
  mergeUserOpponentPresetRoots,
  OpponentPresetShareEnvelope,
  parseOpponentPresetShareJson,
  parseUserOpponentPresetJson,
  PresetMergeSummary,
  removeOpponentPresetReferences,
  restoreStateFromValidatedBackup,
  StoredBattleSession,
  StoredOwnTeamImportDraft,
  StoredOpponentPresetEntry,
  StoredTeam,
  StoredTeamPreview,
  UserOpponentPresetRoot,
  validateAppBackupJson,
  validateAppSettings,
  validateHudLayouts,
  validateSavedTeam,
  validateUserOpponentPresetRoot
} from './StorageContracts';

export const USER_PRESETS_FILE = 'user-opponent-presets.json';
export const SETTINGS_FILE = 'app-settings.json';
export const HUD_LAYOUTS_FILE = 'battle-direct-hud-layouts.json';
export const SYSTEM_BACKUP_ALLOWLIST: string[] = [
  'saved-teams',
  'battle-session',
  'pending-own-team.json',
  'own-team-import-draft.json',
  USER_PRESETS_FILE,
  SETTINGS_FILE,
  HUD_LAYOUTS_FILE
];

const CURRENT_SESSION_FILE = 'battle-session/current-battle-session.json';
const CURRENT_PREVIEW_FILE = 'battle-session/current-team-preview.json';
const PENDING_TEAM_FILE = 'pending-own-team.json';
const IMPORT_DRAFT_FILE = 'own-team-import-draft.json';
const STORAGE_PROBLEM_MESSAGE = '保存的宝可梦配置文件无法读取，已停止写入以免覆盖原数据。';

export interface UserPresetLoadResult {
  root: UserOpponentPresetRoot;
  problem?: string;
  originalBody?: string;
}

export interface AppBackupSummary {
  teamCount: number;
  hasBattleSession: boolean;
  userOpponentPresetCount: number;
}

interface StorageSnapshotEntry {
  relativePath: string;
  body: string;
}

function joinPath(base: string, relative: string): string {
  return `${base.replace(/\/$/, '')}/${relative.replace(/^\//, '')}`;
}

function fileExists(path: string): boolean {
  try {
    return fileIo.accessSync(path);
  } catch (error) {
    return false;
  }
}

function ensureParentDirectory(path: string): void {
  const separator = path.lastIndexOf('/');
  if (separator <= 0) return;
  const parent = path.slice(0, separator);
  if (!fileExists(parent)) fileIo.mkdirSync(parent, true);
}

export function writeUtf8Atomically(path: string, body: string): void {
  ensureParentDirectory(path);
  const temporary = `${path}.tmp`;
  let handle: fileIo.File | undefined;
  try {
    handle = fileIo.openSync(temporary,
      fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC | fileIo.OpenMode.SYNC);
    fileIo.writeSync(handle.fd, body);
    fileIo.fsyncSync(handle.fd);
    fileIo.closeSync(handle);
    handle = undefined;
    fileIo.moveFileSync(temporary, path, 0);
  } catch (error) {
    if (handle) fileIo.closeSync(handle);
    if (fileExists(temporary)) fileIo.unlinkSync(temporary);
    throw new Error(String(error));
  }
}

function deleteRecursively(path: string): void {
  if (!fileExists(path)) return;
  const stat = fileIo.statSync(path);
  if (!stat.isDirectory()) {
    fileIo.unlinkSync(path);
    return;
  }
  for (const name of fileIo.listFileSync(path)) {
    deleteRecursively(joinPath(path, name));
  }
  fileIo.rmdirSync(path);
}

function readText(path: string): string | undefined {
  return fileExists(path) ? fileIo.readTextSync(path, { encoding: 'utf-8' }) : undefined;
}

function prettyJson(value: Object): string {
  return JSON.stringify(value, null, 2);
}

function readOptionalObject<T>(path: string): T | undefined {
  const body = readText(path);
  return body === undefined ? undefined : JSON.parse(body) as T;
}

export class AppStorageRepository {
  private filesDir: string;
  private verificationFailureAfterWrites: number = -1;
  private verificationRestoreWriteCount: number = 0;

  constructor(filesDir: string) {
    this.filesDir = filesDir;
  }

  loadUserOpponentPresets(): UserPresetLoadResult {
    const path = joinPath(this.filesDir, USER_PRESETS_FILE);
    const body = readText(path);
    if (body === undefined) return { root: emptyUserOpponentPresetRoot() };
    try {
      return { root: parseUserOpponentPresetJson(body) };
    } catch (error) {
      return { root: emptyUserOpponentPresetRoot(), problem: STORAGE_PROBLEM_MESSAGE, originalBody: body };
    }
  }

  saveUserOpponentPresets(root: UserOpponentPresetRoot): void {
    const loaded = this.loadUserOpponentPresets();
    if (loaded.problem) throw new Error(`${loaded.problem} 请先保留原文件副本并重置后重试。`);
    this.writeUserOpponentPresets(root);
  }

  mergeOpponentPresetShareJson(json: string): PresetMergeSummary {
    const loaded = this.loadUserOpponentPresets();
    if (loaded.problem) throw new Error(`${loaded.problem} 请先保留原文件副本并重置后重试。`);
    const incoming = parseOpponentPresetShareJson(json);
    const merged = mergeUserOpponentPresetRoots(loaded.root, incoming);
    if (merged.summary.added > 0 || merged.summary.updated > 0) this.writeUserOpponentPresets(merged.root);
    return merged.summary;
  }

  exportOpponentPresetShareJson(exportedAt: string, appVersion: string): string {
    const loaded = this.loadUserOpponentPresets();
    if (loaded.problem) throw new Error(`${loaded.problem} 已禁止导出。`);
    const envelope: OpponentPresetShareEnvelope = buildOpponentPresetShareEnvelope(
      loaded.root, exportedAt, appVersion);
    return prettyJson(envelope);
  }

  preserveCorruptedUserPresetsAndReset(timestamp: number = Date.now()): string {
    const loaded = this.loadUserOpponentPresets();
    if (!loaded.problem || loaded.originalBody === undefined) throw new Error('当前保存配置文件没有检测到损坏');
    const source = joinPath(this.filesDir, USER_PRESETS_FILE);
    let suffix = 0;
    let relative = '';
    do {
      relative = `user-opponent-presets.corrupt-${timestamp}${suffix === 0 ? '' : `-${suffix}`}.json`;
      suffix += 1;
    } while (fileExists(joinPath(this.filesDir, relative)));
    fileIo.copyFileSync(source, joinPath(this.filesDir, relative));
    this.writeUserOpponentPresets(emptyUserOpponentPresetRoot());
    return relative;
  }

  saveTeam(team: StoredTeam): void {
    validateSavedTeam(team, true);
    writeUtf8Atomically(joinPath(this.filesDir, `saved-teams/${team.savedTeamId}.json`), prettyJson(team));
  }

  renameTeam(teamId: string, name: string): void {
    const normalized = name.trim();
    if (normalized.length === 0) throw new Error('队伍名称不能为空');
    if (normalized.length > 30) throw new Error('队伍名称不能超过 30 个字符');
    const team = this.readSavedTeams(false).find((entry: StoredTeam) => entry.savedTeamId === teamId);
    if (!team) throw new Error('找不到要重命名的队伍');
    team.teamName = normalized;
    team.teamSlotName = normalized;
    this.saveTeam(team);
  }

  deleteTeam(teamId: string): void {
    const teams = this.readSavedTeams(false);
    const team = teams.find((entry: StoredTeam) => entry.savedTeamId === teamId);
    if (!team) throw new Error('找不到要删除的队伍');
    const target = joinPath(this.filesDir, `saved-teams/${team.savedTeamId}.json`);
    if (fileExists(target)) fileIo.unlinkSync(target);
    const sessionPath = joinPath(this.filesDir, CURRENT_SESSION_FILE);
    const session = readOptionalObject<StoredBattleSession>(sessionPath);
    if (session?.selectedOwnTeamId === teamId && fileExists(sessionPath)) fileIo.unlinkSync(sessionPath);
  }

  saveCurrentBattleSession(session: StoredBattleSession): void {
    const state = this.loadManagedState();
    const candidate: ManagedAppState = {
      savedTeams: state.savedTeams,
      currentBattleSession: session,
      currentTeamPreview: state.currentTeamPreview,
      pendingOwnTeam: state.pendingOwnTeam,
      ownTeamImportDraft: state.ownTeamImportDraft,
      userOpponentPresets: state.userOpponentPresets,
      updateChannel: state.updateChannel
    };
    const envelope = buildAppBackupEnvelope(candidate,
      '1970-01-01T00:00:00Z', 'validation');
    validateAppBackupJson(JSON.stringify(envelope));
    writeUtf8Atomically(joinPath(this.filesDir, CURRENT_SESSION_FILE), prettyJson(session));
  }

  upsertUserOpponentPreset(entry: StoredOpponentPresetEntry): void {
    const loaded = this.loadUserOpponentPresets();
    if (loaded.problem) throw new Error(`${loaded.problem} 请先保留原文件副本并重置后重试。`);
    const presets = [...loaded.root.presets];
    const index = presets.findIndex((candidate: StoredOpponentPresetEntry) =>
      candidate.preset.profileId === entry.preset.profileId);
    if (index >= 0) presets[index] = entry;
    else presets.push(entry);
    this.writeUserOpponentPresets({ schemaVersion: 1, kind: 'OpponentUserPresets', presets });
  }

  deleteUserOpponentPreset(profileId: string): boolean {
    const loaded = this.loadUserOpponentPresets();
    if (loaded.problem) throw new Error(`${loaded.problem} 请先保留原文件副本并重置后重试。`);
    const presets = loaded.root.presets.filter((entry: StoredOpponentPresetEntry) =>
      entry.preset.profileId !== profileId);
    if (presets.length === loaded.root.presets.length) return false;
    this.writeUserOpponentPresets({ schemaVersion: 1, kind: 'OpponentUserPresets', presets });
    const sessionPath = joinPath(this.filesDir, CURRENT_SESSION_FILE);
    const session = readOptionalObject<StoredBattleSession>(sessionPath);
    if (session?.calculationSelection) {
      session.calculationSelection = removeOpponentPresetReferences(session.calculationSelection, profileId);
      this.saveCurrentBattleSession(session);
    }
    return true;
  }

  saveUpdateChannel(channel: string): void {
    const settings = validateAppSettings({
      schemaVersion: 1,
      kind: 'AppSettings',
      updateChannel: channel
    });
    writeUtf8Atomically(joinPath(this.filesDir, SETTINGS_FILE), prettyJson(settings));
  }

  loadUpdateChannel(): string {
    const root = readOptionalObject<AppSettingsRoot>(joinPath(this.filesDir, SETTINGS_FILE));
    if (!root) return 'stable';
    try {
      return validateAppSettings(root).updateChannel;
    } catch (error) {
      return 'stable';
    }
  }

  saveHudLayouts(root: HudLayoutRoot): void {
    writeUtf8Atomically(joinPath(this.filesDir, HUD_LAYOUTS_FILE), prettyJson(validateHudLayouts(root)));
  }

  loadHudLayouts(): HudLayoutRoot {
    const root = readOptionalObject<HudLayoutRoot>(joinPath(this.filesDir, HUD_LAYOUTS_FILE));
    if (!root) return { schemaVersion: 1, kind: 'BattleDirectHudLayouts' };
    try {
      return validateHudLayouts(root);
    } catch (error) {
      return { schemaVersion: 1, kind: 'BattleDirectHudLayouts' };
    }
  }

  loadManagedState(): ManagedAppState {
    return {
      savedTeams: this.readSavedTeams(false),
      currentBattleSession: readOptionalObject<StoredBattleSession>(joinPath(this.filesDir, CURRENT_SESSION_FILE)),
      currentTeamPreview: readOptionalObject<StoredTeamPreview>(joinPath(this.filesDir, CURRENT_PREVIEW_FILE)),
      pendingOwnTeam: readOptionalObject<StoredTeam>(joinPath(this.filesDir, PENDING_TEAM_FILE)),
      ownTeamImportDraft: readOptionalObject<StoredOwnTeamImportDraft>(joinPath(this.filesDir, IMPORT_DRAFT_FILE)),
      userOpponentPresets: this.loadUserOpponentPresets().root,
      updateChannel: this.loadUpdateChannel()
    };
  }

  buildAppBackupJson(exportedAt: string, appVersion: string): string {
    const presets = this.loadUserOpponentPresets();
    if (presets.problem) throw new Error(`${presets.problem} 已禁止导出。`);
    const state = this.loadManagedState();
    state.userOpponentPresets = presets.root;
    const envelope: AppBackupEnvelope = buildAppBackupEnvelope(state, exportedAt, appVersion);
    validateAppBackupJson(JSON.stringify(envelope));
    return prettyJson(envelope);
  }

  restoreAppBackupJson(json: string): AppBackupSummary {
    return this.restoreAppBackupJsonInternal(json, -1);
  }

  restoreAppBackupJsonWithInjectedFailureForVerification(json: string, afterWrites: number): AppBackupSummary {
    if (!Number.isInteger(afterWrites) || afterWrites < 1) {
      throw new Error('Injected restore failure must occur after at least one write.');
    }
    return this.restoreAppBackupJsonInternal(json, afterWrites);
  }

  private restoreAppBackupJsonInternal(json: string, failureAfterWrites: number): AppBackupSummary {
    const validated = validateAppBackupJson(json);
    const snapshot = this.snapshotManagedFiles();
    const current = this.loadManagedState();
    const next = restoreStateFromValidatedBackup(current, validated);
    this.verificationFailureAfterWrites = failureAfterWrites;
    this.verificationRestoreWriteCount = 0;
    try {
      this.clearManualBackupFiles(validated.hasUserOpponentPresets);
      this.writeBackupState(next, validated.hasUserOpponentPresets);
    } catch (error) {
      this.verificationFailureAfterWrites = -1;
      this.restoreSnapshot(snapshot);
      throw new Error(String(error));
    } finally {
      this.verificationFailureAfterWrites = -1;
      this.verificationRestoreWriteCount = 0;
    }
    return {
      teamCount: next.savedTeams.length,
      hasBattleSession: next.currentBattleSession !== undefined,
      userOpponentPresetCount: next.userOpponentPresets.presets.length
    };
  }

  copySystemBackupTo(backupDir: string): number {
    const snapshot = this.snapshotAllowedFiles(this.filesDir);
    this.clearAllowlistedFiles(backupDir);
    for (const entry of snapshot) {
      writeUtf8Atomically(joinPath(backupDir, entry.relativePath), entry.body);
    }
    return snapshot.length;
  }

  restoreSystemBackupFrom(backupDir: string): number {
    const incoming = this.snapshotAllowedFiles(backupDir);
    const incomingRepository = new AppStorageRepository(backupDir);
    incomingRepository.validateSystemBackupState();
    const previous = this.snapshotAllowedFiles(this.filesDir);
    try {
      this.clearAllowlistedFiles(this.filesDir);
      for (const entry of incoming) {
        writeUtf8Atomically(joinPath(this.filesDir, entry.relativePath), entry.body);
      }
    } catch (error) {
      this.clearAllowlistedFiles(this.filesDir);
      for (const entry of previous) {
        writeUtf8Atomically(joinPath(this.filesDir, entry.relativePath), entry.body);
      }
      throw new Error(String(error));
    }
    return incoming.length;
  }

  writeRawForVerification(relativePath: string, body: string): void {
    if (!SYSTEM_BACKUP_ALLOWLIST.some((allowed: string) =>
      relativePath === allowed || relativePath.startsWith(`${allowed}/`))) {
      throw new Error('Verification write is outside the managed allowlist.');
    }
    writeUtf8Atomically(joinPath(this.filesDir, relativePath), body);
  }

  readRawForVerification(relativePath: string): string | undefined {
    return readText(joinPath(this.filesDir, relativePath));
  }

  private writeUserOpponentPresets(root: UserOpponentPresetRoot): void {
    const validated = validateUserOpponentPresetRoot(root);
    writeUtf8Atomically(joinPath(this.filesDir, USER_PRESETS_FILE), prettyJson(validated));
  }

  private readSavedTeams(strict: boolean): StoredTeam[] {
    const directory = joinPath(this.filesDir, 'saved-teams');
    if (!fileExists(directory)) return [];
    const teams: StoredTeam[] = [];
    for (const name of fileIo.listFileSync(directory).filter((value: string) => value.endsWith('.json')).sort()) {
      try {
        const team = JSON.parse(fileIo.readTextSync(joinPath(directory, name), { encoding: 'utf-8' })) as StoredTeam;
        teams.push(validateSavedTeam(team, true));
      } catch (error) {
        if (strict) throw new Error(String(error));
      }
    }
    return teams;
  }

  private writeBackupState(state: ManagedAppState, writeUserPresets: boolean): void {
    for (const team of state.savedTeams) {
      this.saveTeam(team);
      this.verificationRestoreCheckpoint();
    }
    if (state.currentBattleSession) {
      writeUtf8Atomically(joinPath(this.filesDir, CURRENT_SESSION_FILE), prettyJson(state.currentBattleSession));
      this.verificationRestoreCheckpoint();
    }
    if (state.currentTeamPreview) {
      writeUtf8Atomically(joinPath(this.filesDir, CURRENT_PREVIEW_FILE), prettyJson(state.currentTeamPreview));
      this.verificationRestoreCheckpoint();
    }
    if (state.pendingOwnTeam) {
      writeUtf8Atomically(joinPath(this.filesDir, PENDING_TEAM_FILE), prettyJson(state.pendingOwnTeam));
      this.verificationRestoreCheckpoint();
    }
    if (state.ownTeamImportDraft) {
      writeUtf8Atomically(joinPath(this.filesDir, IMPORT_DRAFT_FILE), prettyJson(state.ownTeamImportDraft));
      this.verificationRestoreCheckpoint();
    }
    if (writeUserPresets) {
      this.writeUserOpponentPresets(state.userOpponentPresets);
      this.verificationRestoreCheckpoint();
    }
    this.saveUpdateChannel(state.updateChannel);
    this.verificationRestoreCheckpoint();
  }

  private verificationRestoreCheckpoint(): void {
    if (this.verificationFailureAfterWrites < 0) return;
    this.verificationRestoreWriteCount += 1;
    if (this.verificationRestoreWriteCount >= this.verificationFailureAfterWrites) {
      throw new Error(`Injected restore failure after ${this.verificationRestoreWriteCount} write(s).`);
    }
  }

  private clearManualBackupFiles(clearUserPresets: boolean): void {
    deleteRecursively(joinPath(this.filesDir, 'saved-teams'));
    deleteRecursively(joinPath(this.filesDir, 'battle-session'));
    for (const relative of [PENDING_TEAM_FILE, IMPORT_DRAFT_FILE]) {
      const path = joinPath(this.filesDir, relative);
      if (fileExists(path)) fileIo.unlinkSync(path);
    }
    if (clearUserPresets) {
      const path = joinPath(this.filesDir, USER_PRESETS_FILE);
      if (fileExists(path)) fileIo.unlinkSync(path);
    }
  }

  private snapshotManagedFiles(): StorageSnapshotEntry[] {
    return this.snapshotAllowedFiles(this.filesDir);
  }

  private restoreSnapshot(snapshot: StorageSnapshotEntry[]): void {
    this.clearAllowlistedFiles(this.filesDir);
    for (const entry of snapshot) {
      writeUtf8Atomically(joinPath(this.filesDir, entry.relativePath), entry.body);
    }
  }

  private snapshotAllowedFiles(baseDir: string): StorageSnapshotEntry[] {
    const entries: StorageSnapshotEntry[] = [];
    for (const relative of SYSTEM_BACKUP_ALLOWLIST) {
      this.collectTextFiles(baseDir, relative, entries);
    }
    return entries;
  }

  private collectTextFiles(baseDir: string, relative: string, entries: StorageSnapshotEntry[]): void {
    const path = joinPath(baseDir, relative);
    if (!fileExists(path)) return;
    const stat = fileIo.statSync(path);
    if (!stat.isDirectory()) {
      entries.push({ relativePath: relative, body: fileIo.readTextSync(path, { encoding: 'utf-8' }) });
      return;
    }
    for (const name of fileIo.listFileSync(path).sort()) {
      this.collectTextFiles(baseDir, `${relative}/${name}`, entries);
    }
  }

  private clearAllowlistedFiles(baseDir: string): void {
    if (!fileExists(baseDir)) fileIo.mkdirSync(baseDir, true);
    for (const relative of SYSTEM_BACKUP_ALLOWLIST) deleteRecursively(joinPath(baseDir, relative));
  }

  private validateSystemBackupState(): void {
    const presets = this.loadUserOpponentPresets();
    if (presets.problem) throw new Error(STORAGE_PROBLEM_MESSAGE);
    const state: ManagedAppState = {
      savedTeams: this.readSavedTeams(true),
      currentBattleSession: readOptionalObject<StoredBattleSession>(joinPath(this.filesDir, CURRENT_SESSION_FILE)),
      currentTeamPreview: readOptionalObject<StoredTeamPreview>(joinPath(this.filesDir, CURRENT_PREVIEW_FILE)),
      pendingOwnTeam: readOptionalObject<StoredTeam>(joinPath(this.filesDir, PENDING_TEAM_FILE)),
      ownTeamImportDraft: readOptionalObject<StoredOwnTeamImportDraft>(joinPath(this.filesDir, IMPORT_DRAFT_FILE)),
      userOpponentPresets: presets.root,
      updateChannel: this.loadUpdateChannel()
    };
    const envelope = buildAppBackupEnvelope(state, '1970-01-01T00:00:00Z', 'system-backup-validation');
    validateAppBackupJson(JSON.stringify(envelope));
    const settings = readOptionalObject<AppSettingsRoot>(joinPath(this.filesDir, SETTINGS_FILE));
    if (settings) validateAppSettings(settings);
    const hud = readOptionalObject<HudLayoutRoot>(joinPath(this.filesDir, HUD_LAYOUTS_FILE));
    if (hud) validateHudLayouts(hud);
  }
}
