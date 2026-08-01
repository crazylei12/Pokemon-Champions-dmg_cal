import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { DEBUG, RELEASE_VARIANT } from 'BuildProfile';
import { setStage4VerificationMode } from '../storage/Stage4VerificationLaunch';

const DOMAIN: number = 0x5043;

export default class EntryAbility extends UIAbility {
  private pagePath: string = 'pages/Index';

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    try {
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
    } catch (error) {
      hilog.error(DOMAIN, 'PCApp', 'setColorMode failed: %{public}s', JSON.stringify(error));
    }
    const verificationFlag = want.parameters?.['stage3Verification'];
    if (DEBUG && verificationFlag === 'true') {
      this.pagePath = 'pages/Stage3Verification';
    }
    const stage4Mode = want.parameters?.['stage4Verification'] as string | undefined;
    if (DEBUG && (stage4Mode === 'seed' || stage4Mode === 'verify')) {
      setStage4VerificationMode(stage4Mode);
      this.pagePath = 'pages/Stage4Verification';
    }
    hilog.info(DOMAIN, 'PCApp', 'APP_ABILITY_CREATED variant=%{public}s', RELEASE_VARIANT);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent(this.pagePath, (error) => {
      if (error.code) {
        hilog.error(DOMAIN, 'PCApp', 'loadContent failed: %{public}s', JSON.stringify(error));
        return;
      }
      hilog.info(DOMAIN, 'PCApp', 'APP_PAGE_READY variant=%{public}s', RELEASE_VARIANT);
    });
  }
}
