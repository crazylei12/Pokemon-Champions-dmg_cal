package com.crazylei12.pokemonchampionsassistant

/** PS uses EVs for both traditional EVs and Champions points; the caller must select the format. */
internal object ShowdownTeamCodec {
    private val labels = linkedMapOf("hp" to "HP", "atk" to "Atk", "def" to "Def", "spa" to "SpA", "spd" to "SpD", "spe" to "Spe")

    data class Parsed(val members: List<ResolvedTeamCodeMember>, val warnings: List<String>)

    fun parse(text: String, traditionalEvs: Boolean = false): Parsed {
        require(text.length <= 50_000) { "PS 文本过长（最多 50000 字符）" }
        val warnings = linkedSetOf<String>()
        if (traditionalEvs) warnings += "传统 EV 按 50 级、31 IV 换算为 Champions 能力点，仅为近似配置；请核对预览。"
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        require(clean.isNotEmpty()) { "请粘贴 PS 队伍文本" }
        val headers = clean.lineSequence().filter { it.trim().startsWith("===") }.toList()
        require(headers.size <= 1) { "请一次导入一支队伍" }
        val blocks = clean.lines().filterNot { it.trim().startsWith("===") }.joinToString("\n")
            .trim().split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
        require(blocks.size in 1..6) { "PS 队伍必须包含 1–6 只宝可梦，并用空行分隔" }
        val members = blocks.mapIndexed { index, block ->
            val prefix = "第 ${index + 1} 只宝可梦："
            val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
            val first = lines.first().split(" @ ", limit = 2)
            var species = first[0]
            val gender = Regex(" \\(([MF])\\)$").find(species)?.groupValues?.get(1)
            if (gender != null) species = species.dropLast(4)
            Regex("^.+ \\(([^()]+)\\)$").matchEntire(species)?.let {
                species = it.groupValues[1]
                warnings += "昵称不保存；导出使用宝可梦名称。"
            }
            var nature = "Serious"
            var ability = ""
            var level = 50
            var points = StatFields.fromMap(labels.keys.associateWith { "0" })
            val moves = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (line in lines.drop(1)) {
                if (line.startsWith("- ")) { moves += line.drop(2).trim(); continue }
                val key = when {
                    line.endsWith(" Nature") -> "Nature"
                    ':' in line -> line.substringBefore(':')
                    else -> error(prefix + "无法识别“$line”")
                }
                require(seen.add(key)) { prefix + "重复字段 $key" }
                val value = line.substringAfter(':').trim()
                when (key) {
                    "Ability" -> ability = value
                    "Nature" -> nature = line.removeSuffix(" Nature").trim()
                    "Level" -> {
                        val parsed = value.toIntOrNull()
                        require(parsed != null && parsed in 1..100) { prefix + "等级无效" }
                        if (parsed != 50) warnings += "等级统一为 Champions 对战的 50 级。"
                        level = 50
                    }
                    "EVs", "SPs", "Stat Points" -> {
                        require(seen.count { it in setOf("EVs", "SPs", "Stat Points") } == 1) { prefix + "能力分配字段重复" }
                        val ev = traditionalEvs && key == "EVs"
                        val raw = parseStats(value, if (ev) 252 else 32, prefix)
                        require(raw.values.sum() <= if (ev) 510 else 66) { prefix + "能力分配总和超限" }
                        points = StatFields.fromMap(labels.keys.associateWith { stat ->
                            val n = raw[stat] ?: 0
                            (if (ev) (n + 4) / 8 else n).toString()
                        })
                        require(points.asMap().values.sumOf(String::toInt) <= 66) { prefix + "换算后能力点超过 66，请调整原队伍" }
                    }
                    "IVs" -> {
                        val ivs = parseStats(value, 31, prefix)
                        if (ivs.values.any { it != 31 }) warnings += "Champions 不使用传统 IV；非 31 IV 不保留，请重新核对速度等能力。"
                    }
                    "Shiny", "Happiness", "Pokeball", "Hidden Power", "Dynamax Level", "Gigantamax", "Tera Type" ->
                        warnings += "不保留 Champions 配置未使用的字段：$key。"
                    else -> error(prefix + "不支持字段 $key")
                }
            }
            require(species.isNotBlank() && ability.isNotBlank()) { prefix + "缺少名称或 Ability 特性" }
            require(moves.size in 1..4 && moves.all(String::isNotBlank)) { prefix + "需要 1–4 个招式" }
            require(moves.distinctBy(::normalizeShowdownId).size == moves.size) { prefix + "招式重复" }
            if ("Nature" !in seen) warnings += "未填写性格时按 Serious（无能力修正）导入。"
            ResolvedTeamCodeMember(species, level, gender, nature, ability,
                first.getOrNull(1)?.trim()?.takeUnless { it.isEmpty() || normalizeShowdownId(it) == "noitem" }, points, moves)
        }
        return Parsed(members, warnings.toList())
    }

    private fun parseStats(value: String, max: Int, prefix: String): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for (part in value.split('/')) {
            val match = Regex("(\\d+)\\s+(HP|Atk|Def|SpA|SpD|Spe)", RegexOption.IGNORE_CASE).matchEntire(part.trim())
            require(match != null) { prefix + "能力分配格式无效：$part" }
            val key = match.groupValues[2].lowercase()
            val number = match.groupValues[1].toIntOrNull()
            require(number != null && number in 0..max && !result.containsKey(key)) { prefix + "能力分配重复或超限：$part" }
            result[key] = number
        }
        return result
    }

    fun export(pokemon: List<PokemonConfig>): String {
        require(pokemon.size in 1..6) { "只能导出 1–6 只宝可梦" }
        return pokemon.joinToString("\n\n") { config ->
            val points = config.statPoints.asMap()
            require(config.statAlignment != null && points.values.all { it.toIntOrNull() in 0..32 }) {
                "${config.species.displayName} 缺少性格或完整能力点，请先手动补全后导出"
            }
            require(points.values.sumOf(String::toInt) <= 66) { "${config.species.displayName} 能力点总和超过 66" }
            require(config.level == 50 && config.ability != null && config.moves.size in 1..4) { "${config.species.displayName} 配置不完整或不是 50 级" }
            buildString {
                append(config.species.showdownId)
                showdownGender(config.gender)?.let { append(" ($it)") }
                config.item?.let { append(" @ ${it.showdownId}") }
                append("\nAbility: ${config.ability.showdownId}\nLevel: 50\n")
                val spread = labels.mapNotNull { (key, label) -> points.getValue(key).toInt().takeIf { it > 0 }?.let { "$it $label" } }
                append("EVs: ${spread.ifEmpty { listOf("0 HP") }.joinToString(" / ")}\n")
                append("${config.statAlignment.showdownId} Nature")
                config.moves.forEach { append("\n- ${it.entity.showdownId}") }
            }
        } + "\n"
    }
}
