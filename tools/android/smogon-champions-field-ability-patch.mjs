import {readFile} from 'node:fs/promises';

const CHAMPIONS_MECHANICS = /[\\/]external[\\/]smogon-damage-calc[\\/]calc[\\/]dist[\\/]mechanics[\\/]champions\.js$/i;
const FIELD_MODEL = /[\\/]external[\\/]smogon-damage-calc[\\/]calc[\\/]dist[\\/]field\.js$/i;

function replaceExactlyOnce(source, before, after, label) {
  const first = source.indexOf(before);
  if (first < 0) throw new Error(`Smogon Champions field-ability patch anchor is missing: ${label}`);
  if (source.indexOf(before, first + before.length) >= 0) {
    throw new Error(`Smogon Champions field-ability patch anchor is ambiguous: ${label}`);
  }
  return source.slice(0, first) + after + source.slice(first + before.length);
}

export function patchChampionsFieldAbilities(input) {
  let source = input.replace(/\r\n/g, '\n');

  source = replaceExactlyOnce(
    source,
    "defender.hasAbility('Queenly Majesty', 'Armor Tail')",
    "defender.hasAbility('Queenly Majesty', 'Armor Tail', 'Dazzling')",
    'Dazzling priority protection',
  );
  source = replaceExactlyOnce(
    source,
    "(attacker.hasAbility('Strong Jaw') && move.flags.bite) ||\n        (attacker.hasAbility('Sharpness') && move.flags.slicing)) {",
    "(attacker.hasAbility('Strong Jaw') && move.flags.bite) ||\n        (attacker.hasAbility('Steely Spirit') && move.hasType('Steel')) ||\n        (attacker.hasAbility('Sharpness') && move.flags.slicing)) {",
    'Steely Spirit self boost',
  );
  source = replaceExactlyOnce(
    source,
    `    var aura = "".concat(move.type, " Aura");
    var isAttackerAura = attacker.hasAbility(aura);
    var isDefenderAura = defender.hasAbility(aura);
    var isFieldFairyAura = field.isFairyAura && move.type === 'Fairy';
    var isFieldDarkAura = field.isDarkAura && move.type === 'Dark';
    var auraActive = isAttackerAura || isDefenderAura || isFieldFairyAura || isFieldDarkAura;
    if (auraActive) {
        bpMods.push(5448);
        if (isAttackerAura)
            desc.attackerAbility = attacker.ability;
        if (isDefenderAura)
            desc.defenderAbility = defender.ability;
    }`,
    `    var aura = "".concat(move.type, " Aura");
    var isAttackerAura = attacker.hasAbility(aura);
    var isDefenderAura = defender.hasAbility(aura);
    var isUserAuraBreak = attacker.hasAbility('Aura Break') || defender.hasAbility('Aura Break');
    var isFieldFairyAura = field.isFairyAura && move.type === 'Fairy';
    var isFieldDarkAura = field.isDarkAura && move.type === 'Dark';
    var auraActive = isAttackerAura || isDefenderAura || isFieldFairyAura || isFieldDarkAura;
    var auraBreak = field.isAuraBreak || isUserAuraBreak;
    if (auraActive) {
        bpMods.push(auraBreak ? 3072 : 5448);
        if (isAttackerAura)
            desc.attackerAbility = attacker.ability;
        if (isDefenderAura)
            desc.defenderAbility = defender.ability;
    }`,
    'Aura Break',
  );
  source = replaceExactlyOnce(
    source,
    "    if (attacker.hasAbility('Rivalry') && ![attacker.gender, defender.gender].includes('N')) {",
    `    if (field.attackerSide.isBattery && move.category === 'Special') {
        bpMods.push(5325);
        desc.isBattery = true;
    }
    if (field.attackerSide.isPowerSpot) {
        bpMods.push(5325);
        desc.isPowerSpot = true;
    }
    if (attacker.hasAbility('Rivalry') && ![attacker.gender, defender.gender].includes('N')) {`,
    'Battery and Power Spot ally boosts',
  );
  source = replaceExactlyOnce(
    source,
    `    if ((attacker.hasAbility('Solar Power') &&
        field.hasWeather('Sun') &&
        move.category === 'Special')) {`,
    `    if ((attacker.hasAbility('Solar Power') &&
        field.hasWeather('Sun') &&
        move.category === 'Special') ||
        (attacker.named('Cherrim') && attacker.hasAbility('Flower Gift') &&
            field.hasWeather('Sun', 'Harsh Sunshine') && move.category === 'Physical')) {`,
    'Flower Gift self attack boost',
  );
  source = replaceExactlyOnce(
    source,
    "    if ((defender.hasAbility('Thick Fat') && move.hasType('Fire', 'Ice')) ||",
    `    if (field.attackerSide.isFlowerGift && !attacker.hasAbility('Flower Gift') &&
        field.hasWeather('Sun', 'Harsh Sunshine') && move.category === 'Physical') {
        atMods.push(6144);
        desc.weather = field.weather;
        desc.isFlowerGiftAttacker = true;
    }
    if (field.attackerSide.isSteelySpirit && move.hasType('Steel')) {
        atMods.push(6144);
        desc.isSteelySpiritAttacker = true;
    }
    var isTabletsOfRuinActive = (defender.hasAbility('Tablets of Ruin') || field.isTabletsOfRuin) &&
        !attacker.hasAbility('Tablets of Ruin');
    var isVesselOfRuinActive = (defender.hasAbility('Vessel of Ruin') || field.isVesselOfRuin) &&
        !attacker.hasAbility('Vessel of Ruin');
    if ((isTabletsOfRuinActive && move.category === 'Physical') ||
        (isVesselOfRuinActive && move.category === 'Special')) {
        if (defender.hasAbility('Tablets of Ruin', 'Vessel of Ruin')) {
            desc.defenderAbility = defender.ability;
        }
        else {
            desc[move.category === 'Special' ? 'isVesselOfRuin' : 'isTabletsOfRuin'] = true;
        }
        atMods.push(3072);
    }
    if ((defender.hasAbility('Thick Fat') && move.hasType('Fire', 'Ice')) ||`,
    'Flower Gift, Steely Spirit, and offensive Ruin modifiers',
  );
  source = replaceExactlyOnce(
    source,
    `    var dfMods = [];
    if (defender.hasAbility('Marvel Scale') && defender.status && hitsPhysical) {
        dfMods.push(6144);
        desc.defenderAbility = defender.ability;
    }
    else if (defender.hasAbility('Fur Coat') && hitsPhysical) {
        dfMods.push(8192);
        desc.defenderAbility = defender.ability;
    }
    return dfMods;`,
    `    var dfMods = [];
    if (defender.hasAbility('Marvel Scale') && defender.status && hitsPhysical) {
        dfMods.push(6144);
        desc.defenderAbility = defender.ability;
    }
    else if (defender.named('Cherrim') && defender.hasAbility('Flower Gift') &&
        field.hasWeather('Sun', 'Harsh Sunshine') && !hitsPhysical) {
        dfMods.push(6144);
        desc.defenderAbility = defender.ability;
        desc.weather = field.weather;
    }
    else if (field.defenderSide.isFlowerGift && !defender.hasAbility('Flower Gift') &&
        field.hasWeather('Sun', 'Harsh Sunshine') && !hitsPhysical) {
        dfMods.push(6144);
        desc.weather = field.weather;
        desc.isFlowerGiftDefender = true;
    }
    else if (defender.hasAbility('Fur Coat') && hitsPhysical) {
        dfMods.push(8192);
        desc.defenderAbility = defender.ability;
    }
    var isSwordOfRuinActive = (attacker.hasAbility('Sword of Ruin') || field.isSwordOfRuin) &&
        !defender.hasAbility('Sword of Ruin');
    var isBeadsOfRuinActive = (attacker.hasAbility('Beads of Ruin') || field.isBeadsOfRuin) &&
        !defender.hasAbility('Beads of Ruin');
    if ((isSwordOfRuinActive && hitsPhysical) || (isBeadsOfRuinActive && !hitsPhysical)) {
        if (attacker.hasAbility('Sword of Ruin', 'Beads of Ruin')) {
            desc.attackerAbility = attacker.ability;
        }
        else {
            desc[hitsPhysical ? 'isSwordOfRuin' : 'isBeadsOfRuin'] = true;
        }
        dfMods.push(3072);
    }
    return dfMods;`,
    'Flower Gift defense and defensive Ruin modifiers',
  );
  source = replaceExactlyOnce(
    source,
    "        !attacker.hasAbility('Unnerve')) {",
    "        !attacker.hasAbility('Unnerve') && !field.attackerSide.isUnnerve) {",
    'partner Unnerve suppresses resist berries',
  );

  return source;
}

export function patchSmogonFieldForActiveAbilities(input) {
  return replaceExactlyOnce(
    input.replace(/\r\n/g, '\n'),
    `        this.isSteelySpirit = !!side.isSteelySpirit;
        this.isSwitching = side.isSwitching;`,
    `        this.isSteelySpirit = !!side.isSteelySpirit;
        this.isUnnerve = !!side.isUnnerve;
        this.isSwitching = side.isSwitching;`,
    'Side.isUnnerve',
  );
}

export function smogonChampionsFieldAbilityPatchPlugin() {
  return {
    name: 'smogon-champions-field-abilities',
    setup(build) {
      build.onLoad({filter: /(?:champions|field)\.js$/}, async args => {
        if (!CHAMPIONS_MECHANICS.test(args.path) && !FIELD_MODEL.test(args.path)) return undefined;
        const source = await readFile(args.path, 'utf8');
        return {
          contents: CHAMPIONS_MECHANICS.test(args.path)
            ? patchChampionsFieldAbilities(source)
            : patchSmogonFieldForActiveAbilities(source),
          loader: 'js',
        };
      });
    },
  };
}
