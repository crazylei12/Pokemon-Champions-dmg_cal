package com.crazylei12.pokemonchampionsassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun `hud toggle is centered at the top of the current safe region`() {
        val region = OverlayBounds(1236, 80, 2400, 1080)
        val bounds = resolveBattleDirectHudBounds(
            region,
            requireNotNull(BattleDirectHudLayout.anchors[BattleDirectHudElement.TOGGLE]),
            desiredWidth = 172,
            desiredHeight = 60,
        )
        val expectedCenter = region.left + (region.width * 0.5f).roundToInt()

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
    fun `display slots stay distinct and swapping preserves both active positions`() {
        assertEquals(listOf(5, 0), normalizeBattleDirectHudSlots(listOf(5, 5), 6))
        assertEquals(listOf(1, 0), replaceBattleDirectHudSlot(listOf(0, 1), displayIndex = 0, teamSlot = 1))
        assertEquals(listOf(0, 4), replaceBattleDirectHudSlot(listOf(0, 1), displayIndex = 1, teamSlot = 4))
        assertEquals(listOf(4, 0), includeBattleDirectHudSlot(listOf(0, 1), selectedSlot = 4, teamSize = 6))
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
