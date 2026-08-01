import { webview } from '@kit.ArkWeb';
import { DamageEngineRuntime } from '../domain/DamageEngineRuntime';
import {
  battleCondition,
  battleStatusText,
  BattleSide,
  parseBattleDirectDamageValues,
  SpeedLineAction,
  speedModifiers
} from '../domain/BattleSession';
import { EntityRef, MoveValue, OpponentProfile } from '../domain/Models';
import { ownTeamCaptureCoordinator } from '../services/OwnTeamCaptureCoordinator';
import {
  battleOverlayCoordinator,
  BattleOverlaySection,
  BattleOverlaySnapshot
} from '../services/BattleOverlayCoordinator';
import { CalculationDisplayResult, CalculationMoveResult, parseCalculationResult } from '../ui/AppUiModels';

const ACCENT: string = '#167D6D';
const TEXT: string = '#17233A';
const MUTED: string = '#667085';
const PANEL: string = '#F7FAFC';
const SECONDARY: string = '#EEF2F6';

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
  @State hudDamage: string[] = ['1 …', '2 …', '3 …', '4 …'];
  @State result: CalculationDisplayResult | undefined = undefined;
  @State dragX: number = 0;
  @State dragY: number = 0;
  @State abilityQuery: string = '';
  @State itemQuery: string = '';

  private controller: webview.WebviewController = new webview.WebviewController('pc-battle-overlay-damage');
  private engine?: DamageEngineRuntime;

  aboutToAppear(): void {
    this.revision += 1;
  }

  private current(): BattleOverlaySnapshot {
    this.revision;
    return battleOverlayCoordinator.snapshot();
  }

  private refresh(recalculate: boolean = true): void {
    this.revision += 1;
    this.result = undefined;
    if (recalculate && this.current().mode === 'hud') this.calculateHud();
  }

  private setSection(section: BattleOverlaySection): void {
    battleOverlayCoordinator.setSection(section);
    this.refresh(false);
  }

  private onEngineLoaded(): void {
    this.engine = new DamageEngineRuntime(this.controller);
    this.engine.getEngineInfo().then(() => {
      this.engineReady = true;
      if (this.current().mode === 'hud') this.calculateHud();
    }).catch((error: Error) => {
      this.message = `伤害引擎初始化失败：${String(error)}`;
    });
  }

  private async calculateHud(): Promise<void> {
    if (!this.engineReady || !this.engine || this.calculating || !this.current().ready) return;
    this.calculating = true;
    this.hudDamage = ['1 …', '2 …', '3 …', '4 …'];
    try {
      const prepared = battleOverlayCoordinator.prepareDamage(true);
      const raw = await this.engine.calculateRaw(prepared.request);
      this.hudDamage = parseBattleDirectDamageValues(raw, prepared.configuredMoves);
    } catch (error) {
      this.hudDamage = ['1 ?', '2 ?', '3 ?', '4 ?'];
      this.message = String(error);
    } finally {
      this.calculating = false;
    }
  }

  private async calculatePanel(): Promise<void> {
    if (!this.engineReady || !this.engine || this.calculating) return;
    this.calculating = true;
    this.result = undefined;
    try {
      const prepared = battleOverlayCoordinator.prepareDamage(false);
      this.result = parseCalculationResult(await this.engine.calculateRaw(prepared.request));
      this.message = '';
    } catch (error) {
      this.message = `计算失败：${String(error)}`;
    } finally {
      this.calculating = false;
    }
  }

  private async recognizePreview(): Promise<void> {
    try {
      await battleOverlayCoordinator.minimize();
      this.message = await ownTeamCaptureCoordinator.recognizeTeamPreview();
    } catch (error) {
      this.message = String(error);
    } finally {
      await battleOverlayCoordinator.reveal();
      this.refresh(false);
    }
  }

  private async recognizeOwnTeam(): Promise<void> {
    try {
      await battleOverlayCoordinator.minimize();
      this.message = await ownTeamCaptureCoordinator.recognizeOwnTeam();
    } catch (error) {
      this.message = String(error);
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

  private hudDamageText(snapshot: BattleOverlaySnapshot, value: string, index: number): string {
    return `${name(snapshot.own?.moves[index]?.entity, '空技能槽')}  ${value}`;
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
        Button('−').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.resize(-80, -80))
        Button('+').fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.resize(80, 80))
        Button('关闭').fontSize(12).backgroundColor('#FEE4E2').fontColor('#B42318')
          .onClick(() => battleOverlayCoordinator.close())
      }
      .width('100%')
      .gesture(PanGesture().onActionStart(() => { this.dragX = 0; this.dragY = 0; })
        .onActionUpdate((event: GestureEvent) => {
          const deltaX = Math.round(event.offsetX - this.dragX);
          const deltaY = Math.round(event.offsetY - this.dragY);
          this.dragX = event.offsetX; this.dragY = event.offsetY;
          battleOverlayCoordinator.moveBy(deltaX, deltaY);
        }))
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
                Color.White : TEXT)
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
      .enabled(enabled).backgroundColor(active ? '#D1FAE5' : SECONDARY)
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
    }.layoutWeight(1).padding(10).backgroundColor(Color.White).borderRadius(10)
  }

  @Builder
  private damageSection(snapshot: BattleOverlaySnapshot) {
    Column({ space: 8 }) {
      Row({ space: 6 }) {
        Button('我方输出').layoutWeight(1)
          .backgroundColor(snapshot.state.direction === 'OWN_TO_OPPONENT' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.direction === 'OWN_TO_OPPONENT' ? Color.White : TEXT)
          .onClick(() => { battleOverlayCoordinator.setDirection('OWN_TO_OPPONENT'); this.refresh(false); })
        Button('我方承伤').layoutWeight(1)
          .backgroundColor(snapshot.state.direction === 'OPPONENT_TO_OWN' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.direction === 'OPPONENT_TO_OWN' ? Color.White : TEXT)
          .onClick(() => { battleOverlayCoordinator.setDirection('OPPONENT_TO_OWN'); this.refresh(false); })
      }.width('100%')
      this.sideSlots(snapshot, 'OWN', '选择我方宝可梦')
      this.sideSlots(snapshot, 'OPPONENT', '选择对手宝可梦')
      Text('对手配置').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(snapshot.profiles.map((profile: OpponentProfile) => profile.profileName)))
        .selected(this.selectedProfileIndex(snapshot)).width('100%')
        .onSelect((index: number) => {
          const profile = snapshot.profiles[index];
          if (profile) { battleOverlayCoordinator.setPreset(profile.profileId); this.refresh(); }
        })
      Text('招式').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(snapshot.moves.map((move: MoveValue) => name(move.entity))))
        .selected(this.selectedMoveIndex(snapshot)).width('100%')
        .onSelect((index: number) => {
          const move = snapshot.moves[index];
          if (move) { battleOverlayCoordinator.setMove(move.entity.showdownId); this.refresh(false); }
        })
      Button(this.calculating ? '正在计算…' : this.engineReady ? '计算伤害' : '正在准备伤害引擎…')
        .id('battle-overlay-calculate').width('100%').enabled(this.engineReady && !this.calculating)
        .backgroundColor(ACCENT).onClick(() => this.calculatePanel())
      if (this.message.length > 0) Text(this.message).fontSize(12).fontColor('#B42318').width('100%')
      if (this.result) {
        Column({ space: 5 }) {
          Text(this.resultTitle()).fontWeight(FontWeight.Bold).width('100%')
          ForEach(this.resultMoves(), (move: CalculationMoveResult) => {
            Text(`${move.name}：${move.minPercent.toFixed(1)}–${move.maxPercent.toFixed(1)}% · ${move.koText}`)
              .fontSize(13).width('100%')
          })
        }.width('100%').padding(10).backgroundColor('#ECFDF3').borderRadius(10)
      }
    }.width('100%')
  }

  @Builder
  private conditionsSection(snapshot: BattleOverlaySnapshot) {
    Column({ space: 9 }) {
      Text('对战格式').fontSize(13).fontColor(MUTED).width('100%')
      Row({ space: 6 }) {
        Button('单打').layoutWeight(1).backgroundColor(snapshot.state.battleType === 'SINGLE' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.battleType === 'SINGLE' ? Color.White : TEXT)
          .onClick(() => { battleOverlayCoordinator.setBattleType('SINGLE'); this.refresh(); })
        Button('双打').layoutWeight(1).backgroundColor(snapshot.state.battleType === 'DOUBLE' ? ACCENT : SECONDARY)
          .fontColor(snapshot.state.battleType === 'DOUBLE' ? Color.White : TEXT)
          .onClick(() => { battleOverlayCoordinator.setBattleType('DOUBLE'); this.refresh(); })
      }.width('100%')
      Text('天气').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(['无天气', '晴天', '雨天', '沙暴', '雪天']))
        .selected(['NONE', 'SUN', 'RAIN', 'SAND', 'SNOW'].indexOf(snapshot.state.weather)).width('100%')
        .onSelect((index: number) => { battleOverlayCoordinator.setEnum('weather',
          ['NONE', 'SUN', 'RAIN', 'SAND', 'SNOW'][index]); this.refresh(); })
      Text('场地').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(['无场地', '电气场地', '青草场地', '精神场地', '薄雾场地']))
        .selected(['NONE', 'ELECTRIC', 'GRASSY', 'PSYCHIC', 'MISTY'].indexOf(snapshot.state.terrain)).width('100%')
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
    }.layoutWeight(1).padding(9).backgroundColor(Color.White).borderRadius(10)
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
            .fontSize(13).width('100%').padding(6).backgroundColor(action.side === 'OWN' ? '#ECFDF3' : '#FFF4E5')
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
          .selected(this.opponentFormIndex(snapshot)).width('100%').onSelect((index: number) => {
            const form = snapshot.opponentForms[index];
            if (form) { battleOverlayCoordinator.setOpponentForm(form); this.refresh(); }
          })
      }
      Text('选择对手配置').fontSize(13).fontColor(MUTED).width('100%')
      Select(options(snapshot.profiles.map((profile: OpponentProfile) => profile.profileName)))
        .selected(this.selectedProfileIndex(snapshot)).width('100%')
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
  private panel(snapshot: BattleOverlaySnapshot) {
    Column({ space: 8 }) {
      this.titleBar(snapshot)
      Row({ space: 5 }) {
        Button('伤害').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'DAMAGE' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'DAMAGE' ? Color.White : TEXT).onClick(() => this.setSection('DAMAGE'))
        Button('战场状态').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'CONDITIONS' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'CONDITIONS' ? Color.White : TEXT).onClick(() => this.setSection('CONDITIONS'))
        Button('速度线').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'SPEED' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'SPEED' ? Color.White : TEXT).onClick(() => this.setSection('SPEED'))
        Button('对手配置').layoutWeight(1).fontSize(12).backgroundColor(snapshot.section === 'OPPONENT' ? ACCENT : SECONDARY)
          .fontColor(snapshot.section === 'OPPONENT' ? Color.White : TEXT).onClick(() => this.setSection('OPPONENT'))
      }.width('100%')
      Scroll() {
        Column({ space: 8 }) {
          if (snapshot.section === 'DAMAGE') this.damageSection(snapshot)
          else if (snapshot.section === 'CONDITIONS') this.conditionsSection(snapshot)
          else if (snapshot.section === 'SPEED') this.speedSection(snapshot)
          else this.opponentSection(snapshot)
          Divider()
          Row({ space: 6 }) {
            Button('重新识别双方阵容').layoutWeight(1).fontSize(12).backgroundColor('#8B5CF6')
              .onClick(() => this.recognizePreview())
            Button('识别我方').layoutWeight(1).fontSize(12).backgroundColor(SECONDARY).fontColor(TEXT)
              .onClick(() => this.recognizeOwnTeam())
          }.width('100%')
        }.width('100%').padding({ bottom: 8 })
      }.layoutWeight(1).width('100%').scrollBar(BarState.Auto)
    }.id('battle-overlay-panel').width('100%').height('100%').padding(12).backgroundColor(PANEL)
      .border({ width: 2, color: '#D0D5DD' }).borderRadius(18)
  }

  @Builder
  private hudPicker(snapshot: BattleOverlaySnapshot, side: BattleSide, displayIndex: number) {
    Column({ space: 3 }) {
      Text(side === 'OWN' ? `我方${displayIndex + 1}` : `对手${displayIndex + 1}`).fontSize(11).fontColor(MUTED)
      Select(options(side === 'OWN' ? snapshot.ownNames : snapshot.opponentNames))
        .selected((side === 'OWN' ? snapshot.state.directHud.ownSlots : snapshot.state.directHud.opponentSlots)[displayIndex])
        .onSelect((index: number) => { battleOverlayCoordinator.replaceHudSlot(side, displayIndex, index); this.refresh(); })
    }.layoutWeight(1).padding(6).backgroundColor(Color.White).borderRadius(8)
  }

  @Builder
  private hud(snapshot: BattleOverlaySnapshot) {
    if (!snapshot.state.directHud.visible) {
      Column() {
        Button('显示对战 HUD').id('battle-hud-restore').backgroundColor(ACCENT)
          .onClick(async () => { await battleOverlayCoordinator.setHudVisible(true); this.refresh(); })
      }.id('battle-hud-hidden-entry').width('100%').height('100%').justifyContent(FlexAlign.Center)
        .backgroundColor('#E6FFFFFF').borderRadius(16)
    } else {
      Column({ space: 7 }) {
        Row({ space: 5 }) {
          Button('布局').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => this.resize(80, 80))
          Button('再战').id('battle-hud-rematch').fontSize(11).backgroundColor('#8B5CF6')
            .onClick(() => this.recognizePreview())
          Button('隐藏 HUD').id('battle-hud-hide').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
            .onClick(async () => { await battleOverlayCoordinator.setHudVisible(false); this.refresh(false); })
          Button(snapshot.state.battleType === 'DOUBLE' ? '双打' : '单打').id('battle-hud-format').fontSize(11)
            .backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
              battleOverlayCoordinator.setBattleType(snapshot.state.battleType === 'DOUBLE' ? 'SINGLE' : 'DOUBLE');
              this.refresh();
            })
          Button('识别我方').fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
            .onClick(() => this.recognizeOwnTeam())
          Button('详细面板').fontSize(11).backgroundColor(ACCENT).onClick(async () => {
            await battleOverlayCoordinator.show('panel'); this.refresh(false);
          })
          Button('关闭').fontSize(11).backgroundColor('#FEE4E2').fontColor('#B42318')
            .onClick(() => battleOverlayCoordinator.close())
        }.width('100%')
        Row({ space: 6 }) {
          if (snapshot.state.battleType === 'DOUBLE') this.hudPicker(snapshot, 'OPPONENT', 0)
          this.hudPicker(snapshot, 'OPPONENT', snapshot.state.battleType === 'DOUBLE' ? 1 : 0)
          if (snapshot.state.battleType === 'DOUBLE') this.hudPicker(snapshot, 'OWN', 0)
          this.hudPicker(snapshot, 'OWN', snapshot.state.battleType === 'DOUBLE' ? 1 : 0)
        }.width('100%')
        Row({ space: 6 }) {
          Column({ space: 4 }) {
            Text('速度顺序').fontWeight(FontWeight.Bold).fontSize(12).width('100%')
            ForEach(snapshot.speedActions.slice(0, 4), (action: SpeedLineAction, index: number) => {
              Text(this.speedActionText(action, index))
                .fontSize(11).width('100%')
            })
          }.layoutWeight(1).padding(7).backgroundColor(Color.White).borderRadius(8)
          Column({ space: 3 }) {
            Text('四招伤害').fontWeight(FontWeight.Bold).fontSize(12).width('100%')
            ForEach(this.hudDamage, (value: string, index: number) => {
              Text(this.hudDamageText(snapshot, value, index))
                .fontSize(11).fontColor(index === 0 ? ACCENT : TEXT).width('100%')
            })
          }.layoutWeight(1).padding(7).backgroundColor(Color.White).borderRadius(8)
        }.width('100%').alignItems(VerticalAlign.Top)
        Row({ space: 6 }) {
          Button(battleStatusText(snapshot.state)).layoutWeight(1).fontSize(11).backgroundColor(SECONDARY).fontColor(TEXT)
            .onClick(async () => { await battleOverlayCoordinator.show('panel'); this.setSection('CONDITIONS'); })
          Select(options(snapshot.profiles.map((profile: OpponentProfile) => profile.profileName)))
            .selected(this.selectedProfileIndex(snapshot)).layoutWeight(1)
            .onSelect((index: number) => {
              const profile = snapshot.profiles[index];
              if (profile) { battleOverlayCoordinator.setPreset(profile.profileId); this.refresh(); }
            })
          Button(snapshot.state.speedLine.trickRoom ? '戏法空间' : '普通空间').fontSize(11)
            .backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => {
              battleOverlayCoordinator.setFlag('trickRoom', !snapshot.state.speedLine.trickRoom); this.refresh();
            })
        }.width('100%')
      }.id('battle-direct-hud').width('100%').height('100%').padding(10).backgroundColor('#EAF7FAFC')
        .border({ width: 2, color: '#98A2B3' }).borderRadius(16)
    }
  }

  build() {
    Stack() {
      if (!this.current().ready) {
        Column({ space: 10 }) {
          Text(this.current().mode === 'hud' ? '对战 HUD' : '对局伤害面板').fontSize(20).fontWeight(FontWeight.Bold)
          Text(this.current().message).fontColor(MUTED).textAlign(TextAlign.Center)
          Button('识别双方阵容').id('battle-overlay-recognize-preview').backgroundColor('#8B5CF6')
            .onClick(() => this.recognizePreview())
          Button('关闭').backgroundColor(SECONDARY).fontColor(TEXT).onClick(() => battleOverlayCoordinator.close())
        }.id('battle-overlay-waiting').width('100%').height('100%').padding(18).justifyContent(FlexAlign.Center)
          .backgroundColor(PANEL).borderRadius(18)
      } else if (this.current().mode === 'hud') this.hud(this.current())
      else this.panel(this.current())
      Web({ src: $rawfile('runtime/damage/engine-host.html'), controller: this.controller })
        .javaScriptAccess(true).onPageEnd(() => this.onEngineLoaded()).width(1).height(1).opacity(0)
    }.width('100%').height('100%')
  }
}
