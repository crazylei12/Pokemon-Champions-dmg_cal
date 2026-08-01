import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { EntityRef, LocalizedNameEntry, MoveValue, OpponentProfile, SpeciesFormOption, SpeedRange } from './Models';
import { findEntity, normalizeShowdownId } from './EntityCatalog';
import { orderOpponentProfiles, possibleSpeedRange } from './PresetLogic';

interface RawMoveValue {
  move: EntityRef;
  basePower?: number;
  type?: string;
  source?: string;
}

interface RawBaseStats {
  hp: number;
  atk: number;
  def: number;
  spa: number;
  spd: number;
  spe: number;
}

interface RawSpeciesForm {
  familyId: string;
  configurationShareGroupId?: string;
  species: EntityRef;
  baseStats: RawBaseStats;
  defaultAbility?: EntityRef;
  abilities: EntityRef[];
  learnableMoves: RawMoveValue[];
}

interface RawSpeciesProfiles {
  species: EntityRef;
  profiles: OpponentProfile[];
}

interface ChampionsPresetRoot {
  schemaVersion: number;
  learnsetRulesetVersion: string;
  learnsetPoolSource: string;
  learnsetDataDate: string;
  speciesCount: number;
  profileCount: number;
  formGroupCount: number;
  speciesFormCount: number;
  moveTypes: Record<string, string>;
  movePriorities: Record<string, number>;
  speciesForms: RawSpeciesForm[];
  species: RawSpeciesProfiles[];
}

export interface RuntimeCatalogSummary {
  localizationEntries: number;
  speciesCount: number;
  profileCount: number;
  formGroupCount: number;
  speciesFormCount: number;
  learnsetRulesetVersion: string;
  learnsetPoolSource: string;
  learnsetDataDate: string;
}

function decodeUtf8(bytes: Uint8Array): string {
  return util.TextDecoder.create('utf-8').decodeToString(bytes);
}

function toMoveValue(raw: RawMoveValue, priorities: Record<string, number>, types: Record<string, string>): MoveValue {
  const id = normalizeShowdownId(raw.move.showdownId);
  return {
    entity: raw.move,
    basePower: raw.basePower,
    type: raw.type ?? types[id],
    priority: priorities[id] ?? 0,
    source: 'OPPONENT_LEGAL_MOVE_POOL'
  };
}

function toSpeciesFormOption(raw: RawSpeciesForm, priorities: Record<string, number>,
  types: Record<string, string>): SpeciesFormOption {
  return {
    familyId: raw.familyId,
    configurationShareGroupId: raw.configurationShareGroupId,
    species: raw.species,
    baseStats: raw.baseStats,
    defaultAbility: raw.defaultAbility,
    abilities: raw.abilities,
    learnableMoves: raw.learnableMoves.map((move: RawMoveValue) => toMoveValue(move, priorities, types))
  };
}

export class RuntimeDataRepository {
  private context: common.UIAbilityContext;
  private localization: LocalizedNameEntry[] = [];
  private root?: ChampionsPresetRoot;
  private forms: SpeciesFormOption[] = [];

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  async load(): Promise<RuntimeCatalogSummary> {
    const localizationBytes = await this.context.resourceManager.getRawFileContent(
      'runtime/localization/zh-Hans.json');
    const presetBytes = await this.context.resourceManager.getRawFileContent(
      'runtime/damage/champions-presets.json');
    this.localization = JSON.parse(decodeUtf8(localizationBytes)) as LocalizedNameEntry[];
    const root = JSON.parse(decodeUtf8(presetBytes)) as ChampionsPresetRoot;
    this.root = root;
    if (root.schemaVersion !== 6 || root.learnsetRulesetVersion !== 'pkmn-mods-champions-0.10.11' ||
      root.learnsetPoolSource !== 'CHAMPIONS_SNAPSHOT') {
      throw new Error('Unsupported Champions preset schema or ruleset.');
    }
    this.forms = root.speciesForms.map((raw: RawSpeciesForm) =>
      toSpeciesFormOption(raw, root.movePriorities, root.moveTypes));
    return this.summary();
  }

  summary(): RuntimeCatalogSummary {
    if (!this.root) {
      throw new Error('Runtime data repository is not loaded.');
    }
    return {
      localizationEntries: this.localization.length,
      speciesCount: this.root.speciesCount,
      profileCount: this.root.profileCount,
      formGroupCount: this.root.formGroupCount,
      speciesFormCount: this.root.speciesFormCount,
      learnsetRulesetVersion: this.root.learnsetRulesetVersion,
      learnsetPoolSource: this.root.learnsetPoolSource,
      learnsetDataDate: this.root.learnsetDataDate
    };
  }

  localize(entityType: string, value: string, language: string = 'zh-Hans'): EntityRef | undefined {
    return findEntity(this.localization, entityType, value, language);
  }

  formsFor(value: string): SpeciesFormOption[] {
    const id = normalizeShowdownId(value);
    const selected = this.forms.find((form: SpeciesFormOption) =>
      normalizeShowdownId(form.species.showdownId) === id);
    return selected ? this.forms.filter((form: SpeciesFormOption) => form.familyId === selected.familyId) : [];
  }

  legalMovesFor(value: string): MoveValue[] {
    const id = normalizeShowdownId(value);
    return this.forms.find((form: SpeciesFormOption) =>
      normalizeShowdownId(form.species.showdownId) === id)?.learnableMoves ?? [];
  }

  profilesFor(value: string, userProfiles: OpponentProfile[] = []): OpponentProfile[] {
    if (!this.root) {
      throw new Error('Runtime data repository is not loaded.');
    }
    const id = normalizeShowdownId(value);
    const builtIn = this.root.species.find((entry: RawSpeciesProfiles) =>
      normalizeShowdownId(entry.species.showdownId) === id)?.profiles ?? [];
    const generated: OpponentProfile[] = [
      { profileId: 'generated.default', profileName: '无加点', source: 'GENERATED_TEMPLATE', level: 50 },
      { profileId: 'generated.physical-bulk', profileName: '偏物耐', source: 'GENERATED_TEMPLATE', level: 50,
        statPoints: { hp: 32, def: 32 } },
      { profileId: 'generated.special-bulk', profileName: '偏特耐', source: 'GENERATED_TEMPLATE', level: 50,
        statPoints: { hp: 32, spd: 32 } },
      { profileId: 'generated.physical-offense', profileName: '物攻与速度', source: 'GENERATED_TEMPLATE', level: 50,
        statPoints: { atk: 32, spe: 32 } },
      { profileId: 'generated.special-offense', profileName: '特攻与速度', source: 'GENERATED_TEMPLATE', level: 50,
        statPoints: { spa: 32, spe: 32 } }
    ];
    return orderOpponentProfiles(userProfiles, generated, builtIn);
  }

  speedRangeFor(value: string): SpeedRange | undefined {
    const id = normalizeShowdownId(value);
    const baseSpeed = this.forms.find((form: SpeciesFormOption) =>
      normalizeShowdownId(form.species.showdownId) === id)?.baseStats.spe;
    return baseSpeed === undefined ? undefined : possibleSpeedRange(baseSpeed);
  }
}
