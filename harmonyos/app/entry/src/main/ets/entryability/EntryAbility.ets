import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { display, window } from '@kit.ArkUI';
import { DEBUG, RELEASE_VARIANT } from 'BuildProfile';
import { setStage4VerificationMode } from '../storage/Stage4VerificationLaunch';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import { setStage6VerificationMode } from '../storage/Stage6VerificationLaunch';
import { setStage7VerificationMode } from '../storage/Stage7VerificationLaunch';
import { battleOverlayCoordinator } from '../services/BattleOverlayCoordinator';
import { setStage8VerificationMode } from '../storage/Stage8VerificationLaunch';
import { setStage9VerificationMode } from '../storage/Stage9VerificationLaunch';

const DOMAIN: number = 0x5043;

export default class EntryAbility extends UIAbility {
  private pagePath: string = 'pages/Index';
  private forceLandscapeForVerification: boolean = false;

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    try {
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
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
    const stage6Mode = want.parameters?.['stage6Verification'] as string | undefined;
    if (DEBUG && (stage6Mode === 'seed' || stage6Mode === 'clear')) {
      setStage6VerificationMode(stage6Mode);
      this.pagePath = 'pages/Stage6Verification';
    }
    const stage7Mode = want.parameters?.['stage7Verification'] as string | undefined;
    if (DEBUG && (stage7Mode === 'seed' || stage7Mode === 'clear' || stage7Mode === 'native-smoke' ||
      stage7Mode === 'setup')) {
      setStage7VerificationMode(stage7Mode);
      this.pagePath = 'pages/Stage7Verification';
      if (stage7Mode === 'setup') this.forceLandscapeForVerification = true;
    }
    const stage8Mode = want.parameters?.['stage8Verification'] as string | undefined;
    if (DEBUG && (stage8Mode === 'panel' || stage8Mode === 'hud' || stage8Mode === 'single' ||
      stage8Mode === 'hidden' || stage8Mode === 'restore' || stage8Mode === 'clear')) {
      AppStorage.setOrCreate<string>('battleHudDamageValues', '');
      AppStorage.setOrCreate<string>('stage8VerificationStatus', 'RUNNING');
      setStage8VerificationMode(stage8Mode);
      this.pagePath = 'pages/Stage8Verification';
      this.forceLandscapeForVerification = true;
    }
    const stage9Mode = want.parameters?.['stage9Verification'] as string | undefined;
    if (DEBUG && (stage9Mode === 'routes' || stage9Mode === 'profile')) {
      setStage9VerificationMode(stage9Mode);
      this.pagePath = 'pages/Stage9Verification';
    }
    hilog.info(DOMAIN, 'PCApp', 'APP_ABILITY_CREATED variant=%{public}s', RELEASE_VARIANT);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    if (!this.forceLandscapeForVerification) {
      this.loadContent(windowStage);
      return;
    }
    windowStage.getMainWindow()
      .then((mainWindow: window.Window) => mainWindow.setPreferredOrientation(window.Orientation.LANDSCAPE))
      .then(() => this.loadAfterLandscapeApplied(windowStage, 0))
      .catch((error: Error) => {
        hilog.error(DOMAIN, 'PCApp', 'verification landscape request failed: %{public}s', String(error));
        this.loadContent(windowStage);
      });
  }

  private loadAfterLandscapeApplied(windowStage: window.WindowStage, attempt: number): void {
    const target = display.getDefaultDisplaySync();
    if (target.width >= target.height || attempt >= 30) {
      this.loadContent(windowStage);
      return;
    }
    setTimeout(() => this.loadAfterLandscapeApplied(windowStage, attempt + 1), 100);
  }

  private loadContent(windowStage: window.WindowStage): void {
    windowStage.loadContent(this.pagePath, (error) => {
      if (error.code) {
        hilog.error(DOMAIN, 'PCApp', 'loadContent failed: %{public}s', JSON.stringify(error));
        return;
      }
      hilog.info(DOMAIN, 'PCApp', 'APP_PAGE_READY variant=%{public}s', RELEASE_VARIANT);
    });
  }

  onDestroy(): void {
    battleOverlayCoordinator.close().catch((error: Error) => {
      hilog.error(DOMAIN, 'PCApp', 'battle overlay cleanup failed: %{public}s', String(error));
    });
    ownTeamCaptureCoordinator.stop().catch((error: Error) => {
      hilog.error(DOMAIN, 'PCApp', 'capture cleanup failed: %{public}s', String(error));
    });
  }
}
