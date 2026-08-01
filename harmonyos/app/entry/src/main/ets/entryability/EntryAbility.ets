import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { RELEASE_VARIANT } from 'BuildProfile';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import { battleOverlayCoordinator } from '../services/BattleOverlayCoordinator';
import { safeUiErrorCode } from '../ui/PrivacySafeError';

const DOMAIN: number = 0x5043;

export default class EntryAbility extends UIAbility {
  onCreate(_want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    try {
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
    } catch (error) {
      hilog.error(DOMAIN, 'PCApp', 'setColorMode failed code=%{public}s', safeUiErrorCode(error));
    }
    hilog.info(DOMAIN, 'PCApp', 'APP_ABILITY_CREATED variant=%{public}s', RELEASE_VARIANT);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    this.loadContent(windowStage);
  }

  private loadContent(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (error) => {
      if (error.code) {
        hilog.error(DOMAIN, 'PCApp', 'loadContent failed code=%{public}s', safeUiErrorCode(error));
        return;
      }
      hilog.info(DOMAIN, 'PCApp', 'APP_PAGE_READY variant=%{public}s', RELEASE_VARIANT);
    });
  }

  onDestroy(): void {
    hilog.info(DOMAIN, 'PCApp', 'APP_ABILITY_DESTROY');
    battleOverlayCoordinator.destroy().catch((error: Error) => {
      hilog.error(DOMAIN, 'PCApp', 'battle overlay cleanup failed code=%{public}s', safeUiErrorCode(error));
    });
    ownTeamCaptureCoordinator.stop().catch((error: Error) => {
      hilog.error(DOMAIN, 'PCApp', 'capture cleanup failed code=%{public}s', safeUiErrorCode(error));
    });
  }
}
