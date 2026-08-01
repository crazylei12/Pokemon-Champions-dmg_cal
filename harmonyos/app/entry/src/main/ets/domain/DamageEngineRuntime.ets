import { webview } from '@kit.ArkWeb';
import { DamageProjection, EngineInfo } from './Models';
import { parseEngineInfo, projectDamageResponse } from './DamageProjection';

function unwrapJavaScriptString(value: string): string {
  try {
    const parsed: string | object = JSON.parse(value) as string | object;
    return typeof parsed === 'string' ? parsed : value;
  } catch (error) {
    return value;
  }
}

export class DamageEngineRuntime {
  private controller: webview.WebviewController;

  constructor(controller: webview.WebviewController) {
    this.controller = controller;
  }

  async getEngineInfo(): Promise<EngineInfo> {
    const value = await this.controller.runJavaScript(
      'window.PokemonChampionsDamageEngine.getEngineInfo()');
    return parseEngineInfo(unwrapJavaScriptString(value));
  }

  async calculateRaw(requestJson: string): Promise<string> {
    const expression = `window.PokemonChampionsDamageEngine.calculateDamage(${JSON.stringify(requestJson)})`;
    const value = await this.controller.runJavaScript(expression);
    return unwrapJavaScriptString(value);
  }

  async calculate(requestJson: string): Promise<DamageProjection> {
    return projectDamageResponse(await this.calculateRaw(requestJson));
  }
}
