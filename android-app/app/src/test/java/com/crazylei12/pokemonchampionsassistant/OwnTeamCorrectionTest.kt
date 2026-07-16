package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTeamCorrectionTest {
    @Test
    fun actualStatInputsExposeClearPlaceholderLabels() {
        assertEquals(
            listOf("HP", "攻击", "防御", "特攻", "特防", "速度"),
            OWN_TEAM_ACTUAL_STAT_INPUT_ROWS.flatten().map { it.second },
        )
    }

    @Test
    fun moveItemPageAlwaysStartsANewDraftAndDropsPreviousStats() {
        val oldMove = movePage()
        val oldStats = statsPage()
        val newMove = movePage(recognized = 40).copy(capturedAt = "new-team")

        val updated = updateOwnTeamDraft(oldMove, oldStats, newMove)

        assertTrue(updated.restarted)
        assertEquals("new-team", updated.moveItemPage?.capturedAt)
        assertEquals(null, updated.statsPage)
        assertEquals(OwnTeamImportNextStep.CAPTURE_STATS, nextOwnTeamImportStep(updated.moveItemPage, updated.statsPage))
    }

    @Test
    fun moveItemEvidenceWinsOverStrayNumbersWhenClassifyingThePage() {
        assertEquals(OwnTeamPageType.MOVE_ITEM, classifyOwnTeamPage(statEvidence = 8, moveItemEvidence = 6))
        assertEquals(OwnTeamPageType.STATS, classifyOwnTeamPage(statEvidence = 8, moveItemEvidence = 2))
    }

    @Test
    fun partialMovePageContinuesToStatsThenRequiresPreciseManualCorrection() {
        val move = movePage(recognized = 41).copy(
            slots = movePage().slots.toMutableList().apply {
                set(
                    5,
                    get(5).copy(
                        moves = get(5).moves.drop(1),
                        moveSlotIndexes = listOf(1, 2, 3),
                    ),
                )
            },
        )

        assertEquals(OwnTeamImportNextStep.CAPTURE_STATS, nextOwnTeamImportStep(move, null))
        assertEquals(
            OwnTeamImportNextStep.MANUAL_CORRECTION,
            nextOwnTeamImportStep(move, statsPage()),
        )
        val correction = buildOwnTeamCorrectionDraft(move, statsPage())
        assertEquals(listOf("招式 1"), correction.slots[5].unresolvedFields())
    }

    @Test
    fun completeMatchingPagesStillRequireManualConfirmation() {
        val correction = buildOwnTeamCorrectionDraft(movePage(), statsPage())

        assertTrue(correction.slots.all(OwnTeamCorrectionSlot::isComplete))
        assertEquals(
            OwnTeamImportNextStep.MANUAL_CORRECTION,
            nextOwnTeamImportStep(movePage(), statsPage()),
        )
    }

    @Test
    fun crossPageSpeciesConflictRequiresManualConfirmation() {
        val stats = statsPage().copy(
            slots = statsPage().slots.toMutableList().apply {
                set(2, get(2).copy(species = entity("species", "different")))
            },
        )

        assertEquals(
            OwnTeamImportNextStep.MANUAL_CORRECTION,
            nextOwnTeamImportStep(movePage(), stats),
        )
        val correction = buildOwnTeamCorrectionDraft(movePage(), stats)
        assertFalse(correction.slots[2].speciesConfirmed)
    }

    @Test
    fun correctionDraftPreservesRecognizedFieldsAndListsOnlyMissingValues() {
        val move = movePage(recognized = 38).copy(
            slots = movePage().slots.toMutableList().apply {
                set(0, get(0).copy(ability = null, item = null, moves = get(0).moves.take(3)))
            },
        )
        val stats = statsPage(recognized = 41).copy(
            slots = statsPage().slots.toMutableList().apply {
                set(0, get(0).copy(actualStats = get(0).actualStats - "spe"))
            },
        )

        val slot = buildOwnTeamCorrectionDraft(move, stats).slots.first()

        assertEquals("species0", slot.species?.showdownId)
        assertEquals(3, slot.moves.size)
        assertEquals("100", slot.actualStats.hp)
        assertTrue(slot.unresolvedFields().contains("特性"))
        assertTrue(slot.unresolvedFields().contains("道具（或确认无道具）"))
        assertTrue(slot.unresolvedFields().contains("招式 4"))
        assertTrue(slot.reminders().isEmpty())
        assertTrue(slot.unresolvedFields().any { it.contains("速度") })
    }

    @Test
    fun explicitlyConfirmedNoItemIsValid() {
        val slot = OwnTeamCorrectionSlot(
            slotIndex = 0,
            species = entity("species", "species0").asValue(),
            speciesConfirmed = true,
            ability = entity("ability", "ability0").asValue(),
            item = null,
            itemResolved = true,
            moves = (0 until 4).map { MoveValue(entity("move", "move$it").asValue()) },
            actualStats = StatFields("100", "101", "102", "103", "104", "105"),
        )

        assertTrue(slot.isComplete())
        assertEquals(null, slot.toPokemonConfig().item)
    }

    @Test
    fun duplicateMovesStillRequireCorrection() {
        val repeated = MoveValue(entity("move", "same").asValue())
        val slot = OwnTeamCorrectionSlot(
            slotIndex = 0,
            species = entity("species", "species0").asValue(),
            speciesConfirmed = true,
            ability = entity("ability", "ability0").asValue(),
            item = entity("item", "item0").asValue(),
            itemResolved = true,
            moves = listOf(repeated, repeated, repeated, repeated),
            actualStats = StatFields("100", "101", "102", "103", "104", "105"),
        )

        assertFalse(slot.isComplete())
        assertTrue(slot.unresolvedFields().contains("招式重复"))
    }

    @Test
    fun allFourDistinctMovesAreRequiredBeforeSaving() {
        val base = OwnTeamCorrectionSlot(
            slotIndex = 0,
            species = entity("species", "species0").asValue(),
            speciesConfirmed = true,
            ability = entity("ability", "ability0").asValue(),
            item = null,
            itemResolved = true,
            moves = listOf(MoveValue(entity("move", "move0").asValue())),
            actualStats = StatFields("100", "101", "102", "103", "104", "105"),
        )

        assertFalse(base.isComplete())
        assertTrue(base.unresolvedFields().containsAll(listOf("招式 2", "招式 3", "招式 4")))
        assertTrue(base.copy(moves = (0 until 4).map { MoveValue(entity("move", "move$it").asValue()) }).isComplete())
        assertFalse(base.copy(moves = emptyList()).isComplete())
        assertTrue(base.copy(moves = emptyList()).unresolvedFields().containsAll(listOf("招式 1", "招式 2", "招式 3", "招式 4")))
    }

    private fun movePage(recognized: Int = 42) = RecognizedOwnTeamPage(
        type = OwnTeamPageType.MOVE_ITEM,
        width = 2400,
        height = 1080,
        slots = (0 until 6).map { slot ->
            RecognizedSlot(
                slotIndex = slot,
                species = entity("species", "species$slot"),
                ability = entity("ability", "ability$slot"),
                item = entity("item", "item$slot"),
                moves = (0 until 4).map { entity("move", "move${slot}_$it") },
            )
        },
        recognized = recognized,
        total = 42,
    )

    private fun statsPage(recognized: Int = 42) = RecognizedOwnTeamPage(
        type = OwnTeamPageType.STATS,
        width = 2400,
        height = 1080,
        slots = (0 until 6).map { slot ->
            RecognizedSlot(
                slotIndex = slot,
                species = entity("species", "species$slot"),
                actualStats = mapOf(
                    "hp" to 100,
                    "atk" to 101,
                    "def" to 102,
                    "spa" to 103,
                    "spd" to 104,
                    "spe" to 105,
                ),
            )
        },
        recognized = recognized,
        total = 42,
    )

    private fun entity(type: String, id: String) = RecognitionEntity(
        entityType = type,
        canonicalId = "$type.$id",
        showdownId = id,
        displayName = id,
        originalText = id,
        confidence = 0.95,
    )

    private fun RecognitionEntity.asValue() = EntityValue(
        canonicalId = canonicalId,
        showdownId = showdownId,
        displayName = displayName,
        entityType = entityType,
    )
}
