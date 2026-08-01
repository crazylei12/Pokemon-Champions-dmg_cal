import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { webview } from '@kit.ArkWeb';
import { DamageEngineRuntime } from '../domain/DamageEngineRuntime';
import { isGoldenProjection } from '../domain/DamageProjection';
import { DamageProjection, EngineInfo } from '../domain/Models';
import { RuntimeCatalogSummary, RuntimeDataRepository } from '../domain/RuntimeDataRepository';
import { userOpponentPresetSharingForms } from '../domain/PresetLogic';

const DOMAIN: number = 0x5043;
const ITERATIONS: number = 100;
const GOLDEN_REQUEST_JSON: string = '{"requestId":"harmony-port-phase0-own-output","calculationDirection":"OWN_TO_OPPONENT","attackerSide":"OWN","attacker":{"species":{"entityType":"species","canonicalId":"species.mawile","showdownId":"Mawile","displayName":"大嘴娃","source":"manual"},"level":50,"actualStats":{"hp":157,"atk":150,"def":105,"spa":75,"spd":77,"spe":63},"ability":{"entityType":"ability","canonicalId":"ability.intimidate","showdownId":"Intimidate","displayName":"威吓","source":"manual"},"item":{"entityType":"item","canonicalId":"item.mawilite","showdownId":"Mawilite","displayName":"大嘴娃进化石","source":"manual"},"moves":[{"move":{"entityType":"move","canonicalId":"move.playrough","showdownId":"Play Rough","displayName":"嬉闹","source":"manual"},"source":"OWN_BUILD"}]},"defenderSide":"OPPONENT","defenderIdentity":{"species":{"entityType":"species","canonicalId":"species.armarouge","showdownId":"Armarouge","displayName":"红莲铠骑","source":"manual"}},"defenderProfileSet":{"defenderSpecies":{"entityType":"species","canonicalId":"species.armarouge","showdownId":"Armarouge","displayName":"红莲铠骑","source":"manual"},"selectedProfileId":"phase0-exact-defender","profiles":[{"profileId":"phase0-exact-defender","profileName":"阶段 0 精确防守配置","source":"MANUAL_CURRENT","isSelected":true,"includedInEnvelope":true,"level":50,"actualStats":{"hp":161,"atk":72,"def":120,"spa":194,"spd":100,"spe":127},"ability":{"entityType":"ability","canonicalId":"ability.flashfire","showdownId":"Flash Fire","displayName":"引火","source":"manual"}}]},"moveSelection":{"mode":"ONE_MOVE","moveId":"move.playrough"},"battle":{"battleType":"SINGLE","weather":"NONE","terrain":"NONE","attackerSideConditions":{},"defenderSideConditions":{}},"calculationMode":"EXACT"}';

@Entry
@Component
struct Stage3Verification {
  @State status: string = 'RUNNING';
  private started: boolean = false;
  private controller: webview.WebviewController = new webview.WebviewController('pc-stage3-damage');

  private async runVerification(): Promise<void> {
    if (this.started) {
      return;
    }
    this.started = true;
    const startedAt = Date.now();
    try {
      const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      const repository = new RuntimeDataRepository(context);
      const summary: RuntimeCatalogSummary = await repository.load();
      const mawile = repository.formsFor('Mawile');
      const selectedMawile = mawile.find((form) => form.species.showdownId === 'Mawile');
      const megaMawile = mawile.find((form) => form.species.showdownId === 'Mawile-Mega');
      if (summary.speciesCount !== 163 || summary.profileCount !== 645 || summary.formGroupCount !== 97 ||
        summary.speciesFormCount !== 324 || repository.localize('species', 'Mawile')?.displayName !== '大嘴娃' ||
        !selectedMawile || !megaMawile || megaMawile.defaultAbility?.showdownId !== 'Huge Power' ||
        userOpponentPresetSharingForms(selectedMawile, mawile).length !== 2 ||
        !repository.legalMovesFor('Staraptor').some((move) => move.entity.showdownId === 'Blaze Kick') ||
        repository.legalMovesFor('Staraptor').some((move) => move.entity.showdownId === 'Toxic')) {
        throw new Error(`runtime catalog mismatch: ${JSON.stringify(summary)}`);
      }
      const runtime = new DamageEngineRuntime(this.controller);
      const info: EngineInfo = await runtime.getEngineInfo();
      let reference = '';
      for (let iteration = 0; iteration < ITERATIONS; iteration += 1) {
        const projection: DamageProjection = await runtime.calculate(GOLDEN_REQUEST_JSON);
        if (!isGoldenProjection(projection)) {
          throw new Error(`golden mismatch at iteration ${iteration + 1}: ${JSON.stringify(projection)}`);
        }
        const serialized = JSON.stringify(projection);
        if (iteration === 0) {
          reference = serialized;
        } else if (serialized !== reference) {
          throw new Error(`context drift at iteration ${iteration + 1}`);
        }
      }

      let invalidRejected = false;
      try {
        await runtime.calculate('{');
      } catch (error) {
        invalidRejected = true;
      }
      if (!invalidRejected) {
        throw new Error('invalid request was not rejected');
      }
      if (!isGoldenProjection(await runtime.calculate(GOLDEN_REQUEST_JSON))) {
        throw new Error('engine did not recover after invalid request');
      }

      const elapsedMs = Date.now() - startedAt;
      this.status = `PASS ${ITERATIONS} ${elapsedMs}`;
      hilog.info(DOMAIN, 'PCStage3',
        'STAGE3_DAMAGE_100_PASS iterations=%{public}d elapsedMs=%{public}d version=%{public}s offline=%{public}s',
        ITERATIONS, elapsedMs, info.version, String(info.offline));
      hilog.info(DOMAIN, 'PCStage3',
        'STAGE3_CATALOG_PASS localization=%{public}d species=%{public}d profiles=%{public}d forms=%{public}d',
        summary.localizationEntries, summary.speciesCount, summary.profileCount, summary.speciesFormCount);
    } catch (error) {
      this.status = `FAIL ${String(error)}`;
      hilog.error(DOMAIN, 'PCStage3', 'STAGE3_DAMAGE_100_FAIL %{public}s', this.status);
    }
  }

  build() {
    Column({ space: 16 }) {
      Text('本地计算组件验证')
        .fontSize(24)
        .fontWeight(FontWeight.Bold)
      Text(this.status)
        .id('stage3-verification-status')
        .fontSize(16)
      Web({
        src: $rawfile('runtime/damage/engine-host.html'),
        controller: this.controller
      })
        .javaScriptAccess(true)
        .onPageEnd(() => this.runVerification())
        .width(1)
        .height(1)
        .opacity(0)
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .backgroundColor('#F4F7FB')
  }
}
