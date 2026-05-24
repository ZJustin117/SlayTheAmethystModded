package io.stamethyst.ui.modimport

import io.stamethyst.backend.mods.importing.DuplicateImportConflict
import io.stamethyst.backend.mods.importing.DuplicateImportDecision
import io.stamethyst.backend.mods.importing.ExistingDuplicateImportSource
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.ModImportItemStatus
import io.stamethyst.backend.mods.importing.ModImportPlan
import io.stamethyst.backend.mods.importing.ModImportSession
import io.stamethyst.backend.mods.importing.PreparedImportSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModImportDecisionsTest {
    @Test
    fun withDefaultReplacementFolders_selectsPreviousFolderForReplacement() {
        val item = duplicateItem()
        val plan = duplicatePlan(item)
        val decisions = ModImportDecisions(
            duplicateDecisions = mapOf("sameid" to DuplicateImportDecision.ReplaceExisting),
            reusePreviousFolderOnReplace = true
        )

        val updated = decisions.withDefaultReplacementFolders(
            plan = plan,
            validFolderIds = setOf("folder-a")
        )

        assertTrue(updated.hasTargetFolderDecision(item.id))
        assertEquals("folder-a", updated.targetFolderIdFor(item.id))
    }

    @Test
    fun withDefaultReplacementFolders_preservesExplicitUnassignedChoice() {
        val item = duplicateItem()
        val plan = duplicatePlan(item)
        val decisions = ModImportDecisions(
            duplicateDecisions = mapOf("sameid" to DuplicateImportDecision.ReplaceExisting),
            reusePreviousFolderOnReplace = true,
            targetFolderIdByItemId = mapOf(item.id to null)
        )

        val updated = decisions.withDefaultReplacementFolders(
            plan = plan,
            validFolderIds = setOf("folder-a")
        )

        assertTrue(updated.hasTargetFolderDecision(item.id))
        assertNull(updated.targetFolderIdFor(item.id))
    }

    @Test
    fun defaultReplacementFolderIdFor_ignoresKeepMultipleDecision() {
        val item = duplicateItem()
        val plan = duplicatePlan(item)
        val decisions = ModImportDecisions(
            duplicateDecisions = mapOf("sameid" to DuplicateImportDecision.KeepMultiple),
            reusePreviousFolderOnReplace = true
        )

        assertNull(
            decisions.defaultReplacementFolderIdFor(
                plan = plan,
                item = item,
                validFolderIds = setOf("folder-a")
            )
        )
    }

    private fun duplicateItem(): ModImportItemPlan {
        return ModImportItemPlan(
            id = "item-0",
            source = PreparedImportSource(
                index = 0,
                uri = null,
                displayName = "new.jar",
                mimeType = null,
                file = File("new.jar")
            ),
            status = ModImportItemStatus.NEEDS_DECISION,
            normalizedModId = "sameid",
            duplicateConflictKey = "sameid"
        )
    }

    private fun duplicatePlan(item: ModImportItemPlan): ModImportPlan {
        return ModImportPlan(
            session = ModImportSession(id = 1L, sessionDir = File("session")),
            items = listOf(item),
            duplicateConflicts = listOf(
                DuplicateImportConflict(
                    normalizedModId = "sameid",
                    displayModId = "SameId",
                    importingItemIds = listOf(item.id),
                    importingDisplayNames = listOf(item.source.displayName),
                    existingSources = listOf(
                        ExistingDuplicateImportSource(
                            storagePath = "/mods/old.jar",
                            fileName = "old.jar",
                            assignedFolderId = "folder-a"
                        )
                    )
                )
            )
        )
    }
}
