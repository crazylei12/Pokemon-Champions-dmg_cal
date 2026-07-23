package com.crazylei12.pokemonchampionsassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class BattleDirectOverlayTest {
    @Test
    fun `every hud element stays inside safe regions at different resolutions`() {
        val regions = listOf(
            OverlayBounds(0, 0, 3392, 2400),
            OverlayBounds(0, 80, 2400, 1080),
            OverlayBounds(48, 96, 2512, 1536),
            OverlayBounds(1236, 80, 2400, 1080),
        )

        regions.forEach { region ->
            BattleDirectHudLayout.anchors.values.forEach { anchor ->
                val bounds = resolveBattleDirectHudBounds(region, anchor, desiredWidth = 300, desiredHeight = 96)
                assertTrue(bounds.left >= region.left)
                assertTrue(bounds.top >= region.top)
                assertTrue(bounds.right <= region.right)
                assertTrue(bounds.bottom <= region.bottom)
            }
        }
    }

    @Test
    fun `hud toggle follows the top dock anchor inside the current safe region`() {
        val region = OverlayBounds(1236, 80, 2400, 1080)
        val bounds = resolveBattleDirectHudBounds(
            region,
            requireNotNull(BattleDirectHudLayout.anchors[BattleDirectHudElement.TOGGLE]),
            desiredWidth = 172,
            desiredHeight = 60,
        )
        val expectedCenter = region.left + (region.width * 0.465f).roundToInt()

        assertTrue(abs((bounds.left + bounds.width / 2) - expectedCenter) <= 1)
        assertEquals(region.top + (region.height * 0.015f).roundToInt(), bounds.top)
    }

    @Test
    fun `opponent assumption no longer overlaps the right picker or details button`() {
        val region = OverlayBounds(0, 0, 2400, 1080)
        val assumption = bounds(region, BattleDirectHudElement.ASSUMPTION, width = 336, height = 96)
        val rightPicker = bounds(region, BattleDirectHudElement.OPPONENT_RIGHT, width = 510, height = 114)
        val details = bounds(region, BattleDirectHudElement.DETAIL, width = 192, height = 102)

        assertEquals(0L, assumption.intersectionArea(rightPicker))
        assertEquals(0L, assumption.intersectionArea(details))
        assertEquals(
            listOf("战场状态", "对手配置", "速度线"),
            BattleDirectHudSection.values().map(BattleDirectHudSection::label),
        )
    }

    @Test
    fun `top hud controls form a clear dock before the opponent cards`() {
        val region = OverlayBounds(0, 0, 2772, 1240)
        val edit = bounds(region, BattleDirectHudElement.EDIT, width = 192, height = 90)
        val rematch = bounds(region, BattleDirectHudElement.REMATCH, width = 192, height = 90)
        val toggle = bounds(region, BattleDirectHudElement.TOGGLE, width = 252, height = 90)
        val recording = bounds(region, BattleDirectHudElement.RECORDING, width = 210, height = 90)
        val format = bounds(region, BattleDirectHudElement.FORMAT, width = 192, height = 90)
        val ownRecognition = bounds(region, BattleDirectHudElement.OWN_RECOGNITION, width = 252, height = 90)
        val opponentLeft = bounds(region, BattleDirectHudElement.OPPONENT_LEFT, width = 532, height = 114)

        assertTrue(edit.right <= rematch.left)
        assertTrue(rematch.right <= toggle.left)
        assertTrue(toggle.right <= recording.left)
        assertTrue(recording.right <= format.left)
        assertTrue(format.bottom <= opponentLeft.top)
        assertTrue(ownRecognition.top >= toggle.bottom)
        assertEquals("录像", BattleDirectHudRecordingState.IDLE.buttonLabel)
        assertEquals("停止录像", BattleDirectHudRecordingState.RUNNING.buttonLabel)
        assertTrue(BattleDirectHudRecordingState.RUNNING.canToggle)
        assertTrue(!BattleDirectHudRecordingState.PREPARING.canToggle)
    }

    @Test
    fun `custom placement scales with a different safe region`() {
        val source = OverlayBounds(100, 50, 1100, 550)
        val placement = battleDirectHudPlacementFromBounds(
            source,
            OverlayBounds(300, 150, 600, 350),
        )
        val resolved = resolveBattleDirectHudPlacement(
            OverlayBounds(20, 30, 2020, 1030),
            placement,
            minimumWidth = 100,
            minimumHeight = 80,
        )

        assertEquals(BattleDirectHudPlacement(0.2f, 0.2f, 0.3f, 0.4f), placement)
        assertEquals(OverlayBounds(420, 230, 1020, 630), resolved)
    }

    @Test
    fun `custom placement and size are clamped inside the safe region`() {
        val region = OverlayBounds(100, 200, 1100, 800)
        val oversizedEdgePlacement = BattleDirectHudPlacement(
            xFraction = 0.98f,
            yFraction = 0.99f,
            widthFraction = 0.4f,
            heightFraction = 0.5f,
        )
        val tinyEdgePlacement = oversizedEdgePlacement.copy(widthFraction = 0.001f, heightFraction = 0.001f)

        assertEquals(
            OverlayBounds(700, 500, 1100, 800),
            resolveBattleDirectHudPlacement(region, oversizedEdgePlacement, 100, 80),
        )
        assertEquals(
            OverlayBounds(1000, 720, 1100, 800),
            resolveBattleDirectHudPlacement(region, tinyEdgePlacement, 100, 80),
        )
    }

    @Test
    fun `saved layout round trips while excluding the fixed edit control`() {
        val placements = mapOf(
            BattleDirectHudElement.SPEED to BattleDirectHudPlacement(0.1f, 0.2f, 0.3f, 0.4f),
            BattleDirectHudElement.DAMAGE to BattleDirectHudPlacement(0.2f, 0.6f, 0.5f, 0.1f),
            BattleDirectHudElement.EDIT to BattleDirectHudPlacement(0f, 0f, 1f, 1f),
        )

        val restored = decodeBattleDirectHudPlacements(encodeBattleDirectHudPlacements(placements))

        assertEquals(placements - BattleDirectHudElement.EDIT, restored)
        assertTrue(decodeBattleDirectHudPlacements("not json").isEmpty())
        assertEquals("landscape", battleDirectHudLayoutProfileKey(OverlayBounds(0, 0, 1200, 700)))
        assertEquals("portrait", battleDirectHudLayoutProfileKey(OverlayBounds(0, 0, 700, 1200)))
    }

    @Test
    fun `display slots stay distinct and swapping preserves both active positions`() {
        assertEquals(listOf(5, 0), normalizeBattleDirectHudSlots(listOf(5, 5), 6))
        assertEquals(listOf(1, 0), replaceBattleDirectHudSlot(listOf(0, 1), displayIndex = 0, teamSlot = 1))
        assertEquals(listOf(0, 4), replaceBattleDirectHudSlot(listOf(0, 1), displayIndex = 1, teamSlot = 4))
        assertEquals(listOf(4, 0), includeBattleDirectHudSlot(listOf(0, 1), selectedSlot = 4, teamSize = 6))
        assertEquals(listOf(1, 0), prioritizeBattleDirectHudSlot(listOf(0, 1), selectedSlot = 1, teamSize = 6))
    }

    @Test
    fun `single and double formats expose the correct battle positions`() {
        val single = battleDirectHudPickerSpecs("SINGLE")
        val double = battleDirectHudPickerSpecs("DOUBLE")

        assertEquals(
            listOf(BattleDirectHudElement.OPPONENT_RIGHT, BattleDirectHudElement.OWN_LEFT),
            single.map(BattleDirectHudPickerSpec::element),
        )
        assertEquals(listOf(0, 0), single.map(BattleDirectHudPickerSpec::displayIndex))
        assertEquals(listOf(SpeedSide.OPPONENT, SpeedSide.OWN), single.map(BattleDirectHudPickerSpec::side))
        assertEquals(
            listOf(
                BattleDirectHudElement.OPPONENT_LEFT,
                BattleDirectHudElement.OPPONENT_RIGHT,
                BattleDirectHudElement.OWN_LEFT,
                BattleDirectHudElement.OWN_RIGHT,
            ),
            double.map(BattleDirectHudPickerSpec::element),
        )
        assertEquals(1, battleDirectHudSlotsPerSide("SINGLE"))
        assertEquals(2, battleDirectHudSlotsPerSide("DOUBLE"))
    }

    @Test
    fun `vertical speed order contains only the selected four normal-priority pokemon`() {
        val actions = listOf(
            action(SpeedSide.OWN, 0, "我一", 180..180),
            action(SpeedSide.OPPONENT, 1, "对二", 150..190),
            action(SpeedSide.OWN, 4, "我五", 120..120),
            action(SpeedSide.OPPONENT, 3, "对四", 80..140),
            action(SpeedSide.OWN, 2, "未上场", 200..200),
            action(SpeedSide.OWN, 0, "先制招式", 180..180, moveName = "突袭", priority = 1),
        )

        val visible = activeBattleDirectSpeedActions(actions, ownSlots = listOf(0, 4), opponentSlots = listOf(1, 3))

        assertEquals(listOf("我一", "对二", "我五", "对四"), visible.map(SpeedLineAction::pokemonName))
        assertTrue(battleDirectSpeedRangesOverlap(180..180, 150..190))
        assertTrue(!battleDirectSpeedRangesOverlap(120..120, 80..110))
    }

    @Test
    fun `four-move damage output follows configured move order and marks status or missing slots`() {
        val raw = JSONObject().put("ok", true).put("result", JSONObject().put(
            "moveResults",
            JSONArray()
                .put(result("move.protect", "Status", 0.0, 0.0))
                .put(result("move.thunderbolt", "Special", 42.04, 50.06)),
        )).toString()
        val moves = listOf(move("move.thunderbolt", "Thunderbolt"), move("move.protect", "Protect"), move("move.missing", "Missing"))

        assertEquals(
            listOf("1 42.0–50.1%", "2 —", "3 ?", "4 —"),
            parseBattleDirectDamageValues(raw, moves),
        )
    }

    @Test
    fun `routine hud state changes reuse existing overlay windows`() {
        val base = hudModel()

        assertFalse(shouldRebuildBattleDirectHudWindows(base, base.copy(selectedOwnSlot = 1), false, true))
        assertFalse(shouldRebuildBattleDirectHudWindows(base, base.copy(selectedAssumptionId = "bulk"), false, true))
        assertTrue(shouldRebuildBattleDirectHudWindows(base, base.copy(battleType = "SINGLE"), false, true))
        assertTrue(shouldRebuildBattleDirectHudWindows(base, base.copy(hudVisible = false), false, true))
        assertTrue(shouldRebuildBattleDirectHudWindows(base, base, true, true))
        assertTrue(shouldRebuildBattleDirectHudWindows(base, base, false, false))
    }

    @Test
    fun `damage cache ignores request ids but keeps calculation inputs`() {
        val first = JSONObject().put("requestId", "one").put("attacker", "a").put("defender", "b").toString()
        val sameInputs = JSONObject().put("requestId", "two").put("attacker", "a").put("defender", "b").toString()
        val changedTarget = JSONObject().put("requestId", "three").put("attacker", "a").put("defender", "c").toString()

        assertEquals(battleDamageCacheKey(first), battleDamageCacheKey(sameInputs))
        assertNotEquals(battleDamageCacheKey(first), battleDamageCacheKey(changedTarget))
    }

    private fun hudModel() = BattleDirectHudModel(
        battleType = "DOUBLE",
        ownTeamNames = listOf("我一", "我二"),
        opponentTeamNames = listOf("对一", "对二"),
        ownSlots = listOf(0, 1),
        opponentSlots = listOf(0, 1),
        selectedOwnSlot = 0,
        selectedOpponentSlot = 0,
        speedActions = emptyList(),
        trickRoom = false,
        statusText = "状态：默认",
        assumptionOptions = listOf(BattleDirectHudPresetOption("default", "默认")),
        selectedAssumptionId = "default",
    )

    private fun action(
        side: SpeedSide,
        slot: Int,
        name: String,
        speed: IntRange,
        moveName: String? = null,
        priority: Int = 0,
    ) = SpeedLineAction(side, slot, name, moveName, priority, speed, exactBaseSpeed = speed.first == speed.last)

    private fun move(canonicalId: String, showdownId: String) = MoveValue(
        EntityValue(canonicalId, showdownId, showdownId, "move"),
    )

    private fun result(moveId: String, category: String, minimum: Double, maximum: Double) = JSONObject()
        .put("moveId", moveId)
        .put("moveCategory", category)
        .put("selectedProfileRange", JSONObject().put("minPercent", minimum).put("maxPercent", maximum))

    private fun bounds(
        region: OverlayBounds,
        element: BattleDirectHudElement,
        width: Int,
        height: Int,
    ) = resolveBattleDirectHudBounds(
        region,
        requireNotNull(BattleDirectHudLayout.anchors[element]),
        desiredWidth = width,
        desiredHeight = height,
    )
}
