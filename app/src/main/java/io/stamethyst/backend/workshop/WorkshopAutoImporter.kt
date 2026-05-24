package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.importing.DuplicateImportDecision
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportExecutionProgress
import io.stamethyst.backend.mods.importing.ModImportExecutionReport
import io.stamethyst.backend.mods.importing.ModImportExecutor
import io.stamethyst.backend.mods.importing.ModImportPatchExecutionEvent
import io.stamethyst.backend.mods.importing.ModImportPatchSkipReason
import io.stamethyst.backend.mods.importing.ModImportPlanningOptions
import io.stamethyst.backend.mods.importing.ModImportPlanningProgress
import io.stamethyst.backend.mods.importing.ModImportPlanner
import io.stamethyst.backend.mods.importing.ModImportPlan
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File

internal object WorkshopAutoImporter {
    private const val ATLAS_OFFLINE_DOWNSCALE_PATCH_ID = "texture.atlas_offline_downscale"

    fun importDownloadedJar(
        context: Context,
        details: WorkshopItemDetails,
        jarFile: File,
        onProgress: (WorkshopAutoImportProgress) -> Unit = {},
    ): WorkshopAutoImportResult {
        val logFile = runCatching { WorkshopAutoImportPatchLogStore.createLogFile(context) }.getOrNull()
        log(logFile, "自动导入修补开始")
        log(logFile, "workshop.appId=${details.summary.appId}")
        log(logFile, "workshop.publishedFileId=${details.summary.publishedFileId}")
        log(logFile, "workshop.title=${details.summary.title}")
        log(logFile, "source.jar.path=${jarFile.absolutePath}")
        log(logFile, "source.jar.exists=${jarFile.isFile}")
        log(logFile, "source.jar.length=${if (jarFile.isFile) jarFile.length() else 0L}")
        val plan = try {
            log(logFile, "规划阶段开始")
            ModImportPlanner.planLocalFiles(
                context = context,
                files = listOf(jarFile),
                options = ModImportPlanningOptions(
                    includeUserConfigurablePatches = true,
                    deferUserConfigurablePatchInspection = true,
                ),
                onProgress = { progress ->
                    log(logFile, progress.toLogLine("规划进度"))
                    onProgress(progress.toWorkshopAutoImportProgress())
                },
            )
        } catch (error: Throwable) {
            log(logFile, "规划阶段失败：${error.summaryForLog()}")
            log(logFile, error.stackTraceToString())
            return WorkshopAutoImportResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        return try {
            log(logFile, "规划阶段完成")
            logPlan(logFile, plan)
            val decisions = buildAutoImportDecisions(context, plan)
            logDecisions(logFile, plan, decisions)
            val report = ModImportExecutor.execute(
                context = context,
                plan = plan,
                decisions = decisions,
                onPatchEvent = { event -> logPatchEvent(logFile, event) },
                onProgress = { progress ->
                    log(logFile, progress.toLogLine("执行进度"))
                    onProgress(progress.toWorkshopAutoImportProgress(executionProgressStart = PLANNING_PROGRESS_WEIGHT))
                }
            )
            logReport(logFile, report)
            val imported = report.importedResults.firstOrNull()
                ?: run {
                    log(logFile, "自动导入失败：自动导入未产生已安装模组")
                    return WorkshopAutoImportResult.Failed("自动导入未产生已安装模组")
                }
            log(logFile, "自动导入成功：modName=${imported.modName} storagePath=${imported.storagePath.orEmpty()}")
            WorkshopAutoImportResult.Imported(
                modName = imported.modName,
                storagePath = imported.storagePath.orEmpty(),
            )
        } catch (error: Throwable) {
            log(logFile, "执行阶段失败：${error.summaryForLog()}")
            log(logFile, error.stackTraceToString())
            WorkshopAutoImportResult.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { ModImportPlanner.cleanup(plan.session) }
                .onFailure { error -> log(logFile, "清理导入会话失败：${error.summaryForLog()}") }
            log(logFile, "自动导入修补结束")
        }
    }

    private fun ModImportPlanningProgress.toWorkshopAutoImportProgress(): WorkshopAutoImportProgress {
        return WorkshopAutoImportProgress(
            message = message,
            percent = scaleProgress(percent, PLANNING_PROGRESS_WEIGHT),
            currentStep = currentStep,
            totalSteps = totalSteps,
            currentFileName = currentFileName,
        )
    }

    private fun ModImportExecutionProgress.toWorkshopAutoImportProgress(
        executionProgressStart: Int
    ): WorkshopAutoImportProgress {
        val remainingProgress = 100 - executionProgressStart
        return WorkshopAutoImportProgress(
            message = message,
            percent = (executionProgressStart + scaleProgress(percent, remainingProgress)).coerceIn(0, 100),
            currentStep = currentStep,
            totalSteps = totalSteps,
            currentFileName = currentFileName,
        )
    }

    private fun scaleProgress(percent: Int, weight: Int): Int {
        val safePercent = percent.coerceIn(0, 100)
        if (safePercent <= 0 || weight <= 0) return 0
        return ((safePercent * weight) + 99) / 100
    }

    private fun buildAutoImportDecisions(
        context: Context,
        plan: io.stamethyst.backend.mods.importing.ModImportPlan
    ): ModImportDecisions {
        val atlasDownscaleEnabled = LauncherPreferences.isWorkshopAutoImportAtlasDownscaleEnabled(context)
        val patchEnabled = LinkedHashMap<String, Boolean>()
        plan.importableItems.forEach { item ->
            item.patchPlans.forEach { patch ->
                patchEnabled[ModImportDecisions.patchDecisionKey(item.id, patch.moduleId)] =
                    patch.defaultEnabled &&
                        (patch.moduleId != ATLAS_OFFLINE_DOWNSCALE_PATCH_ID || atlasDownscaleEnabled)
            }
        }
        return ModImportDecisions(
            duplicateDecisions = plan.duplicateConflicts.associate {
                it.normalizedModId to DuplicateImportDecision.ReplaceExisting
            },
            reusePreviousFileNameOnReplace = true,
            reusePreviousFolderOnReplace = true,
            patchEnabledByKey = patchEnabled,
            atlasDownscaleStrategy = if (atlasDownscaleEnabled) {
                AtlasOfflineDownscaleStrategy.maxEdge(
                    LauncherPreferences.readWorkshopAutoImportAtlasDownscaleMaxEdgePx(context)
                )
            } else {
                null
            },
            targetFolderIdByItemId = emptyMap(),
        )
    }

    private fun log(logFile: File?, message: String) {
        WorkshopAutoImportPatchLogStore.appendLine(logFile, message)
    }

    private fun logPlan(logFile: File?, plan: ModImportPlan) {
        log(logFile, "规划摘要：items=${plan.items.size} importable=${plan.importableItems.size} blocked=${plan.blockedItems.size} skipped=${plan.skippedItems.size} duplicateConflicts=${plan.duplicateConflicts.size}")
        plan.items.forEach { item ->
            log(
                logFile,
                "导入项：id=${item.id} file=${item.source.displayName} status=${item.status.name} modId=${item.normalizedModId.ifBlank { "<empty>" }} modName=${item.displayModName} patchPlans=${item.patchPlans.size} preparedPatchResults=${item.preparedPatchResults.size} blockingReason=${item.blockingReason?.name.orEmpty()} blockingDetail=${item.blockingDetail.ifBlank { "<empty>" }}"
            )
            item.patchPlans.forEach { patch ->
                log(
                    logFile,
                    "修补计划：item=${item.id} module=${patch.moduleId} version=${patch.moduleVersion} name=${patch.displayName} category=${patch.category.name} defaultEnabled=${patch.defaultEnabled} userConfigurable=${patch.userConfigurable} failurePolicy=${patch.failurePolicy.name} applicable=${patch.applicable} details=${patch.details.joinToString(" | ").ifBlank { "<empty>" }}"
                )
            }
            item.preparedPatchResults.forEach { result ->
                log(logFile, "修补完成（规划阶段预处理）：item=${item.id} ${result.toLogText()}")
            }
        }
        plan.duplicateConflicts.forEach { conflict ->
            log(
                logFile,
                "重复模组冲突：modId=${conflict.normalizedModId} importing=${conflict.importingDisplayNames.joinToString()} existing=${conflict.existingSources.joinToString { it.storagePath }}"
            )
        }
    }

    private fun logDecisions(logFile: File?, plan: ModImportPlan, decisions: ModImportDecisions) {
        plan.duplicateConflicts.forEach { conflict ->
            log(logFile, "自动决策：duplicate modId=${conflict.normalizedModId} decision=${decisions.duplicateDecisionFor(conflict.normalizedModId).name}")
        }
        log(logFile, "自动决策：reusePreviousFileNameOnReplace=${decisions.reusePreviousFileNameOnReplace}")
        log(logFile, "自动决策：reusePreviousFolderOnReplace=${decisions.reusePreviousFolderOnReplace}")
        log(logFile, "自动决策：atlasDownscaleStrategy=${decisions.atlasDownscaleStrategy}")
        plan.importableItems.forEach { item ->
            item.patchPlans.forEach { patch ->
                log(logFile, "自动决策：patch item=${item.id} module=${patch.moduleId} enabled=${decisions.isPatchEnabled(item.id, patch)}")
            }
        }
    }

    private fun logPatchEvent(logFile: File?, event: ModImportPatchExecutionEvent) {
        val itemText = "item=${event.item.id} modId=${event.item.normalizedModId.ifBlank { "<empty>" }} file=${event.item.source.displayName}"
        val patchText = "module=${event.patchPlan.moduleId} version=${event.patchPlan.moduleVersion} name=${event.patchPlan.displayName} failurePolicy=${event.patchPlan.failurePolicy.name}"
        when (event) {
            is ModImportPatchExecutionEvent.Started -> {
                log(logFile, "修补开始：$itemText $patchText")
            }
            is ModImportPatchExecutionEvent.Succeeded -> {
                log(logFile, "修补完成：$itemText $patchText ${event.result.toLogText()}")
            }
            is ModImportPatchExecutionEvent.Skipped -> {
                log(logFile, "修补跳过：$itemText $patchText reason=${event.reason.logText()}")
            }
            is ModImportPatchExecutionEvent.Failed -> {
                log(logFile, "修补失败：$itemText $patchText importBlocked=${event.importBlocked} error=${event.error.summaryForLog()}")
                log(logFile, event.error.stackTraceToString())
            }
        }
    }

    private fun logReport(logFile: File?, report: ModImportExecutionReport) {
        log(logFile, "执行摘要：imported=${report.importedCount} skipped=${report.skippedCount} blocked=${report.blockedCount} failed=${report.failedCount} appliedPatches=${report.appliedPatchResults.size}")
        report.results.forEach { result ->
            log(
                logFile,
                "执行结果：item=${result.itemId} modId=${result.modId} modName=${result.modName} imported=${result.imported} skipped=${result.skipped} blocked=${result.blocked} failed=${result.failed} storagePath=${result.storagePath.orEmpty()} message=${result.message.ifBlank { "<empty>" }} patchResults=${result.patchResults.size}"
            )
            result.patchResults.forEach { patchResult ->
                log(logFile, "执行修补结果：item=${result.itemId} ${patchResult.toLogText()}")
            }
        }
    }

    private fun ModImportPlanningProgress.toLogLine(prefix: String): String =
        "$prefix：step=$currentStep/$totalSteps percent=${percent.coerceIn(0, 100)} file=$currentFileName message=$message"

    private fun ModImportExecutionProgress.toLogLine(prefix: String): String =
        "$prefix：step=$currentStep/$totalSteps percent=${percent.coerceIn(0, 100)} file=$currentFileName message=$message"

    private fun ImportPatchResult.toLogText(): String {
        return "module=$moduleId version=$moduleVersion name=$displayName applied=$applied summary=$summary details=${details.joinToString(" | ").ifBlank { "<empty>" }} metrics=${metrics.ifEmpty { emptyMap<String, Int>() }} attributes=${attributes.ifEmpty { emptyMap<String, String>() }}"
    }

    private fun ModImportPatchSkipReason.logText(): String = when (this) {
        ModImportPatchSkipReason.DuplicateZipEntryPreApplied -> "duplicate_zip_entry_pre_applied"
        ModImportPatchSkipReason.DisabledByDecision -> "disabled_by_decision"
        ModImportPatchSkipReason.AlreadyPrepared -> "already_prepared"
        ModImportPatchSkipReason.ModuleUnavailable -> "module_unavailable"
    }

    private fun Throwable.summaryForLog(): String {
        return javaClass.name + (message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
    }

    private const val PLANNING_PROGRESS_WEIGHT = 30
}

internal data class WorkshopAutoImportProgress(
    val message: String,
    val percent: Int,
    val currentStep: Int,
    val totalSteps: Int,
    val currentFileName: String,
)

internal sealed interface WorkshopAutoImportResult {
    data class Imported(
        val modName: String,
        val storagePath: String,
    ) : WorkshopAutoImportResult

    data class Failed(
        val message: String,
    ) : WorkshopAutoImportResult
}
