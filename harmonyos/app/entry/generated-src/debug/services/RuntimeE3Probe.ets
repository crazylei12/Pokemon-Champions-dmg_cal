import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x5043;

class RuntimeE3Probe {
  async waitForCalculation(): Promise<void> {
    hilog.info(DOMAIN, 'PCApp', 'APP_DEBUG_CALC_PROBE_DELAY');
    await this.wait(900);
  }

  async waitForStartup(): Promise<void> {
    hilog.info(DOMAIN, 'PCApp', 'APP_DEBUG_STARTUP_PROBE_DELAY');
    await this.wait(3200);
  }

  async waitForUpdate(): Promise<void> {
    hilog.info(DOMAIN, 'PCApp', 'APP_DEBUG_UPDATE_PROBE_DELAY');
    await this.wait(2000);
  }

  private async wait(milliseconds: number): Promise<void> {
    await new Promise<void>((resolve: () => void): void => {
      setTimeout(resolve, milliseconds);
    });
  }
}

export const runtimeE3Probe: RuntimeE3Probe = new RuntimeE3Probe();
