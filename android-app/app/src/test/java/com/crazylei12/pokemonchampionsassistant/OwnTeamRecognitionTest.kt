package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTeamRecognitionTest {
    @Test
    fun statCandidateSelectionUsesAgreementInsteadOfLargestValue() {
        assertEquals(182, selectStatValueCandidates(listOf(182, 183, 182, null)))
        assertEquals(182, selectStatValueCandidates(listOf(182, 183)))
        assertEquals(100, selectStatValueCandidates(listOf(100, 40, 100, null)))
        assertEquals(182, selectStatValueCandidates(listOf(18, 182)))
    }

    @Test
    fun statDigitGeometryCorrectsSixNineConfusionsInEveryPosition() {
        assertEquals(99, correctSixNineDigitConfusions(96, listOf(0.289, 0.292)))
        assertEquals(99, correctSixNineDigitConfusions(99, listOf(0.289, 0.292)))
        assertEquals(66, correctSixNineDigitConfusions(69, listOf(0.650, 0.650)))
        assertEquals(199, correctSixNineDigitConfusions(196, listOf(null, 0.289, 0.292)))
        assertEquals(96, correctSixNineDigitConfusions(96, listOf(0.50, 0.50)))
    }

    @Test
    fun statDigitGeometryDropsOcrDigitsThatAreNotPresentInTheCrop() {
        assertEquals(90, normalizeStatValueDigitCount(900, 2))
        assertEquals(182, normalizeStatValueDigitCount(182, 3))
        assertEquals(90, normalizeStatValueDigitCount(90, 3))
    }

    @Test
    fun statDigitGeometrySeparatesTwoAndThreeWithAnAmbiguousSafetyBand() {
        assertEquals(137, correctTwoThreeMiddleDigitConfusion(127, 0.348))
        assertEquals(137, correctTwoThreeMiddleDigitConfusion(137, 0.348))
        assertEquals(127, correctTwoThreeMiddleDigitConfusion(137, 0.478))
        assertEquals(127, correctTwoThreeMiddleDigitConfusion(127, 0.478))
        assertEquals(127, correctTwoThreeMiddleDigitConfusion(127, 0.405))
        assertEquals(137, correctTwoThreeMiddleDigitConfusion(137, 0.405))
    }

    @Test
    fun ambiguousPartialSpeciesNameIsNotResolvedByCatalogOrder() {
        val pelipper = entity("species.pelipper", "Pelipper", "大嘴", 0.833)
        val mawile = entity("species.mawile", "Mawile", "大嘴", 0.833)

        assertNull(selectUnambiguousRecognitionEntity(listOf(pelipper, mawile)))
        assertEquals(
            "species.pelipper",
            selectUnambiguousRecognitionEntity(
                listOf(pelipper.copy(originalText = "大嘴鸥", confidence = 1.0), mawile.copy(confidence = 0.74)),
            )?.canonicalId,
        )
    }

    @Test
    fun moveCropHasATextOnlyRetryThatExcludesTheTypeIcon() {
        val regions = entityCropRegions("move0")

        assertEquals(2, regions.size)
        assertTrue(regions[1][0] > regions[0][0])
        assertEquals(0.66, regions[1][0], 0.0)
        assertEquals(regions[0][2], regions[1][2], 0.0)
        assertEquals(regions[0][3], regions[1][3], 0.0)
    }

    @Test
    fun recognizedMoveSlotIndexesSurviveDraftPersistenceAndLegacyDraftsRemainReadable() {
        val slot = RecognizedSlot(
            slotIndex = 5,
            species = null,
            moves = (1..3).map { entity("move.$it", "move$it", "招式$it", 1.0) },
            moveSlotIndexes = listOf(1, 2, 3),
        )

        assertEquals(listOf(1, 2, 3), RecognizedSlot.fromJson(slot.toJson()).moveSlotIndexes)
        assertEquals(
            listOf(0, 1, 2),
            RecognizedSlot.fromJson(slot.toJson().apply { remove("moveSlotIndexes") }).moveSlotIndexes,
        )
    }

    @Test
    fun statCropRetriesWithTwoNumberOnlyWidths() {
        val left = statCropHorizontalRanges(doubleArrayOf(0.24, 0.39, 0.21, 0.41))
        val right = statCropHorizontalRanges(doubleArrayOf(0.71, 0.86, 0.21, 0.41))

        assertEquals(listOf(0.24 to 0.39, 0.23 to 0.415), left)
        assertEquals(listOf(0.71 to 0.86, 0.70 to 0.885), right)
    }

    private fun entity(canonicalId: String, showdownId: String, rawText: String, confidence: Double) =
        RecognitionEntity(
            entityType = "species",
            canonicalId = canonicalId,
            showdownId = showdownId,
            displayName = showdownId,
            originalText = rawText,
            confidence = confidence,
        )
}
