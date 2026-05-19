package io.stamethyst.backend.workshop

import android.content.Context
import android.net.Uri
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.importing.DuplicateImportDecision
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportExecutor
import io.stamethyst.backend.mods.importing.ModImportPlanner
import java.io.File

internal object WorkshopAutoImporter {
    fun importDownloadedJar(
        context: Context,
        details: WorkshopItemDetails,
        jarFile: File,
    ): WorkshopAutoImportResult {
        val plan = try {
            ModImportPlanner.plan(context, listOf(Uri.fromFile(jarFile)))
        } catch (error: Throwable) {
            return WorkshopAutoImportResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        return try {
            val decisions = buildAutoImportDecisions(plan)
            val report = ModImportExecutor.execute(context, plan, decisions)
            val imported = report.importedResults.firstOrNull()
                ?: return WorkshopAutoImportResult.Failed("自动导入未产生已安装模组")
            WorkshopAutoImportResult.Imported(
                modName = imported.modName,
                storagePath = imported.storagePath.orEmpty(),
            )
        } catch (error: Throwable) {
            WorkshopAutoImportResult.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    private fun buildAutoImportDecisions(plan: io.stamethyst.backend.mods.importing.ModImportPlan): ModImportDecisions {
        val patchEnabled = LinkedHashMap<String, Boolean>()
        plan.importableItems.forEach { item ->
            item.patchPlans.forEach { patch ->
                patchEnabled[ModImportDecisions.patchDecisionKey(item.id, patch.moduleId)] = patch.defaultEnabled
            }
        }
        return ModImportDecisions(
            duplicateDecisions = plan.duplicateConflicts.associate {
                it.normalizedModId to DuplicateImportDecision.ReplaceExisting
            },
            reusePreviousFileNameOnReplace = true,
            reusePreviousFolderOnReplace = true,
            patchEnabledByKey = patchEnabled,
            atlasDownscaleStrategy = AtlasOfflineDownscaleStrategy.maxEdge(
                AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX
            ),
            targetFolderIdByItemId = emptyMap(),
        )
    }
}

internal sealed interface WorkshopAutoImportResult {
    data class Imported(
        val modName: String,
        val storagePath: String,
    ) : WorkshopAutoImportResult

    data class Failed(
        val message: String,
    ) : WorkshopAutoImportResult
}
