import { webview } from '@kit.ArkWeb';
import { DamageEngineRuntime } from '../domain/DamageEngineRuntime';
import {
  battleCondition,
  BattleSide,
  SpeedLineAction,
  speedModifiers
} from '../domain/BattleSession';
import { EntityRef, MoveValue, OpponentProfile } from '../domain/Models';
import { TeamPreviewCandidate, TeamPreviewReviewSlot } from '../domain/TeamPreviewRecognition';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import {
  battleOverlayCoordinator,
  BattleSetupSnapshot,
  BattleSetupTeamOption,
  BattleOverlaySection,
  BattleOverlaySnapshot
} from '../services/BattleOverlayCoordinator';
import {
  AsyncRequestGate,
  CalculationDisplayResult,
  CalculationMoveResult,
  damageRequestCacheKey,
  parseCalculationResult,
  StringLruCache
} from '../ui/AppUiModels';
import { safeUiError } from '../ui/PrivacySafeError';

const ACCENT: string = '#F4C542';
const ON_ACCENT: string = '#281E00';
const TEXT: string = '#F4F8FB';
const MUTED: string = '#C0CCD6';
const PANEL: string = '#F20E141B';
const SECONDARY: string = '#1C2731';
const BORDER: string = '#566976';
const DANGER: string = '#FF8A80';
const DANGER_SURFACE: string = '#3B2229';
const SUCCESS_SURFACE: string = '#17352B';
const OPPONENT_SURFACE: string = '#3A2A20';

function option(value: string): SelectOption {
  return { value };
}

function options(values: string[]): SelectOption[] {
  return values.map((value: string): SelectOption => option(value));
}

function name(value: EntityRef | undefined, empty: string = '未设置'): string {
  return value?.displayName ?? value?.showdownId ?? empty;
}

@Entry
@Component
struct BattleOverlay {
  @State revision: number = 0;
  @State engineReady: boolean = false;
  @State calculating: boolean = false;
  @State message: string = '';
  @State result: CalculationDisplayResult | undefined = undefined;
  @State dragX: number = 0;
  @State dragY: number = 0;
  @State abilityQuery: string = '';
  @State itemQuery: string = '';
  @State setupSearchSlot: number = -1;
  @State setupQuery: string = '';

  private controller: webview.WebviewController = new webview.WebviewController('pc-battle-overlay-damage');
  private engine?: DamageEngineRuntime;
  private calculationGate: AsyncRequestGate = new AsyncRequestGate();
  private damageCache: StringLruCache = new StringLruCache(24);

  aboutToAppear(): void {
    this.calculationGate.activate();
    this.revision += 1;
  }

  aboutToDisappear(): void {
    this.calculationGate.dispose();
  }

  private current(): BattleOverlaySnapshot {
    this.revision;
    return battleOverlayCoordinator.snapshot();
  }

  private refresh(_recalculate: boolean = true): void {
    this.calculationGate.supersede();
    this.calculating = false;
    this.revision += 1;
    this.result = undefined;
  }

  private setSection(section: BattleOverlaySection): void {
    battleOverlayCoordinator.setSection(section);
    this.refresh(false);
  }

  private onEngineLoaded(): void {
    this.engine = new DamageEngineRuntime(this.controller);
    this.engine.getEngineInfo().then(() => {
      this.engineReady = true;
    }).catch((error: Error) => {
      this.message = safeUiError(error, '伤害引擎初始化失败');
    });
  }

  private async calculatePanel(): Promise<void> {
    if (!this.engineReady || !this.engine || this.calculating) return;
    this.calculating = true;
    this.result = undefined;
    const generation = this.calculationGate.begin();
    let fingerprint: string = '';
    const slowTimer = setTimeout(() => {
      if (this.calculationGate.isCurrent(generation)) this.message = '计算时间较长，仍在本机处理中…';
    }, 30000);
    let timeoutTimer: number = 0;
    try {
      const prepared = battleOverlayCoordinator.prepareDamage(false);
      fingerprint = damageRequestCacheKey(prepared.request);
      let body = this.damageCache.get(fingerprint);
      if (body === undefined) {
        body = await Promise.race<string>([
          this.engine.calculateRaw(prepared.request),
          new Promise<string>((_resolve, reject) => {
            timeoutTimer = setTimeout(() => reject(new Error('本地伤害计算超过 90 秒，已停止等待')), 90000);
          })
        ]);
        this.damageCache.set(fingerprint, body);
      }
      if (!this.calculationGate.isCurrent(generation)) return;
      if (damageRequestCacheKey(battleOverlayCoordinator.prepareDamage(false).request) !== fingerprint) {
        this.message = '计算期间对局输入已变化，旧结果已丢弃。';
        return;
      }
      this.result = parseCalculationResult(body);
      this.message = '';
    } catch (error) {
      if (this.calculationGate.isCurrent(generation)) this.message = safeUiError(error, '伤害计算失败');
    } finally {
      clearTimeout(slowTimer);
      if (timeoutTimer > 0) clearTimeout(timeoutTimer);
      if (this.calculationGate.isCurrent(generation)) this.calculating = false;
    }
  }

  private async recognizePreview(): Promise<void> {
    let setupShown: boolean = false;
    try {
      await battleOverlayCoordinator.minimize();
      this.message = await ownTeamCaptureCoordinator.recognizeTeamPreview();
      await ownTeamCaptureCoordinator.minimizeFloatWindow();
      await battleOverlayCoordinator.showSetup();
      setupShown = true;
    } catch (error) {
      this.message = safeUiError(error, '识别双方阵容失败');
    } finally {
      if (!setupShown) await battleOverlayCoordinator.reveal();
      this.refresh(false);
    }
  }

  private async recognizeOwnTeam(): Promise<void> {
    try {
      await battleOverlayCoordinator.minimize();
      this.message = await ownTeamCaptureCoordinator.recognizeOwnTeam();
    } catch (error) {
      this.message = safeUiError(error, '识别我的队伍失败');
    } finally {
      await battleOverlayCoordinator.reveal();
      this.refresh(false);
    }
  }

  private selectedProfileIndex(snapshot: BattleOverlaySnapshot): number {
    const index = snapshot.profiles.findIndex((profile: OpponentProfile) =>
      profile.profileId === snapshot.selectedProfile?.profileId);
    return Math.max(0, index);
  }

  private selectedMoveIndex(snapshot: BattleOverlaySnapshot): number {
    const index = snapshot.moves.findIndex((move: MoveValue) =>
      move.entity.showdownId === snapshot.state.selectedMoveId);
    return Math.max(0, index);
  }

  private stageValue(snapshot: BattleOverlaySnapshot, side: BattleSide, key: string): number {
    const stages = battleCondition(snapshot.state, side).stages;
    if (key === 'atk') return stages.atk;
    if (key === 'def') return stages.def;
    if (key === 'spa') return stages.spa;
    if (key === 'spd') return stages.spd;
    return stages.spe;
  }

  private stageText(snapshot: BattleOverlaySnapshot, side: BattleSide, key: string): string {
    const value = this.stageValue(snapshot, side, key);
    return `${value >= 0 ? '+' : ''}${value}`;
  }

  private resultTitle(): string {
    const value = this.result;
    return value ? `${value.attacker} → ${value.defender}` : '';
  }

  private resultMoves(): CalculationMoveResult[] {
    return this.result?.moves ?? [];
  }

  private speedActionText(action: SpeedLineAction, index: number): string {
    const side = action.side === 'OWN' ? '我·' : '对·';
    const move = action.moveName ? `·${action.moveName}` : '';
    const speed = action.minimumSpeed === action.maximumSpeed ? String(action.minimumSpeed) :
      `${action.minimumSpeed}–${action.maximumSpeed}`;
    return `${index + 1}. ${side}${action.pokemonName}${move}  ${speed}`;
  }

  private profileSummary(snapshot: BattleOverlaySnapshot): string {
    const profile = snapshot.selectedProfile;
    if (!profile) return '';
    return `等级 ${profile.level ?? 50} · 特性 ${name(profile.ability)} · 道具 ${name(profile.item, '无道具')}`;
  }

  private profileSource(snapshot: BattleOverlaySnapshot): string {
    const profile = snapshot.selectedProfile;
    return profile ? `配置来源：${profile.source}。现场选择按对手槽位独立保存；切换宝可梦会恢复该槽位上次选择。` : '';
  }

  private selectedSetupTeamIndex(snapshot: BattleSetupSnapshot): number {
    return Math.max(0, snapshot.teams.findIndex((team: BattleSetupTeamOption) =>
      team.teamId === snapshot.selectedTeamId));
  }

  private selectedSetupTeamLabel(snapshot: BattleSetupSnapshot): string {
    return snapshot.teams.find((team: BattleSetupTeamOption) => team.teamId === snapshot.selectedTeamId)?.label ??
      '请选择我方队伍';
  }

  private selectedSetupCandidateIndex(slot: TeamPreviewReviewSlot): number {
    return Math.max(0, slot.candidates.findIndex((candidate: TeamPreviewCandidate) =>
      candidate.canonicalId === slot.selected?.canonicalId));
  }

  private selectedSetupCandidateLabel(slot: TeamPreviewReviewSlot): string {
    const candidate = slot.candidates[this.selectedSetupCandidateIndex(slot)];
    return candidate ? `${candidate.displayName} · 匹配度 ${Math.round(candidate.confidence * 100)}%` :
      name(slot.selected, '未确认');
  }

  private async leaveSetup(): Promise<void> {
    if (ownTeamCaptureCoordinator.mode() === 'hud') {
      await battleOverlayCoordinator.show('hud');
    } else {
      await battleOverlayCoordinator.close();
      await ownTeamCaptureCoordinator.showFloatWindow();
    }
  }

  private async confirmSetup(): Promise<void> {
    try {
      battleOverlayCoordinator.confirmSetup();
      this.message = '本局阵容已确认';
      await this.leaveSetup();
    } catch (error) {
      this.message = safeUiError(error, '确认本局阵容失败');
    }
  }

  private opponentPoint(snapshot: BattleOverlaySnapshot, key: string): number {
    const points = snapshot.selectedProfile?.statPoints;
    if (key === 'hp') return points?.hp ?? 0;
    if (key === 'atk') return points?.atk ?? 0;
    if (key === 'def') return points?.def ?? 0;
    if (key === 'spa') return points?.spa ?? 0;
    if (key === 'spd') return points?.spd ?? 0;
    return points?.spe ?? 0;
  }

  private opponentFormIndex(snapshot: BattleOverlaySnapshot): number {
    const index = snapshot.opponentForms.findIndex((entry: EntityRef) =>
      entry.canonicalId === snapshot.opponent?.canonicalId);
    return Math.max(0, index);
  }

  private async nudge(x: number, y: number): Promise<void> {
    await battleOverlayCoordinator.moveBy(x, y);
  }

  private async resize(width: number, height: number): Promise<void> {
    await battleOverlayCoordinator.resizeBy(width, height);
  }

  @Builder
  private titleBar(snapshot: BattleOverlaySnapshot) {
    Column({ space: 6 }) {
      Row({ space: 6 }) {
        Text(snapshot.mode === 'hud' ? '对战 HUD' : '对局伤害面板')
          .fontSize(20).fontWeight(FontWeight.Bold).fontColor(TEXT).layoutWeight(1)
        Button('←').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.nudge(-32, 0))
        Button('↑').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.nudge(0, -32))
        Button('↓').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.nudge(0, 32))
        Button('→').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.nudge(32, 0))
        Button('-').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.resize(-80, -80))
        Button('+').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.resize(80, 80))
        Button('收起').id('battle-overlay-collapse').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT)
          .onClick(() => battleOverlayCoordinator.collapsePanel())
        Button('关闭').fontSize(12).backgroundColor(DANGER_SURFACE).fontColor(DANGER)
          .onClick(() => battleOverlayCoordinator.close())
      }
      .width('100%')
      .gesture(PanGesture().onActionStart(() => { this.dragX = 0; this.dragY = 0; })
        .onActionUpdate((event: GestureEvent) => {
          const deltaX = Math.round(event.offsetX - this.dragX);
          const deltaY = Math.round(event.offsetY - this.dragY);
          this.dragX = event.offsetX; this.dragY = event.offsetY;
          battleOverlayCoordinator.moveBy(deltaX, deltaY);
        })
        .onActionEnd(() => battleOverlayCoordinator.snapPanelToEdge()))
      Text('拖动标题栏移动；方向键与缩放键也会保存当前横屏或竖屏布局。')
        .fontSize(11).fontColor(MUTED).width('100%')
    }.width('100%')
  }

  @Builder
  private sideSlots(snapshot: BattleOverlaySnapshot, side: BattleSide, title: string) {
    Column({ space: 5 }) {
      Text(title).fontSize(13).fontColor(MUTED).width('100%')
      Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
        ForEach(side === 'OWN' ? snapshot.ownNames : snapshot.opponentNames,
          (entry: string, index: number) => {
            Button(`${index + 1}. ${entry}`).fontSize(12).margin({ right: 5, bottom: 5 })
              .backgroundColor((side === 'OWN' ? snapshot.state.ownSlot : snapshot.state.opponentSlot) === index ?
                ACCENT : SECONDARY)
              .fontColor((side === 'OWN' ? snapshot.state.ownSlot : snapshot.state.opponentSlot) === index ?
                ON_ACCENT : TEXT)
              .onClick(() => {
                if (side === 'OWN') battleOverlayCoordinator.setOwnSlot(index);
                else battleOverlayCoordinator.setOpponentSlot(index);
                this.refresh();
              })
          }, (entry: string, index: number) => `${side}-${index}-${entry}`)
      }.width('100%')
    }.width('100%')
  }

  @Builder
  private flagButton(label: string, active: boolean, key: string, enabled: boolean = true) {
    Button(`${active ? '✓ ' : ''}${label}`).fontSize(12).margin({ right: 5, bottom: 5 })
      .enabled(enabled).backgroundColor(active ? SUCCESS_SURFACE : SECONDARY)
      .fontColor(active ? ACCENT : TEXT).onClick(() => {
        battleOverlayCoordinator.setFlag(key, !active); this.refresh();
      })
  }

  @Builder
  private stageEditor(snapshot: BattleOverlaySnapshot, side: BattleSide) {
    Column({ space: 6 }) {
      Text(side === 'OWN' ? '我方当前宝可梦' : '对手当前宝可梦')
        .fontSize(14).fontWeight(FontWeight.Bold).width('100%')
      this.flagButton('灼伤', battleCondition(snapshot.state, side).burned,
        side === 'OWN' ? 'ownBurned' : 'opponentBurned')
      ForEach(['atk', 'def', 'spa', 'spd', 'spe'], (key: string) => {
        Row({ space: 5 }) {
          Text(key === 'atk' ? '攻击' : key === 'def' ? '防御' : key === 'spa' ? '特攻' :
            key === 'spd' ? '特防' : '速度').width(62).fontSize(12)
          Button('−').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
            battleOverlayCoordinator.setStage(side, key, this.stageValue(snapshot, side, key) - 1); this.refresh();
          })
          Text(this.stageText(snapshot, side, key))
            .width(42).textAlign(TextAlign.Center).fontSize(12)
          Button('+').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
            battleOverlayCoordinator.setStage(side, key, this.stageValue(snapshot, side, key) + 1); this.refresh();
          })
        }.width('100%')
      }, (key: string) => `${side}-${key}`)
    }.layoutWeight(1).padding(10).backgroundColor(SECONDARY).borderRadius(10)
  }

  @Builder
  private damageSection(snapshot: BattleOverlaySnapshot) {
    Column({ space: 8 }) {
      Row({ space: 6 }) {
        Button('我方输出').layoutWeight(1)
          .backgroundColor(snapshot.state.direction === 'OWN_TO_OPPONENT' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.direction === 'OWN_TO_OPPONENT' ? ON_ACCENT : TEXT)
          .onClick(() => { battleOverlayCoordinator.setDirection('OWN_TO_OPPONENT'); this.refresh(false); })
        Button('我方承伤').layoutWeight(1)
          .backgroundColor(snapshot.state.direction === 'OPPONENT_TO_OWN' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.direction === 'OPPONENT_TO_OWN' ? ON_ACCENT : TEXT)
          .onClick(() => { battleOverlayCoordinator.setDirection('OPPONENT_TO_OWN'); this.refresh(false); })
      }.width('100%')
      this.sideSlots(snapshot, 'OWN', '选择我方宝可梦')
      this.sideSlots(snapshot, 'OPPONENT', '选择对手宝可梦')
      Text('对手配置').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(snapshot.profiles.map((profile: OpponentProfile) => profile.profileName)))
        .selected(this.selectedProfileIndex(snapshot)).width('100%').fontColor(TEXT)
        .selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY)
        .onSelect((index: number) => {
          const profile = snapshot.profiles[index];
          if (profile) { battleOverlayCoordinator.setPreset(profile.profileId); this.refresh(); }
        })
      Text('招式').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(snapshot.moves.map((move: MoveValue) => name(move.entity))))
        .selected(this.selectedMoveIndex(snapshot)).width('100%').fontColor(TEXT)
        .selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY)
        .onSelect((index: number) => {
          const move = snapshot.moves[index];
          if (move) { battleOverlayCoordinator.setMove(move.entity.showdownId); this.refresh(false); }
        })
      Button(this.calculating ? '正在计算…' : this.engineReady ? '计算伤害' : '正在准备伤害引擎…')
        .id('battle-overlay-calculate').width('100%').enabled(this.engineReady && !this.calculating)
        .backgroundColor(ACCENT).onClick(() => this.calculatePanel())
      if (this.message.length > 0) Text(this.message).fontSize(12).fontColor(DANGER).width('100%')
      if (this.result) {
        Column({ space: 5 }) {
          Text(this.resultTitle()).fontWeight(FontWeight.Bold).width('100%')
          ForEach(this.resultMoves(), (move: CalculationMoveResult) => {
            Text(`${move.name}：${move.minPercent.toFixed(1)}–${move.maxPercent.toFixed(1)}% · ${move.koText}`)
              .fontSize(13).width('100%')
          })
        }.width('100%').padding(10).backgroundColor(SUCCESS_SURFACE).borderRadius(10)
      }
    }.width('100%')
  }

  @Builder
  private conditionsSection(snapshot: BattleOverlaySnapshot) {
    Column({ space: 9 }) {
      Text('对战格式').fontSize(13).fontColor(MUTED).width('100%')
      Row({ space: 6 }) {
        Button('单打').layoutWeight(1).backgroundColor(snapshot.state.battleType === 'SINGLE' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.battleType === 'SINGLE' ? ON_ACCENT : TEXT)
          .onClick(() => { battleOverlayCoordinator.setBattleType('SINGLE'); this.refresh(); })
        Button('双打').layoutWeight(1).backgroundColor(snapshot.state.battleType === 'DOUBLE' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.battleType === 'DOUBLE' ? ON_ACCENT : TEXT)
          .onClick(() => { battleOverlayCoordinator.setBattleType('DOUBLE'); this.refresh(); })
      }.width('100%')
      Text('天气').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(['无天气', '晴天', '雨天', '沙暴', '雪天']))
        .selected(['NONE', 'SUN', 'RAIN', 'SAND', 'SNOW'].indexOf(snapshot.state.weather)).width('100%')
        .fontColor(TEXT).selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY)
        .onSelect((index: number) => { battleOverlayCoordinator.setEnum('weather',
          ['NONE', 'SUN', 'RAIN', 'SAND', 'SNOW'][index]); this.refresh(); })
      Text('场地').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(['无场地', '电气场地', '青草场地', '精神场地', '薄雾场地']))
        .selected(['NONE', 'ELECTRIC', 'GRASSY', 'PSYCHIC', 'MISTY'].indexOf(snapshot.state.terrain)).width('100%')
        .fontColor(TEXT).selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY)
        .onSelect((index: number) => { battleOverlayCoordinator.setEnum('terrain',
          ['NONE', 'ELECTRIC', 'GRASSY', 'PSYCHIC', 'MISTY'][index]); this.refresh(); })
      Text('我方场地状态').fontSize(13).fontColor(MUTED).width('100%')
      Flex({ wrap: FlexWrap.Wrap }) {
        this.flagButton('反射壁', snapshot.state.ownReflect, 'ownReflect')
        this.flagButton('光墙', snapshot.state.ownLightScreen, 'ownLightScreen')
        this.flagButton('极光幕', snapshot.state.ownAuroraVeil, 'ownAuroraVeil')
        this.flagButton('守住', snapshot.state.ownProtected, 'ownProtected')
      }.width('100%')
      Text('对手场地状态').fontSize(13).fontColor(MUTED).width('100%')
      Flex({ wrap: FlexWrap.Wrap }) {
        this.flagButton('反射壁', snapshot.state.opponentReflect, 'opponentReflect')
        this.flagButton('光墙', snapshot.state.opponentLightScreen, 'opponentLightScreen')
        this.flagButton('极光幕', snapshot.state.opponentAuroraVeil, 'opponentAuroraVeil')
        this.flagButton('守住', snapshot.state.opponentProtected, 'opponentProtected')
      }.width('100%')
      Flex({ wrap: FlexWrap.Wrap }) {
        this.flagButton('帮助', snapshot.state.helpingHand, 'helpingHand', snapshot.state.battleType === 'DOUBLE')
        this.flagButton('必定暴击', snapshot.state.critical, 'critical')
        this.flagButton('范围招式', snapshot.state.spread, 'spread', snapshot.state.battleType === 'DOUBLE')
      }.width('100%')
      Row({ space: 8 }) {
        this.stageEditor(snapshot, 'OWN')
        this.stageEditor(snapshot, 'OPPONENT')
      }.width('100%').alignItems(VerticalAlign.Top)
    }.width('100%')
  }

  @Builder
  private speedModifierEditor(snapshot: BattleOverlaySnapshot, side: BattleSide) {
    Column({ space: 6 }) {
      Text(side === 'OWN' ? '我方当前速度' : '对手当前速度').fontWeight(FontWeight.Bold).fontSize(13)
      Row({ space: 5 }) {
        Button('−').backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
          battleOverlayCoordinator.setSpeedModifier(side, 'stage', speedModifiers(snapshot.state, side).stage - 1);
          this.refresh();
        })
        Text(`等级 ${speedModifiers(snapshot.state, side).stage >= 0 ? '+' : ''}${speedModifiers(snapshot.state, side).stage}`)
          .fontSize(12).layoutWeight(1).textAlign(TextAlign.Center)
        Button('+').backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
          battleOverlayCoordinator.setSpeedModifier(side, 'stage', speedModifiers(snapshot.state, side).stage + 1);
          this.refresh();
        })
      }.width('100%')
      Flex({ wrap: FlexWrap.Wrap }) {
        Button(`${speedModifiers(snapshot.state, side).paralyzed ? '✓ ' : ''}麻痹`).fontSize(11)
          .backgroundColor(SECONDARY).fontColor(TEXT).margin({ right: 4, bottom: 4 }).onClick(() => {
            battleOverlayCoordinator.setSpeedModifier(side, 'paralyzed', !speedModifiers(snapshot.state, side).paralyzed);
            this.refresh();
          })
        Button(`${speedModifiers(snapshot.state, side).doubled ? '✓ ' : ''}速度×2`).fontSize(11)
          .backgroundColor(SECONDARY).fontColor(TEXT).margin({ right: 4, bottom: 4 }).onClick(() => {
            battleOverlayCoordinator.setSpeedModifier(side, 'doubled', !speedModifiers(snapshot.state, side).doubled);
            this.refresh();
          })
        Button(`${speedModifiers(snapshot.state, side).choiceScarf ? '✓ ' : ''}讲究围巾`).fontSize(11)
          .backgroundColor(SECONDARY).fontColor(TEXT).margin({ right: 4, bottom: 4 }).onClick(() => {
            battleOverlayCoordinator.setSpeedModifier(side, 'choiceScarf', !speedModifiers(snapshot.state, side).choiceScarf);
            this.refresh();
          })
      }.width('100%')
    }.layoutWeight(1).padding(9).backgroundColor(SECONDARY).borderRadius(10)
  }

  @Builder
  private speedSection(snapshot: BattleOverlaySnapshot) {
    Column({ space: 8 }) {
      Flex({ wrap: FlexWrap.Wrap }) {
        this.flagButton('我方顺风', snapshot.state.speedLine.ownTailwind, 'ownTailwind')
        this.flagButton('对方顺风', snapshot.state.speedLine.opponentTailwind, 'opponentTailwind')
        this.flagButton('戏法空间', snapshot.state.speedLine.trickRoom, 'trickRoom')
      }.width('100%')
      Text(snapshot.state.speedLine.trickRoom ? '戏法空间：同一先制度内慢者优先；先制度本身不反转。' :
        '普通空间：同一先制度内快者优先。').fontSize(12).fontColor(MUTED).width('100%')
      Row({ space: 8 }) {
        this.speedModifierEditor(snapshot, 'OWN')
        this.speedModifierEditor(snapshot, 'OPPONENT')
      }.width('100%').alignItems(VerticalAlign.Top)
      Column({ space: 5 }) {
        ForEach(snapshot.speedActions, (action: SpeedLineAction, index: number) => {
          Text(this.speedActionText(action, index))
            .fontSize(13).width('100%').padding(6)
            .backgroundColor(action.side === 'OWN' ? SUCCESS_SURFACE : OPPONENT_SURFACE)
            .borderRadius(6)
        })
      }.width('100%')
    }.width('100%')
  }

  @Builder
  private opponentSection(snapshot: BattleOverlaySnapshot) {
    Column({ space: 8 }) {
      this.sideSlots(snapshot, 'OPPONENT', '选择要调整的对手')
      if (snapshot.opponentForms.length > 1) {
        Text('现场形态').fontSize(13).fontColor(MUTED).width('100%')
        Select(options(snapshot.opponentForms.map((entry: EntityRef) => name(entry))))
          .selected(this.opponentFormIndex(snapshot)).width('100%').fontColor(TEXT)
          .selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY).onSelect((index: number) => {
            const form = snapshot.opponentForms[index];
            if (form) { battleOverlayCoordinator.setOpponentForm(form); this.refresh(); }
          })
      }
      Text('选择对手配置').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(snapshot.profiles.map((profile: OpponentProfile) => profile.profileName)))
        .selected(this.selectedProfileIndex(snapshot)).width('100%').fontColor(TEXT)
        .selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY)
        .onSelect((index: number) => {
          const profile = snapshot.profiles[index];
          if (profile) { battleOverlayCoordinator.setPreset(profile.profileId); this.refresh(); }
        })
      if (snapshot.selectedProfile) {
        Text(this.profileSummary(snapshot))
          .fontSize(13).width('100%')
        Text(this.profileSource(snapshot))
          .fontSize(12).fontColor(MUTED).width('100%')
      }
      Text('现场加点（每项 0–32）').fontSize(13).fontColor(MUTED).width('100%')
      ForEach(['hp', 'atk', 'def', 'spa', 'spd', 'spe'], (key: string) => {
        Row({ space: 5 }) {
          Text(key === 'hp' ? '生命' : key === 'atk' ? '攻击' : key === 'def' ? '防御' :
            key === 'spa' ? '特攻' : key === 'spd' ? '特防' : '速度').width(58).fontSize(12)
          Button('−').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
            battleOverlayCoordinator.setOpponentPoint(key, this.opponentPoint(snapshot, key) - 1); this.refresh();
          })
          Text(String(this.opponentPoint(snapshot, key))).width(38).textAlign(TextAlign.Center).fontSize(12)
          Button('+').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
            battleOverlayCoordinator.setOpponentPoint(key, this.opponentPoint(snapshot, key) + 1); this.refresh();
          })
        }.width('100%')
      }, (key: string) => `opponent-point-${key}`)
      Text('现场特性').fontSize(13).fontColor(MUTED).width('100%')
      TextInput({ text: this.abilityQuery, placeholder: '搜索中文或英文特性' }).width('100%')
        .fontColor(TEXT).placeholderColor(MUTED).backgroundColor(SECONDARY)
        .onFocus(() => battleOverlayCoordinator.setPanelInputActive(true))
        .onBlur(() => battleOverlayCoordinator.setPanelInputActive(false))
        .onChange((value: string) => { this.abilityQuery = value; })
      Flex({ wrap: FlexWrap.Wrap }) {
        Button('继承配置').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
          .margin({ right: 4, bottom: 4 }).onClick(() => {
            battleOverlayCoordinator.setOpponentAbility(undefined); this.refresh();
          })
        ForEach(battleOverlayCoordinator.searchEntities('ability', this.abilityQuery), (entry: EntityRef) => {
          Button(name(entry)).fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
            .margin({ right: 4, bottom: 4 }).onClick(() => {
              battleOverlayCoordinator.setOpponentAbility(entry); this.refresh();
            })
        }, (entry: EntityRef) => entry.canonicalId)
      }.width('100%')
      Text('现场道具').fontSize(13).fontColor(MUTED).width('100%')
      TextInput({ text: this.itemQuery, placeholder: '搜索中文或英文道具' }).width('100%')
        .fontColor(TEXT).placeholderColor(MUTED).backgroundColor(SECONDARY)
        .onFocus(() => battleOverlayCoordinator.setPanelInputActive(true))
        .onBlur(() => battleOverlayCoordinator.setPanelInputActive(false))
        .onChange((value: string) => { this.itemQuery = value; })
      Flex({ wrap: FlexWrap.Wrap }) {
        Button('继承配置').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
          .margin({ right: 4, bottom: 4 }).onClick(() => {
            battleOverlayCoordinator.setOpponentItem(undefined, false); this.refresh();
          })
        Button('明确无道具').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
          .margin({ right: 4, bottom: 4 }).onClick(() => {
            battleOverlayCoordinator.setOpponentItem(undefined, true); this.refresh();
          })
        ForEach(battleOverlayCoordinator.searchEntities('item', this.itemQuery), (entry: EntityRef) => {
          Button(name(entry)).fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
            .margin({ right: 4, bottom: 4 }).onClick(() => {
              battleOverlayCoordinator.setOpponentItem(entry, true); this.refresh();
            })
        }, (entry: EntityRef) => entry.canonicalId)
      }.width('100%')
      Button('返回主应用编辑或新建完整对手配置').width('100%').backgroundColor(SECONDARY).fontColor(TEXT)
        .onClick(() => ownTeamCaptureCoordinator.openMainApp())
    }.width('100%')
  }

  @Builder
  private setupPanel(snapshot: BattleSetupSnapshot) {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text(this.setupSearchSlot >= 0 ? `更正对手第 ${this.setupSearchSlot + 1} 只宝可梦` : '核对双方阵容')
          .fontSize(20).fontWeight(FontWeight.Bold).fontColor(TEXT).layoutWeight(1)
        Button('关闭').fontSize(12).backgroundColor(DANGER_SURFACE).fontColor(DANGER)
          .onClick(() => this.leaveSetup())
      }.width('100%')
      Text(this.setupSearchSlot >= 0 ? '搜索中文名或英文名，选择后返回阵容核对。' : '确认后开始本局伤害计算')
        .fontSize(12).fontColor(MUTED).width('100%')
      if (this.setupSearchSlot >= 0) {
        TextInput({ text: this.setupQuery, placeholder: '搜索中文名、英文名或 Showdown ID' }).width('100%')
          .fontColor(TEXT).placeholderColor(MUTED).backgroundColor(SECONDARY)
          .onFocus(() => battleOverlayCoordinator.setPanelInputActive(true))
          .onBlur(() => battleOverlayCoordinator.setPanelInputActive(false))
          .onChange((value: string) => { this.setupQuery = value; })
        Scroll() {
          Column({ space: 6 }) {
            ForEach(battleOverlayCoordinator.searchEntities('species', this.setupQuery), (entry: EntityRef) => {
              Button(name(entry)).width('100%').backgroundColor(SECONDARY).fontColor(TEXT)
                .onClick(() => {
                  battleOverlayCoordinator.setSetupOpponent(this.setupSearchSlot, entry);
                  this.setupSearchSlot = -1;
                  this.setupQuery = '';
                  this.refresh(false);
                })
            }, (entry: EntityRef) => entry.canonicalId)
          }.width('100%')
        }.layoutWeight(1).width('100%')
        Button('返回阵容核对').width('100%').backgroundColor(SECONDARY).fontColor(TEXT)
          .onClick(() => { this.setupSearchSlot = -1; this.setupQuery = ''; })
      } else if (!snapshot.ready) {
        Text(snapshot.message || '没有可核对的双方阵容').fontColor(DANGER).width('100%')
      } else {
        Scroll() {
          Column({ space: 9 }) {
            Text('选择本局使用的我方队伍').fontSize(14).fontWeight(FontWeight.Bold).width('100%')
            Stack({ alignContent: Alignment.Start }) {
              Select(options(snapshot.teams.map((team: BattleSetupTeamOption) => team.label)))
                .selected(this.selectedSetupTeamIndex(snapshot)).width('100%').fontColor(TEXT)
                .selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY)
                .onSelect((index: number) => {
                  const team = snapshot.teams[index];
                  if (team) { battleOverlayCoordinator.setSetupTeam(team.teamId); this.refresh(false); }
                })
              Text(this.selectedSetupTeamLabel(snapshot)).fontSize(12).fontColor(TEXT).maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis }).padding({ left: 10, right: 38 })
                .hitTestBehavior(HitTestMode.None)
            }.width('100%')
            Text((snapshot.teams.find((team: BattleSetupTeamOption) => team.teamId === snapshot.selectedTeamId)?.matchCount ?? 0) === 6 ?
              '已与识别到的我方阵容完全匹配。' :
              `当前匹配 ${snapshot.teams.find((team: BattleSetupTeamOption) => team.teamId === snapshot.selectedTeamId)?.matchCount ?? 0}/6，请确认选择的是本局使用的队伍。`)
              .fontSize(12).fontColor(ACCENT).width('100%')
            Text('核对对手的 6 只宝可梦').fontSize(14).fontWeight(FontWeight.Bold).width('100%')
            Text('低置信度结果必须逐项点“确认当前识别”或主动更正；仅保持默认选中不会自动确认。')
              .fontSize(12).fontColor(MUTED).width('100%')
            if (snapshot.pendingOpponentConfirmations.length > 0) {
              Text(`仍有 ${snapshot.pendingOpponentConfirmations.length} 项低置信度结果待确认。`)
                .fontSize(12).fontWeight(FontWeight.Bold).fontColor('#FFB74D').width('100%')
            }
            ForEach(snapshot.opponents, (slot: TeamPreviewReviewSlot) => {
              Column({ space: 6 }) {
                Row({ space: 7 }) {
                  Text(String(slot.slotIndex + 1)).width(28).fontWeight(FontWeight.Bold).fontColor(TEXT)
                  Text(name(slot.selected, '未确认')).layoutWeight(1).fontWeight(FontWeight.Bold).fontColor(TEXT)
                  Button('更正').id(`battle-setup-correct-${slot.slotIndex}`).fontSize(11)
                    .backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
                      this.setupSearchSlot = slot.slotIndex;
                      this.setupQuery = '';
                    })
                }.width('100%')
                if (slot.candidates.length > 0) {
                  Stack({ alignContent: Alignment.Start }) {
                    Select(options(slot.candidates.map((candidate: TeamPreviewCandidate) =>
                      `${candidate.displayName} · 匹配度 ${Math.round(candidate.confidence * 100)}%`)))
                      .selected(this.selectedSetupCandidateIndex(slot)).width('100%').fontColor(TEXT)
                      .selectedOptionFontColor(TEXT).optionFontColor(TEXT).backgroundColor(SECONDARY)
                      .onSelect((index: number) => {
                        battleOverlayCoordinator.setSetupOpponentCandidate(slot.slotIndex, index);
                        this.refresh(false);
                      })
                    Text(this.selectedSetupCandidateLabel(slot)).fontSize(11).fontColor(TEXT).maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis }).padding({ left: 10, right: 38 })
                      .hitTestBehavior(HitTestMode.None)
                  }.width('100%')
                } else {
                  Text('未识别到候选，请搜索并选择正确的宝可梦。').fontSize(12).fontColor('#FFB74D').width('100%')
                }
                if (slot.recognitionRisk && !slot.confirmed && slot.selected) {
                  Text(`低置信度：匹配度 ${Math.round(slot.confidence * 100)}%，请人工核对。`)
                    .fontSize(12).fontColor('#FFB74D').width('100%')
                  Button('确认当前识别').id(`battle-setup-confirm-slot-${slot.slotIndex}`).width('100%')
                    .backgroundColor(ACCENT).fontColor(ON_ACCENT).onClick(() => {
                      battleOverlayCoordinator.confirmSetupOpponent(slot.slotIndex);
                      this.refresh(false);
                    })
                } else if (slot.recognitionRisk && slot.confirmed) {
                  Text('已人工确认此低置信度结果。').fontSize(12).fontColor(ACCENT).width('100%')
                }
              }.width('100%').padding(9).backgroundColor(SECONDARY).borderRadius(10)
            }, (slot: TeamPreviewReviewSlot) => `setup-opponent-${slot.slotIndex}`)
            Text('队伍预览无法确定对手的招式、特性和能力值，开始对局后可按实际情况选择。')
              .fontSize(12).fontColor(MUTED).width('100%')
          }.width('100%').padding({ bottom: 8 })
        }.layoutWeight(1).width('100%').scrollBar(BarState.Auto)
        Row({ space: 8 }) {
          Button('重新识别阵容').id('battle-setup-retry').layoutWeight(1).backgroundColor(SECONDARY).fontColor(TEXT)
            .onClick(() => {
              battleOverlayCoordinator.setMessage('请回到双方队伍预览页面，再点击悬浮入口选择“识别双方阵容”');
              this.leaveSetup();
            })
          Button('确认并开始对局').id('battle-setup-confirm').layoutWeight(1).backgroundColor(ACCENT).fontColor(ON_ACCENT)
            .enabled(snapshot.canConfirm)
            .onClick(() => this.confirmSetup())
        }.width('100%')
      }
    }.id('battle-setup-panel').width('100%').height('100%').padding(12).backgroundColor(PANEL)
      .border({ width: 2, color: BORDER }).borderRadius(18)
  }

  @Builder
  private panel(snapshot: BattleOverlaySnapshot) {
    Column({ space: 8 }) {
      this.titleBar(snapshot)
      Row({ space: 5 }) {
        Button('伤害').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'DAMAGE' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'DAMAGE' ? ON_ACCENT : TEXT).onClick(() => this.setSection('DAMAGE'))
        Button('战场状态').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'CONDITIONS' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'CONDITIONS' ? ON_ACCENT : TEXT).onClick(() => this.setSection('CONDITIONS'))
        Button('速度线').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'SPEED' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'SPEED' ? ON_ACCENT : TEXT).onClick(() => this.setSection('SPEED'))
        Button('对手配置').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'OPPONENT' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'OPPONENT' ? ON_ACCENT : TEXT).onClick(() => this.setSection('OPPONENT'))
      }.width('100%')
      Scroll() {
        Column({ space: 8 }) {
          if (snapshot.section === 'DAMAGE') this.damageSection(snapshot)
          else if (snapshot.section === 'CONDITIONS') this.conditionsSection(snapshot)
          else if (snapshot.section === 'SPEED') this.speedSection(snapshot)
          else this.opponentSection(snapshot)
          Divider()
          Row({ space: 6 }) {
            Button('重新识别双方阵容').layoutWeight(1).fontSize(12).backgroundColor(ACCENT).fontColor(ON_ACCENT)
              .onClick(() => this.recognizePreview())
            Button('识别我方').layoutWeight(1).fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT)
              .onClick(() => this.recognizeOwnTeam())
          }.width('100%')
        }.width('100%').padding({ bottom: 8 })
      }.layoutWeight(1).width('100%').scrollBar(BarState.Auto)
    }.id('battle-overlay-panel').width('100%').height('100%').padding(12).backgroundColor(PANEL)
      .border({ width: 2, color: BORDER }).borderRadius(18)
  }

  build() {
    Stack() {
      if (this.current().mode === 'setup') {
        this.setupPanel(battleOverlayCoordinator.setupSnapshot())
      } else if (!this.current().ready) {
        Column({ space: 10 }) {
          Text(this.current().mode === 'hud' ? '对战 HUD' : '对局伤害面板').fontSize(20).fontWeight(FontWeight.Bold)
          Text(this.current().message).fontColor(MUTED).textAlign(TextAlign.Center)
          Button('识别双方阵容').id('battle-overlay-recognize-preview').backgroundColor(ACCENT).fontColor(ON_ACCENT)
            .onClick(() => this.recognizePreview())
          Button('关闭').backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => battleOverlayCoordinator.close())
        }.id('battle-overlay-waiting').width('100%').height('100%').padding(18).justifyContent(FlexAlign.Center)
          .backgroundColor(PANEL).borderRadius(18)
      } else this.panel(this.current())
      Web({ src: $rawfile('runtime/damage/engine-host.html'), controller: this.controller })
        .javaScriptAccess(true).onPageEnd(() => this.onEngineLoaded()).width(1).height(1).opacity(0)
    }.width('100%').height('100%')
  }
}
