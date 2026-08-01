import promptAction from '@ohos.promptAction';
import { webview } from '@kit.ArkWeb';
import { REPLAY_ENABLED } from 'BuildProfile';
import { DamageEngineRuntime } from '../domain/DamageEngineRuntime';
import {
  battleStatusText,
  parseBattleDirectDamageValues,
  SpeedLineAction
} from '../domain/BattleSession';
import { OpponentProfile } from '../domain/Models';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import {
  battleOverlayCoordinator,
  BattleHudElement,
  BattleOverlaySnapshot
} from '../services/BattleOverlayCoordinator';
import { replayRecordingCoordinator } from '../services/ReplayRecordingCoordinator';

const HUD_BACKGROUND: string = '#DE0E141B';
const HUD_SURFACE: string = '#E81C2731';
const HUD_SELECTED_SURFACE: string = '#EE2E3830';
const HUD_BORDER: string = '#566976';
const HUD_SELECTED: string = '#F4C542';
const HUD_OWN: string = '#48B2FF';
const HUD_OPPONENT: string = '#FF914C';
const HUD_TEXT: string = '#F4F8FB';
const HUD_MUTED: string = '#C0CCD6';

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
  @StorageLink('stage8VerificationStatus') verificationStatus: string = '';
  @StorageLink('battleHudDamageValues') verificationDamage: string = '';
  @State engineReady: boolean = false;
  @State calculating: boolean = false;
  @State hudDamage: string[] = ['1 —', '2 —', '3 —', '4 —'];
  @State dragX: number = 0;
  @State dragY: number = 0;
  @State resizeX: number = 0;
  @State resizeY: number = 0;

  private controller: webview.WebviewController = new webview.WebviewController('pc-battle-direct-damage');
  private engine?: DamageEngineRuntime;

  aboutToAppear(): void {
    this.overlayRevision;
  }

  private onOverlayRevisionChanged(): void {
    if (this.element === 'DAMAGE' && this.engineReady) this.calculateHud();
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
    }).catch((error: Error) => battleOverlayCoordinator.setMessage(`伤害引擎初始化失败：${String(error)}`));
  }

  private async calculateHud(): Promise<void> {
    if (!this.engineReady || !this.engine || this.calculating || !this.current().ready) return;
    this.calculating = true;
    try {
      const prepared = battleOverlayCoordinator.prepareDamage(true);
      const raw = await this.engine.calculateRaw(prepared.request);
      this.hudDamage = parseBattleDirectDamageValues(raw, prepared.configuredMoves);
      AppStorage.setOrCreate<string>('battleHudDamageValues', this.hudDamage.join(' | '));
    } catch (error) {
      this.hudDamage = ['1 ?', '2 ?', '3 ?', '4 ?'];
      battleOverlayCoordinator.setMessage(String(error));
    } finally {
      this.calculating = false;
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
      battleOverlayCoordinator.setMessage(String(error));
    } finally {
      if (!setupShown) await battleOverlayCoordinator.reveal();
    }
  }

  private async recognizeOwnTeam(): Promise<void> {
    try {
      await battleOverlayCoordinator.minimize();
      battleOverlayCoordinator.setMessage(await ownTeamCaptureCoordinator.recognizeOwnTeam());
    } catch (error) {
      battleOverlayCoordinator.setMessage(String(error));
    } finally {
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
      battleOverlayCoordinator.setMessage(String(error));
      if (replayRecordingCoordinator.canRetrySilently()) await this.offerSilentRecording();
    }
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
  private button(label: string, enabled: boolean, action: () => void) {
    Button(label).width('100%').height('100%').fontSize(10).fontColor(HUD_TEXT)
      .enabled(enabled).opacity(enabled ? 1 : 0.62).backgroundColor(Color.Transparent)
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
      this.button(!snapshot.ready ? '等待阵容' : snapshot.state.directHud.visible ? '隐藏 HUD' : '显示 HUD',
        snapshot.ready, () => { battleOverlayCoordinator.setHudVisible(!snapshot.state.directHud.visible); })
    } else if (this.element === 'RECORDING') {
      this.button(this.recordingLabel(), REPLAY_ENABLED, () => { this.toggleRecording(); })
    } else if (this.element === 'FORMAT') {
      this.button(!snapshot.ready ? '格式' : snapshot.state.battleType === 'DOUBLE' ? '双打' : '单打', snapshot.ready,
        () => { battleOverlayCoordinator.setBattleType(snapshot.state.battleType === 'DOUBLE' ? 'SINGLE' : 'DOUBLE'); })
    } else if (this.element === 'OWN_RECOGNITION') {
      this.button('识别我方', true, () => { this.recognizeOwnTeam(); })
    } else if (this.element === 'SPEED') {
      this.speed(snapshot)
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
      this.button('详细', true, () => { battleOverlayCoordinator.showPanelSection('DAMAGE'); })
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
      if (this.verificationStatus.length > 0) {
        Text(this.verificationStatus).id('stage8-verification-proxy').width(1).height(1).opacity(0)
        Text(this.verificationDamage).id('stage8-hud-damage-proxy').width(1).height(1).opacity(0)
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
