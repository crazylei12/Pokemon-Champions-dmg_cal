import { BackupExtensionAbility, BundleVersion } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { AppStorageRepository } from '../storage/AppStorageRepository';

const DOMAIN: number = 0x5043;

export default class EntryBackupAbility extends BackupExtensionAbility {
  onBackup(): void {
    const count = new AppStorageRepository(this.context.filesDir).copySystemBackupTo(this.context.backupDir);
    hilog.info(DOMAIN, 'PCBackup', 'SYSTEM_BACKUP_ALLOWLIST_PASS files=%{public}d', count);
  }

  onRestore(bundleVersion: BundleVersion): void {
    const count = new AppStorageRepository(this.context.filesDir).restoreSystemBackupFrom(this.context.backupDir);
    hilog.info(DOMAIN, 'PCBackup', 'SYSTEM_RESTORE_ALLOWLIST_PASS files=%{public}d from=%{public}s',
      count, bundleVersion.name);
  }
}
