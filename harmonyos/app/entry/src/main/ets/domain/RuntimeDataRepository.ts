import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { EntityRef, EntityType, LocalizedNameEntry, MoveValue, NatureOption, OpponentProfile, SpeciesFormOption,
  SpeedRange, StatValues } from './Models';
import { entitySearchMatches, findEntity, normalizeShowdownId, toEntityRef } from './EntityCatalog';
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
  types: string[];
  typeMatchups: Record<string, string[]>;
  baseStats: RawBaseStats;
  defaultAbility?: EntityRef;
  abilities: EntityRef[];
  learnableMoves: RawMoveValue[];
}

interface RawSpeciesProfiles {
  species: EntityRef;
  profiles: OpponentProfile[];
}

interface RawNatureOption {
  nature: EntityRef;
  plus?: string;
  minus?: string;
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
  natures?: RawNatureOption[];
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
    types: raw.types,
    typeMatchups: raw.typeMatchups,
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

  entityCatalog(entityType: EntityType, language: string = 'zh-Hans'): EntityRef[] {
    return this.localization
      .filter((entry: LocalizedNameEntry) => entry.entityType === entityType)
      .map((entry: LocalizedNameEntry) => toEntityRef(entry, language))
      .sort((left: EntityRef, right: EntityRef) =>
        (left.displayName ?? left.showdownId).localeCompare(right.displayName ?? right.showdownId, 'zh-Hans'));
  }

  searchEntities(entityType: EntityType, query: string, limit: number = 100,
    language: string = 'zh-Hans'): EntityRef[] {
    const normalizedQuery = query.trim();
    const entries = this.localization.filter((entry: LocalizedNameEntry) => entry.entityType === entityType &&
      (normalizedQuery.length === 0 || entitySearchMatches(entry, normalizedQuery)));
    return entries.slice(0, Math.max(1, limit)).map((entry: LocalizedNameEntry) => toEntityRef(entry, language));
  }

  formFor(value: string): SpeciesFormOption | undefined {
    const id = normalizeShowdownId(value);
    return this.forms.find((form: SpeciesFormOption) => normalizeShowdownId(form.species.showdownId) === id);
  }

  abilitiesFor(value: string): EntityRef[] {
    const form = this.formFor(value);
    if (!form) return [];
    const values: EntityRef[] = [];
    if (form.defaultAbility) values.push(form.defaultAbility);
    for (const ability of form.abilities) {
      if (!values.some((candidate: EntityRef) => normalizeShowdownId(candidate.showdownId) ===
        normalizeShowdownId(ability.showdownId))) values.push(ability);
    }
    return values;
  }

  natureOptions(): NatureOption[] {
    return (this.root?.natures ?? []).map((entry: RawNatureOption): NatureOption => ({
      entity: entry.nature,
      plus: entry.plus,
      minus: entry.minus
    }));
  }

  actualStatsFor(value: string, points: StatValues = {}, nature?: EntityRef): StatValues {
    const base = this.formFor(value)?.baseStats;
    if (!base) return {};
    const natureOption = this.natureOptions().find((entry: NatureOption) =>
      normalizeShowdownId(entry.entity.showdownId) === normalizeShowdownId(nature?.showdownId ?? ''));
    const plus = natureOption?.plus === natureOption?.minus ? undefined : natureOption?.plus;
    const minus = natureOption?.minus === natureOption?.plus ? undefined : natureOption?.minus;
    const result: StatValues = {};
    for (const stat of ['hp', 'atk', 'def', 'spa', 'spd', 'spe']) {
      const key = stat as keyof StatValues;
      const baseValue = base[key] ?? 0;
      const pointValue = Math.max(0, Math.min(32, points[key] ?? 0));
      if (stat === 'hp') result[key] = baseValue === 1 ? 1 : baseValue + pointValue + 75;
      else result[key] = Math.floor((stat === plus ? 1.1 : stat === minus ? 0.9 : 1) *
        (baseValue + pointValue + 20));
    }
    return result;
  }

  formsFor(value: string): SpeciesFormOption[] {
    const id = normalizeShowdownId(value);
    const selected = this.forms.find((form: SpeciesFormOption) =>
      normalizeShowdownId(form.species.showdownId) === id);
    return selected ? this.forms.filter((form: SpeciesFormOption) => form.familyId === selected.familyId) : [];
  }

  typeMatchupsFor(value: string): Record<string, string[]> {
    return this.formFor(value)?.typeMatchups ?? {};
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
