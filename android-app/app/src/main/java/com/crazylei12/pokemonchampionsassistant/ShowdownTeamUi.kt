package com.crazylei12.pokemonchampionsassistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun ShowdownExportButton(pokemon: List<PokemonConfig>) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    TextButton(onClick = {
        copied = false
        runCatching { OpponentPresetRepository(context).exportShowdown(pokemon) }.onSuccess { text = it }.onFailure { error = it.message }
    }) { Text("导出 PS 码") }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("暂时无法导出") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } })
    }
    text?.let { output ->
        AlertDialog(onDismissRequest = { text = null }, title = { Text("PS 队伍文本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("用于 PS 的 Champions 模式；EVs 数字代表能力点。只导出已记录的配置，未记录的性别、昵称等不会补造。")
                    OutlinedTextField(value = output, onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
                }
            }, confirmButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("PS 队伍", output))
                    copied = true
                }) { Text(if (copied) "已复制" else "复制全部") }
            }, dismissButton = { TextButton(onClick = { text = null }) { Text("关闭") } })
    }
}

@Composable
internal fun ShowdownImportScreen(onClose: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { OpponentPresetRepository(context) }
    var input by rememberSaveable { mutableStateOf("") }
    var traditional by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var pokemon by remember { mutableStateOf<List<PokemonConfig>>(emptyList()) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onClose) { Text("返回") }
        Text("导入 PS 队伍", style = MaterialTheme.typography.headlineSmall)
        Text("粘贴 Pokémon Showdown 的 Import/Export 队伍文本，宝可梦之间用空行分隔。离线解析，核对后保存。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !traditional, onClick = { traditional = false; pokemon = emptyList(); error = "" }, label = { Text("Champions 能力点") })
            FilterChip(selected = traditional, onClick = { traditional = true; pokemon = emptyList(); error = "" }, label = { Text("传统 EV 换算") })
        }
        Text(if (traditional) "EVs 按传统努力值换算，IV 不保留；请核对换算结果。" else "EVs / SPs 为能力点：单项 0–32，总和最多 66。")
        OutlinedTextField(value = input, onValueChange = { input = it.take(50_001); pokemon = emptyList(); error = "" },
            label = { Text("PS 队伍文本") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp))
        Button(onClick = {
            pokemon = emptyList()
            runCatching {
                val parsed = ShowdownTeamCodec.parse(input, traditional)
                val mapped = repository.configurationsForShowdown(parsed.members)
                warnings = parsed.warnings
                mapped
            }.onSuccess { pokemon = it; error = "" }.onFailure { error = it.message ?: "PS 文本解析失败" }
        }, enabled = input.isNotBlank()) { Text("解析并预览") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (pokemon.isNotEmpty()) {
            warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            Text("已解析 ${pokemon.size} 只宝可梦", style = MaterialTheme.typography.titleMedium)
            pokemon.forEachIndexed { index, config ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${index + 1}. ${config.species.displayName}")
                        Text("${config.statAlignment?.displayName} · ${config.ability?.displayName} · ${config.item?.displayName ?: "无道具"}")
                        Text(config.moves.joinToString(" / ") { it.entity.displayName })
                        Text("能力点 HP/攻击/防御/特攻/特防/速度：${config.statPoints.asMap().values.joinToString(" / ")}")
                        Text("实际能力：${config.actualStats.asMap().values.joinToString(" / ")}")
                    }
                }
            }
            OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("队伍名称") }, singleLine = true)
            Button(onClick = {
                runCatching { TeamRepository.saveShowdownTeam(context, name, pokemon) }
                    .onSuccess { onSaved(it.name) }.onFailure { error = it.message ?: "保存失败" }
            }, enabled = name.isNotBlank()) { Text("保存为我的队伍") }
            ShowdownExportButton(pokemon)
        }
    }
}
