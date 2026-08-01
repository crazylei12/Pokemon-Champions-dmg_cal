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
const STORAGE_TRANSACTION_FILE = '.app-storage-transaction.json';
const MANUAL_RESTORE_PATHS: string[] = [
  'saved-teams',
  'battle-session',
  PENDING_TEAM_FILE,
  IMPORT_DRAFT_FILE,
  USER_PRESETS_FILE,
  SETTINGS_FILE
];

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

interface StorageTransactionJournal {
  schemaVersion: number;
  kind: 'AppStorageTransaction';
  stageDirectory: string;
  backupDirectory: string;
  relativePaths: string[];
  previousPaths: string[];
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

function movePath(source: string, target: string): void {
  ensureParentDirectory(target);
  fileIo.moveFileSync(source, target, 0);
}

function transactionToken(): string {
  return `${Date.now()}-${Math.floor(Math.random() * 0x7fffffff)}`;
}

function safeStorageFailure(error: Object, fallback: string): string {
  const message = String(error);
  if (/^(?:Error:\s*)?Injected (?:restore|commit rename) failure\b/.test(message)) {
    return message.replace(/^Error:\s*/, '');
  }
  return fallback;
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
    throw new Error(safeStorageFailure(error, '存储文件写入失败'));
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
    this.recoverInterruptedTransaction();
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

  saveOwnTeamImportDraft(draft: StoredOwnTeamImportDraft): void {
    if (draft.kind !== 'OwnTeamImportDraft') throw new Error('我方队伍导入草稿结构无效');
    writeUtf8Atomically(joinPath(this.filesDir, IMPORT_DRAFT_FILE), prettyJson(draft));
  }

  loadOwnTeamImportDraft(): StoredOwnTeamImportDraft | undefined {
    return readOptionalObject<StoredOwnTeamImportDraft>(joinPath(this.filesDir, IMPORT_DRAFT_FILE));
  }

  clearOwnTeamImportDraft(): void {
    const path = joinPath(this.filesDir, IMPORT_DRAFT_FILE);
    if (fileExists(path)) fileIo.unlinkSync(path);
  }

  saveCurrentTeamPreview(preview: StoredTeamPreview): void {
    if (preview.kind !== 'TeamPreviewRecognitionResult') throw new Error('当前队伍预览结构无效');
    const previewPath = joinPath(this.filesDir, CURRENT_PREVIEW_FILE);
    writeUtf8Atomically(previewPath, prettyJson(preview));
  }

  loadCurrentTeamPreview(): StoredTeamPreview | undefined {
    return readOptionalObject<StoredTeamPreview>(joinPath(this.filesDir, CURRENT_PREVIEW_FILE));
  }

  clearCurrentTeamPreview(): void {
    const path = joinPath(this.filesDir, CURRENT_PREVIEW_FILE);
    if (fileExists(path)) fileIo.unlinkSync(path);
  }

  clearCurrentBattleSession(): void {
    const target = joinPath(this.filesDir, CURRENT_SESSION_FILE);
    if (fileExists(target)) fileIo.unlinkSync(target);
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
    const sessionPath = joinPath(this.filesDir, CURRENT_SESSION_FILE);
    const session = readOptionalObject<StoredBattleSession>(sessionPath);
    const relativePaths: string[] = [`saved-teams/${team.savedTeamId}.json`];
    if (session?.selectedOwnTeamId === teamId) relativePaths.push(CURRENT_SESSION_FILE);
    const stageDirectory = `.app-storage-stage-${transactionToken()}`;
    this.commitStagedPaths(stageDirectory, relativePaths);
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
    const sessionPath = joinPath(this.filesDir, CURRENT_SESSION_FILE);
    const session = readOptionalObject<StoredBattleSession>(sessionPath);
    const stageDirectory = `.app-storage-stage-${transactionToken()}`;
    const stageRoot = joinPath(this.filesDir, stageDirectory);
    try {
      writeUtf8Atomically(joinPath(stageRoot, USER_PRESETS_FILE), prettyJson(validateUserOpponentPresetRoot({
        schemaVersion: 1, kind: 'OpponentUserPresets', presets
      })));
      const relativePaths: string[] = [USER_PRESETS_FILE];
      if (session?.calculationSelection) {
        session.calculationSelection = removeOpponentPresetReferences(session.calculationSelection, profileId);
        writeUtf8Atomically(joinPath(stageRoot, CURRENT_SESSION_FILE), prettyJson(session));
        relativePaths.push(CURRENT_SESSION_FILE);
      }
      this.commitStagedPaths(stageDirectory, relativePaths);
    } catch (error) {
      this.cleanupTransactionDirectory(stageDirectory);
      throw new Error(safeStorageFailure(error, '删除保存配置失败'));
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
    const current = this.loadManagedState();
    const next = restoreStateFromValidatedBackup(current, validated);
    const stageDirectory = `.app-storage-stage-${transactionToken()}`;
    const stageRoot = joinPath(this.filesDir, stageDirectory);
    const stagedRepository = new AppStorageRepository(stageRoot);
    stagedRepository.verificationFailureAfterWrites = failureAfterWrites;
    stagedRepository.verificationRestoreWriteCount = 0;
    try {
      stagedRepository.writeBackupState(next, validated.hasUserOpponentPresets);
      stagedRepository.validateSystemBackupState();
      this.commitStagedPaths(stageDirectory, MANUAL_RESTORE_PATHS.filter((relativePath: string) =>
        relativePath !== USER_PRESETS_FILE || validated.hasUserOpponentPresets));
    } catch (error) {
      this.cleanupTransactionDirectory(stageDirectory);
      throw new Error(safeStorageFailure(error, '恢复应用数据失败'));
    } finally {
      stagedRepository.verificationFailureAfterWrites = -1;
      stagedRepository.verificationRestoreWriteCount = 0;
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
    const stageDirectory = `.app-storage-stage-${transactionToken()}`;
    const stageRoot = joinPath(this.filesDir, stageDirectory);
    try {
      for (const entry of incoming) {
        writeUtf8Atomically(joinPath(stageRoot, entry.relativePath), entry.body);
      }
      const stagedRepository = new AppStorageRepository(stageRoot);
      stagedRepository.validateSystemBackupState();
      this.commitStagedPaths(stageDirectory, SYSTEM_BACKUP_ALLOWLIST);
    } catch (error) {
      this.cleanupTransactionDirectory(stageDirectory);
      throw new Error(safeStorageFailure(error, '恢复系统备份失败'));
    }
    return incoming.length;
  }

  writeRawForVerification(relativePath: string, body: string): void {
    if (!this.isManagedTransactionPath(relativePath)) {
      throw new Error('Verification write is outside the managed allowlist.');
    }
    writeUtf8Atomically(joinPath(this.filesDir, relativePath), body);
  }

  readRawForVerification(relativePath: string): string | undefined {
    return readText(joinPath(this.filesDir, relativePath));
  }

  private transactionJournalPath(): string {
    return joinPath(this.filesDir, STORAGE_TRANSACTION_FILE);
  }

  private isManagedTransactionPath(relativePath: string): boolean {
    return relativePath.length > 0 && !relativePath.startsWith('/') && !relativePath.includes('\\') &&
      relativePath.split('/').every((part: string) => part.length > 0 && part !== '.' && part !== '..') &&
      SYSTEM_BACKUP_ALLOWLIST.some((allowed: string) =>
        relativePath === allowed || relativePath.startsWith(`${allowed}/`));
  }

  private validateTransactionJournal(value: StorageTransactionJournal): StorageTransactionJournal {
    if (!value || value.schemaVersion !== 1 || value.kind !== 'AppStorageTransaction' ||
      !/^\.app-storage-stage-[0-9]+-[0-9]+$/.test(value.stageDirectory) ||
      !/^\.app-storage-backup-[0-9]+-[0-9]+$/.test(value.backupDirectory) ||
      !Array.isArray(value.relativePaths) || value.relativePaths.length === 0 ||
      !Array.isArray(value.previousPaths)) throw new Error('存储事务日志结构无效');
    const paths = new Set<string>();
    for (const relativePath of value.relativePaths) {
      if (!this.isManagedTransactionPath(relativePath) || paths.has(relativePath)) {
        throw new Error('存储事务日志包含无效路径');
      }
      paths.add(relativePath);
    }
    if (value.previousPaths.some((relativePath: string) => !paths.has(relativePath))) {
      throw new Error('存储事务日志的旧路径清单无效');
    }
    return value;
  }

  private recoverInterruptedTransaction(): void {
    const journalPath = this.transactionJournalPath();
    const body = readText(journalPath);
    if (body === undefined) return;
    const journal = this.validateTransactionJournal(JSON.parse(body) as StorageTransactionJournal);
    this.rollbackTransaction(journal);
    fileIo.unlinkSync(journalPath);
    this.cleanupTransactionDirectory(journal.stageDirectory);
    this.cleanupTransactionDirectory(journal.backupDirectory);
  }

  private commitStagedPaths(stageDirectory: string, relativePaths: string[]): void {
    if (!/^\.app-storage-stage-[0-9]+-[0-9]+$/.test(stageDirectory)) {
      throw new Error('存储事务 staging 目录无效');
    }
    const paths: string[] = [];
    for (const relativePath of relativePaths) {
      if (!this.isManagedTransactionPath(relativePath) || paths.includes(relativePath)) {
        throw new Error('存储事务包含无效或重复路径');
      }
      paths.push(relativePath);
    }
    if (paths.length === 0) throw new Error('存储事务没有待处理路径');
    this.recoverInterruptedTransaction();
    const token = transactionToken();
    const backupDirectory = `.app-storage-backup-${token}`;
    const journal: StorageTransactionJournal = {
      schemaVersion: 1,
      kind: 'AppStorageTransaction',
      stageDirectory,
      backupDirectory,
      relativePaths: paths,
      previousPaths: paths.filter((relativePath: string) => fileExists(joinPath(this.filesDir, relativePath)))
    };
    writeUtf8Atomically(this.transactionJournalPath(), prettyJson(journal));
    try {
      for (const relativePath of paths) {
        const live = joinPath(this.filesDir, relativePath);
        const staged = joinPath(joinPath(this.filesDir, stageDirectory), relativePath);
        const backup = joinPath(joinPath(this.filesDir, backupDirectory), relativePath);
        if (fileExists(live)) movePath(live, backup);
        if (fileExists(staged)) movePath(staged, live);
      }
      fileIo.unlinkSync(this.transactionJournalPath());
      this.cleanupTransactionDirectory(stageDirectory);
      this.cleanupTransactionDirectory(backupDirectory);
    } catch (error) {
      let rollbackError: string | undefined;
      try {
        this.rollbackTransaction(journal);
        if (fileExists(this.transactionJournalPath())) fileIo.unlinkSync(this.transactionJournalPath());
        this.cleanupTransactionDirectory(stageDirectory);
        this.cleanupTransactionDirectory(backupDirectory);
      } catch (rollbackFailure) {
        rollbackError = safeStorageFailure(rollbackFailure, '存储事务回滚失败');
      }
      if (rollbackError) throw new Error(`存储事务提交失败；回滚将在下次启动继续：${rollbackError}`);
      throw new Error(safeStorageFailure(error, '存储事务提交失败'));
    }
  }

  private rollbackTransaction(journal: StorageTransactionJournal): void {
    const stageRoot = joinPath(this.filesDir, journal.stageDirectory);
    const backupRoot = joinPath(this.filesDir, journal.backupDirectory);
    const previous = new Set<string>(journal.previousPaths);
    for (let index = journal.relativePaths.length - 1; index >= 0; index -= 1) {
      const relativePath = journal.relativePaths[index];
      const live = joinPath(this.filesDir, relativePath);
      const backup = joinPath(backupRoot, relativePath);
      const discard = joinPath(stageRoot, `__rollback__/${index}`);
      if (fileExists(backup)) {
        if (fileExists(discard)) deleteRecursively(discard);
        if (fileExists(live)) movePath(live, discard);
        movePath(backup, live);
      } else if (!previous.has(relativePath) && fileExists(live)) {
        if (fileExists(discard)) deleteRecursively(discard);
        movePath(live, discard);
      }
    }
  }

  private cleanupTransactionDirectory(relativePath: string): void {
    if (!/^\.app-storage-(?:stage|backup)-[0-9]+-[0-9]+$/.test(relativePath)) return;
    try {
      deleteRecursively(joinPath(this.filesDir, relativePath));
    } catch (error) {
      // Transaction cleanup is best-effort after the durable commit/rollback marker is gone.
    }
  }

  private writeUserOpponentPresets(root: UserOpponentPresetRoot): void {
    const validated = validateUserOpponentPresetRoot(root);
    writeUtf8Atomically(joinPath(this.filesDir, USER_PRESETS_FILE), prettyJson(validated));
  }

  private readSavedTeams(strict: boolean): StoredTeam[] {
    const directory = joinPath(this.filesDir, 'saved-teams');
    if (!fileExists(directory)) return [];
    const teams: StoredTeam[] = [];
    const files = fileIo.listFileSync(directory).filter((value: string) => value.endsWith('.json'))
      .map((name: string): { name: string; modifiedAt: number } => ({
        name,
        modifiedAt: Number(fileIo.statSync(joinPath(directory, name)).mtime)
      }))
      .sort((left: { name: string; modifiedAt: number }, right: { name: string; modifiedAt: number }): number =>
        right.modifiedAt - left.modifiedAt || left.name.localeCompare(right.name));
    for (const file of files) {
      try {
        const team = JSON.parse(fileIo.readTextSync(joinPath(directory, file.name), { encoding: 'utf-8' })) as StoredTeam;
        teams.push(validateSavedTeam(team, true));
      } catch (error) {
        if (strict) throw new Error('保存的队伍文件无效');
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
