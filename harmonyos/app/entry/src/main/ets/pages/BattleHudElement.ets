import promptAction from '@ohos.promptAction';
import { webview } from '@kit.ArkWeb';
import { REPLAY_ENABLED } from 'BuildProfile';
import { DamageEngineRuntime } from '../domain/DamageEngineRuntime';
import {
  battleDirectHudToggleLabel,
  battleStatusText,
  parseBattleDirectDamageValues,
  SpeedLineAction
} from '../domain/BattleSession';
import { OpponentProfile } from '../domain/Models';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import {
  battleOverlayCoordinator,
  BattleHudElement,
  BattleOverlaySnapshot,
  BattleTypeMatchup
} from '../services/BattleOverlayCoordinator';
import { replayRecordingCoordinator } from '../services/ReplayRecordingCoordinator';
import { AsyncRequestGate, damageRequestCacheKey, StringLruCache } from '../ui/AppUiModels';
import { safeUiError } from '../ui/PrivacySafeError';

const HUD_BACKGROUND: string = '#DE0E141B';
const HUD_SURFACE: string = '#E81C2731';
const HUD_SELECTED_SURFACE: string = '#EE2E3830';
const HUD_BORDER: string = '#566976';
const HUD_SELECTED: string = '#F4C542';
const HUD_OWN: string = '#48B2FF';
const HUD_OPPONENT: string = '#FF914C';
const HUD_TEXT: string = '#F4F8FB';
const HUD_MUTED: string = '#C0CCD6';
const RESIST_SURFACE: string = '#DA053548';
const RESIST_BORDER: string = '#11B5E0';
const WEAK_SURFACE: string = '#DA652A17';
const WEAK_BORDER: string = '#F06F43';
const TYPE_GLYPHS: Map<string, string> = new Map<string, string>([
  ['Normal', '普'], ['Fighting', '斗'], ['Flying', '飞'], ['Poison', '毒'], ['Ground', '地'], ['Rock', '岩'],
  ['Bug', '虫'], ['Ghost', '幽'], ['Steel', '钢'], ['Fire', '火'], ['Water', '水'], ['Grass', '草'],
  ['Electric', '电'], ['Psychic', '超'], ['Ice', '冰'], ['Dragon', '龙'], ['Dark', '恶'], ['Fairy', '妖']
]);
const TYPE_COLORS: Map<string, string> = new Map<string, string>([
  ['Normal', '#929DA3'], ['Fighting', '#CE416B'], ['Flying', '#8FA9DE'], ['Poison', '#AA6BC8'],
  ['Ground', '#D97845'], ['Rock', '#C5B78C'], ['Bug', '#91C12F'], ['Ghost', '#5269AD'],
  ['Steel', '#5A8EA2'], ['Fire', '#FF9D55'], ['Water', '#5090D6'], ['Grass', '#63BC5A'],
  ['Electric', '#F4D23C'], ['Psychic', '#FA7179'], ['Ice', '#73CEC0'], ['Dragon', '#0B6DC3'],
  ['Dark', '#5A5465'], ['Fairy', '#EC8FE6']
]);

interface TypeMultiplier {
  key: string;
  label: string;
}

const RESIST_MULTIPLIERS: TypeMultiplier[] = [
  { key: '0', label: '0' }, { key: '0.25', label: '¼' }, { key: '0.5', label: '½' }
];
const WEAK_MULTIPLIERS: TypeMultiplier[] = [{ key: '4', label: '4' }, { key: '2', label: '2' }];

function option(value: string): SelectOption {
  return { value };
}

function options(values: string[]): SelectOption[] {
  return values.map((value: string): SelectOption => option(value));
}

@Component
export struct BattleHudElementContent {
  element: string = 'STATUS';
  @StorageLink('battleOverlayRevision') @Watch('onOverlayRevisionChanged') overlayRevision: number = 0;
  @State engineReady: boolean = false;
  @State calculating: boolean = false;
  @State ownRecognitionBusy: boolean = false;
  @State hudDamage: string[] = ['1 —', '2 —', '3 —', '4 —'];
  @State dragX: number = 0;
  @State dragY: number = 0;
  @State resizeX: number = 0;
  @State resizeY: number = 0;

  private controller: webview.WebviewController = new webview.WebviewController('pc-battle-direct-damage');
  private engine?: DamageEngineRuntime;
  private calculationGate: AsyncRequestGate = new AsyncRequestGate();
  private calculationPending: boolean = false;
  private damageCache: StringLruCache = new StringLruCache(12);

  aboutToAppear(): void {
    this.calculationGate.activate();
    this.overlayRevision;
  }

  aboutToDisappear(): void {
    this.calculationPending = false;
    this.calculationGate.dispose();
  }

  private onOverlayRevisionChanged(): void {
    if (this.element !== 'DAMAGE' || !this.engineReady) return;
    if (this.calculating) {
      this.calculationPending = true;
      this.calculationGate.supersede();
      return;
    }
    this.calculateHud();
  }

  private current(): BattleOverlaySnapshot {
    this.overlayRevision;
    return battleOverlayCoordinator.snapshot();
  }

  private hudElement(): BattleHudElement {
    return this.element as BattleHudElement;
  }

  private elementId(): string {
    return `battle-hud-${this.element.toLowerCase().replace('_', '-')}`;
  }

  private onEngineLoaded(): void {
    this.engine = new DamageEngineRuntime(this.controller);
    this.engine.getEngineInfo().then(() => {
      this.engineReady = true;
      this.calculateHud();
    }).catch((error: Error) => battleOverlayCoordinator.setMessage(safeUiError(error, '伤害引擎初始化失败')));
  }

  private async calculateHud(): Promise<void> {
    if (!this.engineReady || !this.engine || !this.current().ready) return;
    if (this.calculating) {
      this.calculationPending = true;
      this.calculationGate.supersede();
      return;
    }
    this.calculating = true;
    this.calculationPending = false;
    const generation = this.calculationGate.begin();
    let fingerprint: string = '';
    const slowTimer = setTimeout(() => {
      if (this.calculationGate.isCurrent(generation)) this.hudDamage = ['计算较慢', '仍在处理', '—', '—'];
    }, 30000);
    let timeoutTimer: number = 0;
    try {
      const prepared = battleOverlayCoordinator.prepareDamage(true);
      fingerprint = damageRequestCacheKey(prepared.request);
      let raw = this.damageCache.get(fingerprint);
      if (raw === undefined) {
        raw = await Promise.race<string>([
          this.engine.calculateRaw(prepared.request),
          new Promise<string>((_resolve, reject) => {
            timeoutTimer = setTimeout(() => reject(new Error('HUD 伤害计算超过 90 秒')), 90000);
          })
        ]);
        this.damageCache.set(fingerprint, raw);
      }
      if (!this.calculationGate.isCurrent(generation)) return;
      if (damageRequestCacheKey(battleOverlayCoordinator.prepareDamage(true).request) !== fingerprint) {
        this.calculationPending = true;
        return;
      }
      this.hudDamage = parseBattleDirectDamageValues(raw, prepared.configuredMoves);
    } catch (error) {
      if (this.calculationGate.isCurrent(generation)) this.hudDamage = ['计算失败', '点击详细', '重试', '—'];
    } finally {
      clearTimeout(slowTimer);
      if (timeoutTimer > 0) clearTimeout(timeoutTimer);
      this.calculating = false;
      if (this.calculationPending) {
        this.calculationPending = false;
        setTimeout(() => this.calculateHud(), 0);
      }
    }
  }

  private async recognizePreview(): Promise<void> {
    let setupShown: boolean = false;
    try {
      await battleOverlayCoordinator.minimize();
      battleOverlayCoordinator.setMessage(await ownTeamCaptureCoordinator.recognizeTeamPreview());
      await ownTeamCaptureCoordinator.minimizeFloatWindow();
      await battleOverlayCoordinator.showSetup();
      setupShown = true;
    } catch (error) {
      battleOverlayCoordinator.setMessage(safeUiError(error, '识别双方阵容失败'));
    } finally {
      if (!setupShown) await battleOverlayCoordinator.reveal();
    }
  }

  private async recognizeOwnTeam(): Promise<void> {
    if (this.ownRecognitionBusy) return;
    this.ownRecognitionBusy = true;
    try {
      await battleOverlayCoordinator.minimize();
      battleOverlayCoordinator.setMessage(await ownTeamCaptureCoordinator.recognizeOwnTeam());
    } catch (error) {
      battleOverlayCoordinator.setMessage(safeUiError(error, '识别我的队伍失败'));
    } finally {
      this.ownRecognitionBusy = false;
      await battleOverlayCoordinator.reveal();
    }
  }

  private recordingLabel(): string {
    if (!REPLAY_ENABLED) return '录像';
    if (replayRecordingCoordinator.isRecording()) return '停止录像';
    if (replayRecordingCoordinator.hasPendingRecording()) return '保存录像';
    return '录像';
  }

  private async offerSilentRecording(): Promise<void> {
    const response = await promptAction.showDialog({
      title: '内部声音不可用',
      message: '不会自动改成无声录像。是否明确继续无声录制？',
      buttons: [
        { text: '继续无声录制', color: HUD_SELECTED },
        { text: '取消本次录像', color: '#FF8A80' }
      ]
    });
    const status = response.index === 0 ? replayRecordingCoordinator.continueSilently() :
      replayRecordingCoordinator.cancelRecording();
    battleOverlayCoordinator.setMessage(status);
  }

  private async toggleRecording(): Promise<void> {
    if (!REPLAY_ENABLED) return;
    try {
      let status: string;
      if (replayRecordingCoordinator.hasPendingRecording()) {
        status = await replayRecordingCoordinator.publishPending();
      } else if (replayRecordingCoordinator.isRecording()) {
        status = await replayRecordingCoordinator.stopAndPublish();
      } else {
        status = replayRecordingCoordinator.startRecordingOnCurrentCapture();
        setTimeout(() => {
          if (replayRecordingCoordinator.silentFallbackNeeded()) this.offerSilentRecording();
        }, 1500);
      }
      battleOverlayCoordinator.setMessage(status);
    } catch (error) {
      battleOverlayCoordinator.setMessage(safeUiError(error, '切换录屏状态失败'));
      if (replayRecordingCoordinator.canRetrySilently()) await this.offerSilentRecording();
    }
  }

  private async confirmCloseSession(): Promise<void> {
    const response = await promptAction.showDialog({
      title: '结束对局助手',
      message: '将关闭本局 HUD、伤害面板和画面会话。是否继续？',
      buttons: [
        { text: '结束会话', color: '#FF8A80' },
        { text: '取消', color: HUD_TEXT }
      ]
    });
    if (response.index !== 0) return;
    await ownTeamCaptureCoordinator.stop();
    await battleOverlayCoordinator.close();
  }

  private pickerSide(): string {
    return this.element.startsWith('OWN_') ? 'OWN' : 'OPPONENT';
  }

  private pickerDisplayIndex(snapshot: BattleOverlaySnapshot): number {
    if (this.element === 'OPPONENT_RIGHT') return snapshot.state.battleType === 'DOUBLE' ? 1 : 0;
    if (this.element === 'OWN_RIGHT') return 1;
    return 0;
  }

  private pickerSlot(snapshot: BattleOverlaySnapshot): number {
    const slots = this.pickerSide() === 'OWN' ? snapshot.state.directHud.ownSlots :
      snapshot.state.directHud.opponentSlots;
    return slots[this.pickerDisplayIndex(snapshot)] ?? 0;
  }

  private pickerNames(snapshot: BattleOverlaySnapshot): string[] {
    return this.pickerSide() === 'OWN' ? snapshot.ownNames : snapshot.opponentNames;
  }

  private selectPicker(snapshot: BattleOverlaySnapshot, index: number): void {
    battleOverlayCoordinator.replaceHudSlot(this.pickerSide() === 'OWN' ? 'OWN' : 'OPPONENT',
      this.pickerDisplayIndex(snapshot), index);
  }

  private selectedProfileIndex(snapshot: BattleOverlaySnapshot): number {
    return Math.max(0, snapshot.profiles.findIndex((profile: OpponentProfile) =>
      profile.profileId === snapshot.selectedProfile?.profileId));
  }

  private speedText(action: SpeedLineAction, index: number): string {
    const speed = action.minimumSpeed === action.maximumSpeed ? String(action.minimumSpeed) :
      `${action.minimumSpeed}–${action.maximumSpeed}`;
    return `${index + 1} ${action.side === 'OWN' ? '我' : '对'}·${action.pokemonName}  ${speed}`;
  }

  private async moveElement(event: GestureEvent): Promise<void> {
    if (!battleOverlayCoordinator.isLayoutEditing()) return;
    const deltaX = event.offsetX - this.dragX;
    const deltaY = event.offsetY - this.dragY;
    this.dragX = event.offsetX;
    this.dragY = event.offsetY;
    await battleOverlayCoordinator.moveHudElementBy(this.hudElement(), deltaX, deltaY);
  }

  private async resizeElement(event: GestureEvent): Promise<void> {
    if (!battleOverlayCoordinator.isLayoutEditing()) return;
    const deltaX = event.offsetX - this.resizeX;
    const deltaY = event.offsetY - this.resizeY;
    this.resizeX = event.offsetX;
    this.resizeY = event.offsetY;
    await battleOverlayCoordinator.resizeHudElementBy(this.hudElement(), deltaX, deltaY);
  }

  @Builder
  private button(label: string, enabled: boolean, action: () => void, prominent: boolean = false) {
    Button(label).width('100%').height('100%').fontSize(10)
      .fontColor(prominent && enabled ? HUD_BACKGROUND : HUD_TEXT)
      .enabled(enabled).opacity(enabled ? 1 : 0.62)
      .backgroundColor(prominent && enabled ? HUD_SELECTED : Color.Transparent)
      .onClick(action)
  }

  @Builder
  private speed(snapshot: BattleOverlaySnapshot) {
    Column({ space: 2 }) {
      Text(`${snapshot.state.battleType === 'DOUBLE' ? '四只' : '两只'}顺序 · ${snapshot.state.speedLine.trickRoom ? '慢→快' : '快→慢'}`)
        .fontSize(10).fontWeight(FontWeight.Bold).fontColor(HUD_TEXT).width('100%')
      ForEach(snapshot.speedActions.slice(0, snapshot.state.battleType === 'DOUBLE' ? 4 : 2),
        (action: SpeedLineAction, index: number) => {
          Text(this.speedText(action, index)).fontSize(9).fontColor(HUD_TEXT).width('100%')
            .padding({ left: 3, right: 3, top: 1, bottom: 1 })
            .backgroundColor(HUD_SURFACE)
            .border({ width: 1, color: action.side === 'OWN' ? HUD_OWN : HUD_OPPONENT }).borderRadius(5)
        })
    }.width('100%').height('100%').padding(5)
  }

  @Builder
  private picker(snapshot: BattleOverlaySnapshot) {
    Stack({ alignContent: Alignment.Start }) {
      Select(options(this.pickerNames(snapshot))).selected(this.pickerSlot(snapshot))
        .width('100%').height('100%').fontColor(HUD_TEXT)
        .selectedOptionFontColor(HUD_TEXT).optionFontColor(HUD_TEXT)
        .backgroundColor(this.pickerSlot(snapshot) ===
          (this.pickerSide() === 'OWN' ? snapshot.state.ownSlot : snapshot.state.opponentSlot) ?
          HUD_SELECTED_SURFACE : HUD_BACKGROUND)
        .onSelect((index: number) => this.selectPicker(snapshot, index))
      Text(this.pickerNames(snapshot)[this.pickerSlot(snapshot)] ?? '未确认')
        .fontSize(10).fontColor(HUD_TEXT).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .padding({ left: 8, right: 34 }).hitTestBehavior(HitTestMode.None)
    }.width('100%').height('100%')
  }

  @Builder
  private typeIcon(type: string) {
    Text(TYPE_GLYPHS.get(type) ?? type.slice(0, 1)).width(14).height(14).fontSize(8)
      .fontWeight(FontWeight.Bold).fontColor(Color.White).textAlign(TextAlign.Center)
      .backgroundColor(TYPE_COLORS.get(type) ?? HUD_BORDER).border({ width: 1, color: '#96FFFFFF' }).borderRadius(7)
  }

  @Builder
  private typeMatchupGroup(multiplier: TypeMultiplier, types: string[]) {
    Row({ space: 1 }) {
      Text(multiplier.label).width(15).height('100%').fontSize(9).fontWeight(FontWeight.Bold)
        .fontColor(HUD_TEXT).textAlign(TextAlign.Center)
      ForEach(types, (type: string) => { this.typeIcon(type) }, (type: string) => type)
    }.height('100%').padding({ left: 1, right: 1 }).alignItems(VerticalAlign.Center)
  }

  @Builder
  private typeMatchupRow(category: string, groups: Record<string, string[]>, multipliers: TypeMultiplier[],
    fill: string, border: string) {
    Row() {
      Text(category).width(20).height('100%').fontSize(10).fontWeight(FontWeight.Bold)
        .fontColor(HUD_TEXT).textAlign(TextAlign.Center)
      Row() {
        ForEach(multipliers, (multiplier: TypeMultiplier) => {
          this.typeMatchupGroup(multiplier, groups[multiplier.key] ?? [])
        }, (multiplier: TypeMultiplier) => multiplier.key)
      }.height('100%').justifyContent(FlexAlign.End).alignItems(VerticalAlign.Center)
    }.height('100%').padding({ left: 2, right: 2 })
      .backgroundColor(fill).border({ width: 1, color: border }).borderRadius(5)
  }

  @Builder
  private typeMatchups(snapshot: BattleOverlaySnapshot) {
    Column() {
      ForEach(snapshot.typeMatchups.slice(0, 6), (matchup: BattleTypeMatchup, index: number) => {
        Column() {
          Row() {
            this.typeMatchupRow('抗', matchup.groups, RESIST_MULTIPLIERS, RESIST_SURFACE, RESIST_BORDER)
          }.layoutWeight(48)
          Blank().layoutWeight(4)
          Row() {
            this.typeMatchupRow('弱', matchup.groups, WEAK_MULTIPLIERS, WEAK_SURFACE, WEAK_BORDER)
          }.layoutWeight(48)
        }.layoutWeight(9).width('100%').alignItems(HorizontalAlign.End)
          .accessibilityDescription(`${matchup.speciesName}属性相性`)
        if (index < Math.min(snapshot.typeMatchups.length, 6) - 1) {
          Blank().layoutWeight(2.6)
        }
      }, (matchup: BattleTypeMatchup, index: number) => `${index}-${matchup.speciesName}`)
    }.width('100%').height('100%').alignItems(HorizontalAlign.End)
  }

  @Builder
  private damage(snapshot: BattleOverlaySnapshot) {
    Row({ space: 2 }) {
      ForEach((this.hudDamage.length === 4 ? this.hudDamage : ['1 —', '2 —', '3 —', '4 —']),
        (value: string, index: number) => {
          Text(value).layoutWeight(1).height('100%').fontSize(9).fontWeight(FontWeight.Bold)
            .fontColor(index === 0 ? HUD_SELECTED : HUD_TEXT).textAlign(TextAlign.Center)
            .backgroundColor(HUD_SURFACE).borderRadius(5)
        }, (value: string, index: number) => `${index}-${value}`)
    }.width('100%').height('100%').padding(3)
  }

  @Builder
  private elementContent(snapshot: BattleOverlaySnapshot) {
    if (this.element === 'EDIT') {
      this.button(battleOverlayCoordinator.isLayoutEditing() ? '确认' : '调整', true,
        () => { battleOverlayCoordinator.toggleLayoutEditing(); })
    } else if (this.element === 'REMATCH') {
      this.button('再战', true, () => { this.recognizePreview(); })
    } else if (this.element === 'TOGGLE') {
      this.button(battleDirectHudToggleLabel(snapshot.ready, snapshot.state.directHud.mode),
        snapshot.ready, () => { battleOverlayCoordinator.cycleHudMode(); }, true)
    } else if (this.element === 'RECORDING') {
      this.button(this.recordingLabel(), REPLAY_ENABLED, () => { this.toggleRecording(); })
    } else if (this.element === 'FORMAT') {
      this.button(!snapshot.ready ? '格式' : snapshot.state.battleType === 'DOUBLE' ? '双打' : '单打', snapshot.ready,
        () => { battleOverlayCoordinator.setBattleType(snapshot.state.battleType === 'DOUBLE' ? 'SINGLE' : 'DOUBLE'); })
    } else if (this.element === 'OWN_RECOGNITION') {
      this.button(this.ownRecognitionBusy ? '识别进行中' : ownTeamCaptureCoordinator.ownTeamRecognitionButtonLabel(),
        !this.ownRecognitionBusy, () => { this.recognizeOwnTeam(); })
    } else if (this.element === 'SPEED') {
      this.speed(snapshot)
    } else if (this.element === 'MATCHUP') {
      this.typeMatchups(snapshot)
    } else if (this.element === 'STATUS') {
      this.button(snapshot.ready ? battleStatusText(snapshot.state) : '请点击“再战”识别双方阵容', true,
        () => { if (snapshot.ready) battleOverlayCoordinator.showPanelSection('CONDITIONS'); else this.recognizePreview(); })
    } else if (this.element === 'ASSUMPTION') {
      Stack({ alignContent: Alignment.Start }) {
        Select(options(snapshot.profiles.map((profile: OpponentProfile) => profile.profileName)))
          .selected(this.selectedProfileIndex(snapshot)).width('100%').height('100%')
          .fontColor(HUD_TEXT).selectedOptionFontColor(HUD_TEXT).optionFontColor(HUD_TEXT)
          .backgroundColor(Color.Transparent).onSelect((index: number) => {
            const profile = snapshot.profiles[index];
            if (profile) battleOverlayCoordinator.setPreset(profile.profileId);
          })
        Text(snapshot.selectedProfile?.profileName ?? '默认')
          .fontSize(9).fontColor(HUD_TEXT).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .padding({ left: 5, right: 30 }).hitTestBehavior(HitTestMode.None)
      }.width('100%').height('100%')
    } else if (this.element === 'OPPONENT_LEFT' || this.element === 'OPPONENT_RIGHT' ||
      this.element === 'OWN_LEFT' || this.element === 'OWN_RIGHT') {
      this.picker(snapshot)
    } else if (this.element === 'DAMAGE') {
      this.damage(snapshot)
    } else if (this.element === 'DETAIL') {
      Column({ space: 1 }) {
        Button('详细').layoutWeight(1).width('100%').fontSize(9).fontColor(HUD_TEXT)
          .backgroundColor(Color.Transparent).onClick(() => battleOverlayCoordinator.showPanelSection('DAMAGE'))
        Button('结束').layoutWeight(1).width('100%').fontSize(9).fontColor('#FF8A80')
          .backgroundColor(Color.Transparent).onClick(() => this.confirmCloseSession())
      }.width('100%').height('100%')
    }
  }

  build() {
    Stack() {
      this.elementContent(this.current())
      if (battleOverlayCoordinator.isLayoutEditing() && this.element !== 'EDIT') {
        Text('↘').fontSize(13).fontColor(HUD_SELECTED).width(22).height(22)
          .textAlign(TextAlign.Center).align(Alignment.BottomEnd)
          .backgroundColor(HUD_BACKGROUND).border({ width: 1, color: HUD_SELECTED }).borderRadius(4)
          .gesture(PanGesture().onActionStart(() => { this.resizeX = 0; this.resizeY = 0; })
            .onActionUpdate((event: GestureEvent) => { this.resizeElement(event); }))
      }
      if (this.element === 'DAMAGE') {
        Web({ src: $rawfile('runtime/damage/engine-host.html'), controller: this.controller })
          .javaScriptAccess(true).onPageEnd(() => this.onEngineLoaded()).width(1).height(1).opacity(0)
      }
    }
    .id(this.elementId())
    .width('100%').height('100%').backgroundColor(HUD_BACKGROUND)
    .border({ width: battleOverlayCoordinator.isLayoutEditing() && this.element !== 'EDIT' ? 2 : 1,
      color: battleOverlayCoordinator.isLayoutEditing() && this.element !== 'EDIT' ? HUD_SELECTED : HUD_BORDER })
    .borderRadius(9)
    .gesture(PanGesture().onActionStart(() => { this.dragX = 0; this.dragY = 0; })
      .onActionUpdate((event: GestureEvent) => { this.moveElement(event); }))
  }
}
