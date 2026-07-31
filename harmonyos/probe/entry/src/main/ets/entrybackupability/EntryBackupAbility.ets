import { BackupExtensionAbility, BundleVersion } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x5043;

export default class EntryBackupAbility extends BackupExtensionAbility {
  async onBackup(): Promise<void> {
    hilog.info(DOMAIN, 'PCProbe', 'PROBE_BACKUP_CALLED');
    await Promise.resolve();
  }

  async onRestore(bundleVersion: BundleVersion): Promise<void> {
    hilog.info(DOMAIN, 'PCProbe', 'PROBE_RESTORE_CALLED %{public}s', JSON.stringify(bundleVersion));
    await Promise.resolve();
  }
}
