package com.crazylei12.pokemonchampionsassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var engineRuntime: DamageEngineRuntime
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayCaptureService.start(this, result.resultCode, data)
        } else {
            CaptureUiState.message.value = "用户取消了屏幕截图授权"
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LegacyRecognitionStorageMigration.runInBackground(this)
        engineRuntime = DamageEngineRuntime(this)
        setContent { ChampionsDamageApp(engineRuntime, this) }
    }

    override fun onDestroy() {
        engineRuntime.destroy()
        super.onDestroy()
    }

    fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    fun startOwnTeamCapture() {
        if (!Settings.canDrawOverlays(this)) {
            CaptureUiState.message.value = "请先授予悬浮窗权限"
            requestOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }
}

private val AppColors = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color(0xFF281E00),
    secondary = Color(0xFF77D7C4),
    background = Color(0xFF0E1420),
    surface = Color(0xFF171F2E),
    surfaceVariant = Color(0xFF263145),
    onBackground = Color(0xFFF1F4FF),
    onSurface = Color(0xFFF1F4FF),
)

private enum class AppTab(val label: String, val glyph: String) {
    HOME("首页", "⌂"),
    MANUAL("计算", "⚔"),
    BATTLE("对局", "◉"),
    SETTINGS("设置", "⚙"),
}

@Composable
private fun ChampionsDamageApp(runtime: DamageEngineRuntime, activity: MainActivity) {
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    var preferredOwnTeamId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTab = AppTab.valueOf(selectedTabName)
    val context = LocalContext.current
    val teamLibraryRevision by CaptureUiState.teamLibraryRevision
    val teamsResult = remember(teamLibraryRevision) { runCatching { TeamRepository.load(context) } }

    MaterialTheme(colorScheme = AppColors) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AndroidView(
                factory = { runtime.webView },
                modifier = Modifier.size(1.dp).alpha(0f),
            )
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == selectedTab,
                                onClick = { selectedTabName = tab.name },
                                icon = { Text(tab.glyph) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Surface(Modifier.fillMaxSize().padding(padding)) {
                    teamsResult.fold(
                        onSuccess = { teams ->
                            when (selectedTab) {
                                AppTab.HOME -> HomeScreen(teams, runtime) { teamId ->
                                    preferredOwnTeamId = teamId
                                    selectedTabName = AppTab.MANUAL.name
                                }
                                AppTab.MANUAL -> ManualCalculatorScreen(teams, runtime, preferredOwnTeamId)
                                AppTab.BATTLE -> OwnTeamCaptureScreen(activity)
                                AppTab.SETTINGS -> SettingsScreen(runtime, teams)
                            }
                        },
                        onFailure = { error -> PlaceholderScreen("队伍资源加载失败", error.message.orEmpty()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OwnTeamCaptureScreen(activity: MainActivity) {
    val message by CaptureUiState.message
    val running by CaptureUiState.running
    val savedFile by CaptureUiState.lastSavedFile
    val overlayAllowed = Settings.canDrawOverlays(activity)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("悬浮截图识别", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        StatusCard(message, running)
        SectionCard("1. 权限与会话") {
            Text(if (overlayAllowed) "悬浮窗权限：已授予" else "悬浮窗权限：尚未授予")
            if (!overlayAllowed) {
                Button(onClick = activity::requestOverlayPermission) { Text("授予悬浮窗权限") }
            }
            Button(onClick = activity::startOwnTeamCapture, enabled = !running) {
                Text("授权屏幕截图并启动悬浮按钮")
            }
            OutlinedButton(onClick = { OverlayCaptureService.stop(activity) }, enabled = running) {
                Text("结束悬浮识别")
            }
        }
        SectionCard("2. 在照片应用中识别") {
            Text("OCR：依次全屏打开同一我方队伍的招式/道具页和能力值页，每张图选择“识别屏幕上的队伍”。")
            Text("双方队伍 ROI：在 3392×2400 横屏队伍预览页选择“识别当前屏幕上的双方队伍”，一次生成双方各 6 个 Top-3 候选。")
            Text("预览识别完成后会直接进入本局确认；确认敌方 6 只后即可打开悬浮实战伤害面板。")
            Text("测试图片必须真正全屏，不能被照片应用的系统栏压缩。识别期间悬浮按钮会自动隐藏。")
            Text("App 会请求共享整个屏幕。ColorOS 若仍通知“应用内容已屏蔽”或“相册内容对方不可见”，请在通知面板点击“解除屏蔽”。")
        }
        SectionCard("3. 数据保存") {
            Text("自己的队伍：双图识别完成后先输入名称，再保存到 App 私有队伍库；可在首页和计算页直接使用。")
            Text("双方队伍预览：只保留当前一份临时结果，下一次识别会自动覆盖。")
            Text("本局确认：保存所选我方队伍、敌方阵容和当前计算条件；识别结果不会覆盖手动计算选择。")
            Text("识别结果不再自动写入下载目录。")
            Text("双方队伍候选默认未确认，不会直接进入伤害计算。")
            if (savedFile.isNotBlank()) Text("最近保存：$savedFile", color = Color(0xFF80CBC4))
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Text(
                "隐私说明：只在用户点击悬浮按钮后读取当前屏幕；识别与保存完全在本机完成，不上传、不修改游戏、不自动操作游戏。",
                Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun HomeScreen(teams: List<SavedTeam>, runtime: DamageEngineRuntime, openManual: (String?) -> Unit) {
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var previewTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var editTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf("") }
    var deleteError by remember { mutableStateOf("") }

    editTarget?.let { team ->
        TeamEditorScreen(
            team = team,
            onClose = { editTarget = null },
            onSaved = {
                CaptureUiState.teamLibraryRevision.value += 1
                editTarget = null
            },
        )
        return
    }

    renameTarget?.let { team ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名队伍") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it; renameError = "" },
                        label = { Text("队伍名称") },
                        singleLine = true,
                        isError = renameError.isNotBlank(),
                        supportingText = renameError.takeIf(String::isNotBlank)?.let { message ->
                            { Text(message) }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { TeamRepository.rename(context, team.id, renameText) }
                        .onSuccess {
                            CaptureUiState.teamLibraryRevision.value += 1
                            renameTarget = null
                        }
                        .onFailure { renameError = it.message ?: "重命名失败" }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    previewTarget?.let { team ->
        TeamPreviewDialog(team = team, onDismiss = { previewTarget = null })
    }

    deleteTarget?.let { team ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null; deleteError = "" },
            title = { Text("删除队伍") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除“${team.name}”吗？此操作无法撤销。")
                    if (deleteError.isNotBlank()) Text(deleteError, color = Color(0xFFFF8A80))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { TeamRepository.delete(context, team.id) }
                        .onSuccess {
                            CaptureUiState.teamLibraryRevision.value += 1
                            deleteTarget = null
                            deleteError = ""
                        }
                        .onFailure { deleteError = it.message ?: "删除失败" }
                }) { Text("确认删除", color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null; deleteError = "" }) { Text("取消") }
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Pokémon Champions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("离线伤害计算器", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        StatusCard(runtime.status, runtime.status.contains("已就绪"))
        Text("可直接自由选择任意宝可梦进行计算；已保存队伍也能一键带入精确配置。")
        Button(onClick = { openManual(null) }, enabled = runtime.status.contains("已就绪")) {
            Text("开始自由计算")
        }

        Text("我的队伍（${teams.size}）", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (teams.isEmpty()) {
            Text("还没有保存自己的队伍。请在“对局”页启动悬浮识别并完成两张队伍截图。")
        }
        teams.forEach { team ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(team.name, fontWeight = FontWeight.Bold)
                    Text(team.speciesSummary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (team.damageReady) "实际能力值完整" else "存在缺失项，计算前请手动补全",
                        color = if (team.damageReady) Color(0xFF80CBC4) else Color(0xFFFFB74D),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openManual(team.id) },
                            enabled = runtime.status.contains("已就绪"),
                        ) { Text("用于计算") }
                        TextButton(onClick = { previewTarget = team }) { Text("预览") }
                        TextButton(onClick = { editTarget = team }) { Text("手动调整") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            renameTarget = team
                            renameText = team.name
                            renameError = ""
                        }) { Text("重命名") }
                        TextButton(onClick = {
                            deleteTarget = team
                            deleteError = ""
                        }) { Text("删除", color = Color(0xFFFF8A80)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamPreviewDialog(team: SavedTeam, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(team.name) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(team.pokemon) { index, pokemon ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${index + 1}. ${pokemon.species.displayName}", fontWeight = FontWeight.Bold)
                            Text(pokemonConfigSummary(pokemon), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun TeamEditorScreen(team: SavedTeam, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { OpponentPresetRepository(context) }
    var selectedSlot by rememberSaveable(team.id) { mutableStateOf(0) }
    var draft by remember(team.id) { mutableStateOf(team.pokemon.first()) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(selectedSlot) {
        draft = team.pokemon[selectedSlot]
        errorMessage = ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose) { Text("返回") }
            Column {
                Text("手动调整队伍", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(team.name, color = MaterialTheme.colorScheme.primary)
            }
        }
        SimplePicker(
            label = "选择要调整的宝可梦",
            options = team.pokemon.indices.toList(),
            selected = selectedSlot,
            display = { index -> "${index + 1}. ${team.pokemon[index].species.displayName}" },
            onSelect = { selectedSlot = it },
        )
        PokemonConfigEditor(draft, repository) { draft = it }
        if (errorMessage.isNotBlank()) Text(errorMessage, color = Color(0xFFFF8A80))
        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !saving,
            onClick = {
                saving = true
                errorMessage = ""
                runCatching { TeamRepository.updatePokemon(context, team.id, selectedSlot, draft) }
                    .onSuccess { onSaved() }
                    .onFailure {
                        saving = false
                        errorMessage = it.message ?: "保存失败"
                    }
            },
        ) { Text(if (saving) "保存中…" else "保存本次调整") }
    }
}

private fun pokemonConfigSummary(pokemon: PokemonConfig): String = buildString {
    append("等级 ${pokemon.level} · 特性 ${pokemon.ability?.displayName ?: "未设置"} · 道具 ${pokemon.item?.displayName ?: "无"}")
    append("\n招式：${pokemon.moves.joinToString(" / ") { it.entity.displayName }.ifBlank { "未设置" }}")
    append("\n实际能力：${statsSummaryText(pokemon.actualStats)}")
    append("\n加点：${statsSummaryText(pokemon.statPoints)}")
}

private fun statsSummaryText(stats: StatFields): String = listOf(
    "生命" to stats.hp,
    "攻击" to stats.atk,
    "防御" to stats.def,
    "特攻" to stats.spa,
    "特防" to stats.spd,
    "速度" to stats.spe,
).joinToString("  ") { (label, value) -> "$label ${value.ifBlank { "?" }}" }

private data class CalculationUiResult(
    val direction: String,
    val attacker: String,
    val defender: String,
    val moves: List<MoveResultUi>,
    val warnings: List<String>,
)

private data class MoveResultUi(
    val name: String,
    val minPercent: Double,
    val maxPercent: Double,
    val minDamage: Int,
    val maxDamage: Int,
    val koText: String,
    val assumptions: List<String>,
)

private data class CalculatorPokemonState(
    val config: PokemonConfig,
    val sourceLabel: String,
    val profileId: String? = null,
)

private data class SavedPokemonOption(
    val teamName: String,
    val slot: Int,
    val config: PokemonConfig,
)

@Composable
private fun ManualCalculatorScreen(
    teams: List<SavedTeam>,
    runtime: DamageEngineRuntime,
    preferredOwnTeamId: String?,
) {
    val context = LocalContext.current
    val repository = remember(context) { OpponentPresetRepository(context) }
    val catalog = repository.speciesCatalog
    val preferredTeam = teams.firstOrNull { it.id == preferredOwnTeamId }
    val initialOwnTeam = preferredTeam ?: teams.firstOrNull()
    val initialOwnConfig = initialOwnTeam?.pokemon?.firstOrNull()
        ?: repository.configFor(catalog.firstOrNull { it.showdownId == "Pikachu" } ?: catalog.first())
    val initialOpponentSpecies = catalog.firstOrNull {
        it.showdownId == "Armarouge" && it.showdownId != initialOwnConfig.species.showdownId
    } ?: catalog.firstOrNull {
        it.showdownId != initialOwnConfig.species.showdownId
    } ?: catalog.first()
    val initialOpponentProfile = repository.recommendedProfile(initialOpponentSpecies)
    var direction by rememberSaveable { mutableStateOf("OWN_TO_OPPONENT") }
    var ownSelection by remember {
        mutableStateOf(
            CalculatorPokemonState(
                config = initialOwnConfig,
                sourceLabel = initialOwnTeam?.let { "已保存队伍：${it.name}" } ?: "图鉴配置假设",
                profileId = if (initialOwnTeam == null) repository.recommendedProfile(initialOwnConfig.species).profileId else null,
            )
        )
    }
    var opponentSelection by remember {
        mutableStateOf(
            CalculatorPokemonState(
                config = repository.configFor(initialOpponentSpecies, initialOpponentProfile),
                sourceLabel = "图鉴配置假设",
                profileId = initialOpponentProfile.profileId,
            )
        )
    }
    var selectedMove by rememberSaveable { mutableStateOf("") }
    var moveSortModeName by rememberSaveable { mutableStateOf(MoveSortMode.PINYIN.name) }
    var battleType by rememberSaveable { mutableStateOf("SINGLE") }
    var weather by rememberSaveable { mutableStateOf("NONE") }
    var terrain by rememberSaveable { mutableStateOf("NONE") }
    var ownReflect by rememberSaveable { mutableStateOf(false) }
    var ownLightScreen by rememberSaveable { mutableStateOf(false) }
    var opponentReflect by rememberSaveable { mutableStateOf(false) }
    var opponentLightScreen by rememberSaveable { mutableStateOf(false) }
    var critical by rememberSaveable { mutableStateOf(false) }
    var spread by rememberSaveable { mutableStateOf(false) }
    var showConditions by rememberSaveable { mutableStateOf(false) }
    var calculating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CalculationUiResult?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(preferredOwnTeamId, teams.map(SavedTeam::id)) {
        teams.firstOrNull { it.id == preferredOwnTeamId }?.pokemon?.firstOrNull()?.let { config ->
            ownSelection = CalculatorPokemonState(
                config = config,
                sourceLabel = "已保存队伍：${teams.first { it.id == preferredOwnTeamId }.name}",
            )
        }
    }

    val moveOptions = if (direction == "OWN_TO_OPPONENT") {
        ownSelection.config.moves
    } else {
        opponentSelection.config.moves
    }
    val moveSortMode = MoveSortMode.valueOf(moveSortModeName)
    val sortedMoveOptions = remember(moveOptions, moveSortMode) {
        sortMoves(moveOptions, moveSortMode, repository::moveTypeFor)
    }
    LaunchedEffect(direction, moveOptions.map { it.entity.showdownId }) {
        selectedMove = moveOptions.firstOrNull {
            it.entity.showdownId.equals(selectedMove, ignoreCase = true)
        }?.entity?.showdownId ?: moveOptions.firstOrNull { (it.basePower ?: 0) > 0 }?.entity?.showdownId
            ?: moveOptions.firstOrNull()?.entity?.showdownId.orEmpty()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("自由伤害计算", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("无需先建立两支队伍，可从完整图鉴自由选择双方宝可梦。", color = MaterialTheme.colorScheme.primary)
        StatusCard(runtime.status, runtime.status.contains("已就绪"))

        SectionCard("计算方向") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = direction == "OWN_TO_OPPONENT",
                    onClick = { direction = "OWN_TO_OPPONENT" },
                    label = { Text("我方输出") },
                )
                FilterChip(
                    selected = direction == "OPPONENT_TO_OWN",
                    onClick = { direction = "OPPONENT_TO_OWN" },
                    label = { Text("我方承伤") },
                )
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CalculatorPokemonCard(
                        title = "我方宝可梦",
                        state = ownSelection,
                        teams = teams,
                        repository = repository,
                        modifier = Modifier.weight(1f),
                        onChange = { ownSelection = it },
                    )
                    CalculatorPokemonCard(
                        title = "对方宝可梦",
                        state = opponentSelection,
                        teams = teams,
                        repository = repository,
                        modifier = Modifier.weight(1f),
                        onChange = { opponentSelection = it },
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CalculatorPokemonCard("我方宝可梦", ownSelection, teams, repository, Modifier.fillMaxWidth()) {
                        ownSelection = it
                    }
                    CalculatorPokemonCard("对方宝可梦", opponentSelection, teams, repository, Modifier.fillMaxWidth()) {
                        opponentSelection = it
                    }
                }
            }
        }

        SectionCard("招式与战场条件") {
            if (moveOptions.isEmpty()) {
                Text("当前攻击方还没有招式，请在宝可梦卡片的“详细调整”中添加。", color = Color(0xFFFFB74D))
            } else {
                MoveSortSelector(moveSortMode) { moveSortModeName = it.name }
                SimplePicker(
                    label = if (direction == "OWN_TO_OPPONENT") "我方招式" else "对方招式",
                    options = sortedMoveOptions.map { it.entity.showdownId },
                    selected = selectedMove,
                    display = { id ->
                        moveOptions.firstOrNull { it.entity.showdownId == id }
                            ?.let { "[${repository.moveTypeLabel(it)}] ${it.entity.displayName}" }
                            ?: "未选择"
                    },
                    onSelect = { selectedMove = it },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = battleType == "SINGLE",
                    onClick = { battleType = "SINGLE" },
                    label = { Text("单打") },
                )
                FilterChip(
                    selected = battleType == "DOUBLE",
                    onClick = { battleType = "DOUBLE" },
                    label = { Text("双打") },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    SimplePicker("天气", listOf("NONE", "Sun", "Rain", "Sand", "Snow"), weather, ::weatherLabel) {
                        weather = it
                    }
                }
                Box(Modifier.weight(1f)) {
                    SimplePicker(
                        "场地",
                        listOf("NONE", "Electric", "Grassy", "Psychic", "Misty"),
                        terrain,
                        ::terrainLabel,
                    ) { terrain = it }
                }
            }
            TextButton(onClick = { showConditions = !showConditions }) {
                Text(if (showConditions) "收起高级条件" else "能力 / 墙 / 状态")
            }
            if (showConditions) {
                CheckRow("我方反射壁", ownReflect) { ownReflect = it }
                CheckRow("我方光墙", ownLightScreen) { ownLightScreen = it }
                CheckRow("对方反射壁", opponentReflect) { opponentReflect = it }
                CheckRow("对方光墙", opponentLightScreen) { opponentLightScreen = it }
                CheckRow("会心", critical) { critical = it }
                CheckRow("按双打范围招式修正", spread) { spread = it }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = runtime.status.contains("已就绪") && !calculating && selectedMove.isNotBlank(),
            onClick = {
                errorMessage = ""
                result = null
                calculating = true
                val request = buildDamageRequest(
                    direction = direction,
                    own = PokemonEditorState.from(ownSelection.config),
                    opponent = PokemonEditorState.from(opponentSelection.config),
                    selectedMove = selectedMove,
                    battleType = battleType,
                    weather = weather,
                    terrain = terrain,
                    ownReflect = ownReflect,
                    ownLightScreen = ownLightScreen,
                    opponentReflect = opponentReflect,
                    opponentLightScreen = opponentLightScreen,
                    critical = critical,
                    spread = spread,
                )
                runtime.calculate(request) { calculation ->
                    calculating = false
                    calculation.fold(
                        onSuccess = { raw ->
                            runCatching { parseCalculation(raw) }
                                .onSuccess { result = it }
                                .onFailure { errorMessage = localizeEngineError(it.message) }
                        },
                        onFailure = { errorMessage = localizeEngineError(it.message) },
                    )
                }
            },
        ) { Text(if (calculating) "计算中…" else "计算伤害") }

        if (errorMessage.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF5B2525))) {
                Text(errorMessage, Modifier.padding(14.dp))
            }
        }
        result?.let { CalculationResultCard(it) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CalculatorPokemonCard(
    title: String,
    state: CalculatorPokemonState,
    teams: List<SavedTeam>,
    repository: OpponentPresetRepository,
    modifier: Modifier = Modifier,
    onChange: (CalculatorPokemonState) -> Unit,
) {
    var chooseSpecies by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable(title) { mutableStateOf(false) }
    val savedOptions = teams.flatMap { team ->
        team.pokemon.mapIndexed { index, config -> SavedPokemonOption(team.name, index, config) }
    }
    val profiles = repository.profilesFor(state.config.species)

    if (chooseSpecies) {
        EntitySearchDialog(
            title = "选择$title",
            entities = repository.speciesCatalog,
            onDismiss = { chooseSpecies = false },
            onSelect = { species ->
                val profile = repository.recommendedProfile(species)
                onChange(CalculatorPokemonState(repository.configFor(species, profile), "图鉴配置假设", profile.profileId))
                chooseSpecies = false
            },
        )
    }

    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { chooseSpecies = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.config.species.displayName, modifier = Modifier.weight(1f))
                Text("更换")
            }
            if (savedOptions.isNotEmpty()) {
                SimplePicker<SavedPokemonOption?>(
                    label = "从我的队伍带入（可选）",
                    options = listOf(null) + savedOptions,
                    selected = null,
                    display = { option -> option?.let { "${it.teamName} · ${it.slot + 1}. ${it.config.species.displayName}" } ?: "选择已保存配置" },
                    onSelect = { option ->
                        option?.let { onChange(CalculatorPokemonState(it.config, "已保存队伍：${it.teamName}")) }
                    },
                )
            }
            state.profileId?.let { profileId ->
                val selectedProfile = profiles.firstOrNull { it.profileId == profileId } ?: profiles.first()
                SimplePicker(
                    label = "配置假设",
                    options = profiles,
                    selected = selectedProfile,
                    display = OpponentPreset::profileName,
                    onSelect = { profile ->
                        onChange(CalculatorPokemonState(repository.configFor(state.config.species, profile), "图鉴配置假设", profile.profileId))
                    },
                )
            }
            Text(state.sourceLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Text(pokemonConfigSummary(state.config), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "收起详细调整" else "详细调整")
            }
            if (showDetails) {
                PokemonConfigEditor(state.config, repository) { config ->
                    onChange(CalculatorPokemonState(config, "手动配置"))
                }
            }
        }
    }
}

@Composable
private fun PokemonConfigEditor(
    state: PokemonConfig,
    repository: OpponentPresetRepository,
    onChange: (PokemonConfig) -> Unit,
) {
    var levelText by remember(state.species.showdownId, state.level) { mutableStateOf(state.level.toString()) }
    var chooseSpecies by remember { mutableStateOf(false) }
    var chooseItem by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<Int?>(null) }
    val abilities = (listOfNotNull(state.ability) + repository.abilitiesFor(state.species))
        .distinctBy { it.showdownId.lowercase() }
    val legalMoves = repository.movesFor(state.species)

    if (chooseSpecies) {
        EntitySearchDialog(
            title = "选择宝可梦",
            entities = repository.speciesCatalog,
            onDismiss = { chooseSpecies = false },
            onSelect = { species ->
                onChange(repository.configFor(species))
                chooseSpecies = false
            },
        )
    }
    if (chooseItem) {
        EntitySearchDialog(
            title = "选择道具",
            entities = repository.itemCatalog,
            onDismiss = { chooseItem = false },
            onSelect = { item -> onChange(state.copy(item = item)); chooseItem = false },
        )
    }
    moveTarget?.let { target ->
        MoveSearchDialog(
            title = if (target < state.moves.size) "更换招式" else "添加招式",
            moves = legalMoves,
            repository = repository,
            onDismiss = { moveTarget = null },
            onSelect = { move ->
                val updated = state.moves.toMutableList()
                if (target < updated.size) updated[target] = move else updated.add(move)
                onChange(state.copy(moves = updated.distinctBy { it.entity.showdownId }.take(4)))
                moveTarget = null
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { chooseSpecies = true }, modifier = Modifier.fillMaxWidth()) {
            Text("宝可梦：${state.species.displayName}", modifier = Modifier.weight(1f))
            Text("选择")
        }
        OutlinedTextField(
            value = levelText,
            onValueChange = { value ->
                levelText = value.filter(Char::isDigit).take(3)
                levelText.toIntOrNull()?.let { onChange(state.copy(level = it.coerceIn(1, 100))) }
            },
            label = { Text("等级") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SimplePicker<EntityValue?>(
            label = "特性",
            options = listOf(null) + abilities,
            selected = state.ability,
            display = { it?.displayName ?: "未设置" },
            onSelect = { onChange(state.copy(ability = it)) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseItem = true }, modifier = Modifier.weight(1f)) {
                Text("道具：${state.item?.displayName ?: "无"}")
            }
            if (state.item != null) TextButton(onClick = { onChange(state.copy(item = null)) }) { Text("清除") }
        }
        Text("招式", fontWeight = FontWeight.SemiBold)
        state.moves.forEachIndexed { index, move ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { moveTarget = index }, modifier = Modifier.weight(1f)) {
                    Text("${index + 1}. ${move.entity.displayName}")
                }
                TextButton(onClick = {
                    onChange(state.copy(moves = state.moves.toMutableList().apply { removeAt(index) }))
                }) { Text("移除") }
            }
        }
        if (state.moves.size < 4) {
            OutlinedButton(onClick = { moveTarget = state.moves.size }, modifier = Modifier.fillMaxWidth()) {
                Text("添加招式")
            }
        }
        Text("实际能力值", fontWeight = FontWeight.SemiBold)
        StatEditor(state.actualStats) { onChange(state.copy(actualStats = it)) }
        Text("能力加点（可留空）", fontWeight = FontWeight.SemiBold)
        StatEditor(state.statPoints) { onChange(state.copy(statPoints = it)) }
    }
}

@Composable
private fun MoveSearchDialog(
    title: String,
    moves: List<MoveValue>,
    repository: OpponentPresetRepository,
    onDismiss: () -> Unit,
    onSelect: (MoveValue) -> Unit,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    var sortModeName by rememberSaveable("$title-sort") { mutableStateOf(MoveSortMode.PINYIN.name) }
    val normalized = query.trim().lowercase()
    val sortMode = MoveSortMode.valueOf(sortModeName)
    val filtered = remember(moves, normalized, sortMode) {
        val matches = if (normalized.isBlank()) moves else moves.filter {
            it.entity.displayName.lowercase().contains(normalized) ||
                it.entity.showdownId.lowercase().contains(normalized)
        }
        sortMoves(matches, sortMode, repository::moveTypeFor)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索招式中文名") },
                    supportingText = { Text("也支持英文名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MoveSortSelector(sortMode) { sortModeName = it.name }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(filtered.take(100)) { _, move ->
                        TextButton(onClick = { onSelect(move) }, modifier = Modifier.fillMaxWidth()) {
                            Text("[${repository.moveTypeLabel(move)}] ${move.entity.displayName}", modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (filtered.isEmpty()) Text("没有找到匹配项", color = Color(0xFFFFB74D))
                else if (filtered.size > 100) Text("结果较多，请继续输入关键词。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MoveSortSelector(selected: MoveSortMode, onSelect: (MoveSortMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("排序方式", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoveSortMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
    }
}

@Composable
private fun EntitySearchDialog(
    title: String,
    entities: List<EntityValue>,
    onDismiss: () -> Unit,
    onSelect: (EntityValue) -> Unit,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val filtered = remember(entities, normalized) {
        if (normalized.isBlank()) entities
        else entities.filter {
            it.displayName.lowercase().contains(normalized) || it.showdownId.lowercase().contains(normalized)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索中文名") },
                    supportingText = { Text("也支持英文名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(filtered.take(100)) { _, entity ->
                        TextButton(onClick = { onSelect(entity) }, modifier = Modifier.fillMaxWidth()) {
                            Text(entity.displayName, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (filtered.isEmpty()) Text("没有找到匹配项", color = Color(0xFFFFB74D))
                else if (filtered.size > 100) Text("结果较多，请继续输入关键词。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StatEditor(value: StatFields, onChange: (StatFields) -> Unit) {
    StatRow(
        listOf("生命" to value.hp, "攻击" to value.atk, "防御" to value.def),
    ) { index, text ->
        onChange(
            when (index) {
                0 -> value.copy(hp = text)
                1 -> value.copy(atk = text)
                else -> value.copy(def = text)
            }
        )
    }
    StatRow(
        listOf("特攻" to value.spa, "特防" to value.spd, "速度" to value.spe),
    ) { index, text ->
        onChange(
            when (index) {
                0 -> value.copy(spa = text)
                1 -> value.copy(spd = text)
                else -> value.copy(spe = text)
            }
        )
    }
}

@Composable
private fun StatRow(values: List<Pair<String, String>>, onChange: (Int, String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, (label, value) ->
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(index, it.filter(Char::isDigit).take(3)) },
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CalculationResultCard(result: CalculationUiResult) {
    SectionCard(if (result.direction == "OWN_TO_OPPONENT") "结果：我方输出" else "结果：我方承伤") {
        Text("${result.attacker}  →  ${result.defender}", fontWeight = FontWeight.Bold)
        result.moves.forEachIndexed { index, move ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(move.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${formatPercent(move.minPercent)} – ${formatPercent(move.maxPercent)}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("${move.minDamage} – ${move.maxDamage} 点生命值")
                Text(move.koText)
                move.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (result.moves.isEmpty()) Text("没有可计算的招式，请重新选择。", color = Color(0xFFFFB74D))
        if (result.warnings.isNotEmpty()) {
            HorizontalDivider()
            Text("计算提示", fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
            result.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun parseCalculation(raw: String): CalculationUiResult {
    val envelope = JSONObject(raw)
    if (!envelope.optBoolean("ok")) {
        val error = envelope.optJSONObject("error")
        throw IllegalArgumentException(localizeEngineError(error?.optString("message")))
    }
    val result = envelope.getJSONObject("result")
    val moveResults = result.getJSONArray("moveResults")
    val warnings = result.getJSONArray("warnings")
    return CalculationUiResult(
        direction = result.getString("calculationDirection"),
        attacker = result.getJSONObject("attackerSummary").getString("speciesName"),
        defender = result.getJSONObject("defenderIdentity").getJSONObject("species")
            .optString("displayName", "未知目标"),
        moves = (0 until moveResults.length()).map { index ->
            val move = moveResults.getJSONObject(index)
            val range = move.getJSONObject("selectedProfileRange")
            val assumptions = move.getJSONArray("assumptions")
            MoveResultUi(
                name = move.getString("moveName"),
                minPercent = range.getDouble("minPercent"),
                maxPercent = range.getDouble("maxPercent"),
                minDamage = range.getInt("minDamage"),
                maxDamage = range.getInt("maxDamage"),
                koText = localizeKoText(move.getJSONObject("koSummary").optString("text", "")),
                assumptions = (0 until assumptions.length())
                    .map(assumptions::getString)
                    .map(::localizeAssumption),
            )
        },
        warnings = (0 until warnings.length()).map { index ->
            val warning = warnings.getJSONObject(index)
            localizeWarning(warning.getString("code"), warning.getString("message"))
        }.distinct(),
    )
}

@Composable
private fun <T> SimplePicker(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(display(selected), modifier = Modifier.weight(1f))
                Text("▼")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(display(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatusCard(message: String, ready: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) Color(0xFF153C36) else Color(0xFF3B3420),
        ),
    ) {
        Text(message, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun SettingsScreen(runtime: DamageEngineRuntime, teams: List<SavedTeam>) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置与诊断", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        StatusCard(runtime.status, runtime.status.contains("已就绪"))
        Text("队伍资产：${teams.size} 份")
        Text("引擎信息：${runtime.engineInfo.ifBlank { "尚未读取" }}", style = MaterialTheme.typography.bodySmall)
        Text("全部计算在本地完成；App 未申请网络权限。", color = Color(0xFF80CBC4))
    }
}

@Composable
private fun PlaceholderScreen(title: String, message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(message)
    }
}

private fun weatherLabel(value: String) = when (value) {
    "NONE" -> "无天气"
    "Sun" -> "晴天"
    "Rain" -> "雨天"
    "Sand" -> "沙暴"
    "Snow" -> "下雪"
    else -> value
}

private fun terrainLabel(value: String) = when (value) {
    "NONE" -> "无场地"
    "Electric" -> "电气场地"
    "Grassy" -> "青草场地"
    "Psychic" -> "精神场地"
    "Misty" -> "薄雾场地"
    else -> value
}

private fun formatPercent(value: Double): String = "%.1f%%".format(value)

private fun localizeKoText(text: String): String {
    if (text == "No direct damage.") return "无直接伤害"
    return text
        .replace(Regex("guaranteed OHKO", RegexOption.IGNORE_CASE), "必定一击击倒")
        .replace(Regex("possible OHKO", RegexOption.IGNORE_CASE), "可能一击击倒")
        .replace(Regex("(\\d+(?:\\.\\d+)?)% chance to OHKO", RegexOption.IGNORE_CASE), "$1% 概率一击击倒")
        .replace(Regex("guaranteed (\\d+)HKO", RegexOption.IGNORE_CASE), "必定 $1 次击倒")
        .replace(Regex("possible (\\d+)HKO", RegexOption.IGNORE_CASE), "可能 $1 次击倒")
}

private fun localizeAssumption(text: String): String = text
    .replace("Defender profile:", "防守配置：")
    .replace("Attacker profile:", "攻击配置：")
    .replace("Defender ability is unspecified.", "防守方特性未指定。")
    .replace("Defender item is unspecified.", "防守方道具未指定。")
    .replace("Defender Stat Points use calculator defaults.", "防守方能力加点使用计算器默认值。")

private fun localizeEngineError(message: String?): String {
    val raw = message.orEmpty()
    return when {
        raw.isBlank() -> "计算失败，请检查双方配置和招式。"
        raw.contains("damage[damage.length - 1] === 0") -> "该招式对目标无直接伤害。"
        raw.contains("Unknown species", ignoreCase = true) -> "找不到所选宝可梦或形态，请重新选择。"
        raw.contains("Unknown move", ignoreCase = true) -> "找不到所选招式，请重新选择。"
        raw.any { it.code > 127 } -> raw
        else -> "计算失败，请检查双方配置和招式。"
    }
}

private fun localizeWarning(code: String, message: String): String = when (code) {
    "ACTUAL_STATS_APPROXIMATED" -> "已使用输入的实际能力值进行本次计算。"
    "LEGAL_MOVE_POOL_MISSING" -> "缺少对方合法招式池。"
    "NO_OPPONENT_MOVE_SELECTED" -> "请选择一个对方招式。"
    "ILLEGAL_OPPONENT_MOVE" -> "所选招式不在当前对方合法招式池中。"
    "MOVE_NOT_FOUND" -> "找不到所选招式，请重新选择。"
    "SPECIES_NOT_FOUND" -> "找不到所选宝可梦或形态，请重新选择。"
    "NO_ATTACKER_MOVES" -> "攻击方还没有可用于计算的招式。"
    "NO_ATTACKER_PROFILE", "NO_SELECTED_ATTACKER_PROFILE" -> "没有可用的攻击方配置假设。"
    "NO_DEFENDER_PROFILES", "NO_SELECTED_PROFILE" -> "没有可用的防守方配置假设。"
    "CUSTOM_FLAGS_NOT_APPLIED" -> "部分自定义高级条件暂未应用。"
    "EMPTY_ENVELOPE" -> "没有勾选用于范围计算的配置，已改用全部配置。"
    else -> message
}
