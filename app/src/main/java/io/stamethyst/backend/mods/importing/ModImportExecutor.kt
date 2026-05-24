package io.stamethyst.backend.mods.importing

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import io.stamethyst.R
import io.stamethyst.backend.mods.DuplicateZipEntryNormalizer
import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.ImportedModPatchRegistry
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.importing.patches.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.AtlasOfflineDownscalePatchModule
import io.stamethyst.backend.mods.importing.patches.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.ImportPatchModule
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.VupShionImportPatchModule
import io.stamethyst.ui.main.MainFolderAssignmentHandoffStore
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.ModAliasStore
import io.stamethyst.ui.main.NewlyImportedModHighlightStore
import io.stamethyst.ui.main.resolveModStoragePathCandidates
import io.stamethyst.ui.main.resolveModFileNameWithoutJar
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet

internal object ModImportExecutor {
    internal fun normalizeWorkingJarForImport(workingJar: File) =
        DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(workingJar)

    private class ProgressReporter(
        val totalSteps: Int,
        private val onProgress: (ModImportExecutionProgress) -> Unit
    ) {
        var completedSteps: Int = 0
            private set

        fun step(item: ModImportItemPlan, message: String) {
            val stepBeforeOperation = completedSteps
            onProgress(
                ModImportExecutionProgress(
                    currentIndex = stepBeforeOperation,
                    total = totalSteps,
                    currentFileName = item.source.displayName,
                    message = message,
                    currentStep = stepBeforeOperation,
                    totalSteps = totalSteps
                )
            )
            completedSteps = (completedSteps + 1).coerceAtMost(totalSteps)
        }
    }

    fun execute(
        context: Context,
        plan: ModImportPlan,
        decisions: ModImportDecisions,
        onPatchEvent: (ModImportPatchExecutionEvent) -> Unit = {},
        onProgress: (ModImportExecutionProgress) -> Unit = {}
    ): ModImportExecutionReport {
        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(context)
        val modulesById = ImportPatchRegistry.modules(context).associateBy { it.id }
        val executableItems = plan.importableItems.filter { item ->
            val conflictKey = item.duplicateConflictKey
            conflictKey == null || decisions.duplicateDecisionFor(conflictKey) != DuplicateImportDecision.SkipNew
        }
        val progress = ProgressReporter(
            totalSteps = executableItems.sumOf { countItemSteps(it, decisions, modulesById) },
            onProgress = onProgress
        )
        val results = ArrayList<ModImportExecutionItemResult>()
        plan.skippedItems.forEach { item ->
            results.add(
                ModImportExecutionItemResult(
                    itemId = item.id,
                    displayName = item.source.displayName,
                    modId = item.normalizedModId,
                    modName = item.displayModName,
                    skipped = true,
                    message = item.blockingDetail
                )
            )
        }
        plan.blockedItems.forEach { item ->
            results.add(
                ModImportExecutionItemResult(
                    itemId = item.id,
                    displayName = item.source.displayName,
                    modId = item.normalizedModId,
                    modName = item.displayModName,
                    blocked = true,
                    message = item.blockingDetail
                )
            )
        }
        plan.importableItems
            .filter { item ->
                val conflictKey = item.duplicateConflictKey
                conflictKey != null && decisions.duplicateDecisionFor(conflictKey) == DuplicateImportDecision.SkipNew
            }
            .forEach { item ->
                results.add(
                    ModImportExecutionItemResult(
                        itemId = item.id,
                        displayName = item.source.displayName,
                        modId = item.normalizedModId,
                        modName = item.displayModName,
                        skipped = true,
                        message = context.importString(R.string.mod_import_result_skipped_duplicate_decision)
                    )
                )
            }

        executableItems.forEach { item ->
            results.add(executeItem(context, item, plan, decisions, modulesById, progress, onPatchEvent))
        }
        onProgress(
            ModImportExecutionProgress(
                currentIndex = executableItems.size,
                total = executableItems.size,
                currentFileName = "",
                message = context.importString(R.string.mod_import_progress_complete),
                currentStep = progress.completedSteps,
                totalSteps = progress.totalSteps
            )
        )
        val report = ModImportExecutionReport(results)
        NewlyImportedModHighlightStore.mark(context, report.importedResults.mapNotNull { it.storagePath })
        return report
    }

    private fun executeItem(
        context: Context,
        item: ModImportItemPlan,
        plan: ModImportPlan,
        decisions: ModImportDecisions,
        modulesById: Map<String, ImportPatchModule>,
        progress: ProgressReporter,
        onPatchEvent: (ModImportPatchExecutionEvent) -> Unit
    ): ModImportExecutionItemResult {
        val preparedWorkingJar = item.preparedImportFile
        val workingJar = preparedWorkingJar ?: File(plan.session.sessionDir, "working-${item.source.index}.jar")
        var activeWorkingJar = workingJar
        var committedTarget: File? = null
        var commitMarker: File? = null
        return try {
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_prepare_working_copy, item.source.displayName)
            )
            if (preparedWorkingJar == null) {
                copyFile(item.source.file, workingJar)
                val normalized = normalizeWorkingJarForImport(workingJar)
                if (normalized.rewritten) {
                    activeWorkingJar = File(plan.session.sessionDir, "normalized-working-${item.source.index}.jar")
                    copyFile(workingJar, activeWorkingJar)
                }
            }
            val patchResults = ArrayList<ImportPatchResult>(item.preparedPatchResults)
            val preparedPatchModuleIds = item.preparedPatchResults.mapTo(LinkedHashSet()) { it.moduleId }
            for (patchPlan in item.patchPlans) {
                if (patchPlan.moduleId == DuplicateZipEntryPatchModule.id) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Skipped(
                            item = item,
                            patchPlan = patchPlan,
                            reason = ModImportPatchSkipReason.DuplicateZipEntryPreApplied
                        )
                    )
                    continue
                }
                if (!decisions.isPatchEnabled(item.id, patchPlan)) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Skipped(
                            item = item,
                            patchPlan = patchPlan,
                            reason = ModImportPatchSkipReason.DisabledByDecision
                        )
                    )
                    continue
                }
                if (preparedPatchModuleIds.contains(patchPlan.moduleId)) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Skipped(
                            item = item,
                            patchPlan = patchPlan,
                            reason = ModImportPatchSkipReason.AlreadyPrepared
                        )
                    )
                    continue
                }
                val module = modulesById[patchPlan.moduleId]
                if (module == null) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Skipped(
                            item = item,
                            patchPlan = patchPlan,
                            reason = ModImportPatchSkipReason.ModuleUnavailable
                        )
                    )
                    continue
                }
                try {
                    progress.step(
                        item = item,
                        message = context.importString(
                            R.string.mod_import_progress_apply_patch,
                            resolvePatchProgressName(context, patchPlan)
                        )
                    )
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Started(
                            item = item,
                            patchPlan = patchPlan
                        )
                    )
                    val result = module.apply(
                        context = context,
                        workingJar = activeWorkingJar,
                        item = item,
                        plan = patchPlan,
                        decisions = decisions
                    )
                    patchResults.add(result)
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Succeeded(
                            item = item,
                            patchPlan = patchPlan,
                            result = result
                        )
                    )
                } catch (error: Throwable) {
                    onPatchEvent(
                        ModImportPatchExecutionEvent.Failed(
                            item = item,
                            patchPlan = patchPlan,
                            error = error,
                            importBlocked = patchPlan.failurePolicy == ImportPatchFailurePolicy.BlockImport
                        )
                    )
                    if (patchPlan.failurePolicy == ImportPatchFailurePolicy.BlockImport) {
                        throw error
                    }
                    patchResults.add(
                        ImportPatchResult(
                            moduleId = patchPlan.moduleId,
                            moduleVersion = patchPlan.moduleVersion,
                            displayNameResId = patchPlan.displayNameResId,
                            summaryResId = patchPlan.summaryResId,
                            displayName = patchPlan.displayName,
                            applied = false,
                            summary = context.importString(R.string.mod_import_patch_failed_skipped),
                            details = listOf(error.message ?: error.javaClass.simpleName)
                        )
                    )
                }
            }
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_validate_manifest, item.source.displayName)
            )
            val finalLaunchModId = MtsLaunchManifestValidator.resolveLaunchModId(activeWorkingJar).trim()
            val replaceExisting = item.duplicateConflictKey?.let { conflictKey ->
                decisions.duplicateDecisionFor(conflictKey) == DuplicateImportDecision.ReplaceExisting
            } == true
            val reuse = if (replaceExisting) {
                buildReusePlan(plan, item, decisions)
            } else {
                DuplicateReusePlan()
            }
            val targetName = reuse.targetFileName
                ?.takeIf { it.isNotBlank() }
                ?: item.source.displayName.ifBlank { "${item.normalizedModId}.jar" }
            val target = if (
                replaceExisting &&
                decisions.reusePreviousFileNameOnReplace &&
                !reuse.targetFileName.isNullOrBlank()
            ) {
                ModManager.resolveStorageFileForImportedModReplacement(context, reuse.targetFileName)
            } else {
                ModManager.resolveStorageFileForImportedMod(context, targetName)
            }
            commitMarker = importCommitMarker(target)
            writeImportCommitMarker(commitMarker, target)
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_write_file, target.name)
            )
            moveFileReplacing(activeWorkingJar, target)
            committedTarget = target
            activeWorkingJar = target
            val targetPath = target.absolutePath
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_update_folder, item.source.displayName)
            )
            val hasExplicitFolderDecision = decisions.hasTargetFolderDecision(item.id)
            val selectedFolderId = decisions.targetFolderIdFor(item.id)
            if (hasExplicitFolderDecision) {
                if (selectedFolderId.isNullOrBlank()) {
                    clearFolderDecision(
                        context = context,
                        storagePath = targetPath,
                        sourceStoragePaths = reuse.sourceStoragePaths,
                        normalizedModId = if (replaceExisting) item.normalizedModId else ""
                    )
                } else {
                    applyFolderDecision(
                        context = context,
                        storagePath = targetPath,
                        folderId = selectedFolderId,
                        sourceStoragePaths = reuse.sourceStoragePaths,
                        normalizedModId = if (replaceExisting) item.normalizedModId else ""
                    )
                }
            } else if (!reuse.assignedFolderId.isNullOrBlank()) {
                applyFolderDecision(
                    context = context,
                    storagePath = targetPath,
                    folderId = reuse.assignedFolderId,
                    sourceStoragePaths = reuse.sourceStoragePaths,
                    normalizedModId = item.normalizedModId
                )
            } else {
                clearFolderDecision(context, targetPath)
            }
            if (!hasExplicitFolderDecision && replaceExisting && decisions.reusePreviousFolderOnReplace) {
                enqueueDuplicateFolderReuseHandoff(
                    context = context,
                    targetPath = targetPath,
                    reuse = reuse,
                    normalizedModId = item.normalizedModId
                )
            }
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_write_metadata, item.source.displayName)
            )
            ImportedModPatchRegistry.put(
                context = context,
                storagePath = targetPath,
                patchInfo = buildLegacyPatchInfo(item, patchResults)
            )
            commitMarker.delete()
            commitMarker = null
            if (replaceExisting) {
                progress.step(
                    item = item,
                    message = context.importString(R.string.mod_import_progress_replace_existing, item.source.displayName)
                )
                ModManager.removeExistingOptionalModsForImport(
                    context = context,
                    normalizedModId = item.normalizedModId,
                    launchModId = finalLaunchModId,
                    excludedPath = target.absolutePath
                )
                applyDuplicateFileNameAlias(
                    context = context,
                    targetPath = targetPath,
                    reuse = reuse,
                    decisions = decisions
                )
            }
            progress.step(
                item = item,
                message = context.importString(R.string.mod_import_progress_item_complete, item.source.displayName)
            )
            ModImportExecutionItemResult(
                itemId = item.id,
                displayName = item.source.displayName,
                modId = item.normalizedModId,
                modName = item.displayModName,
                storagePath = targetPath,
                imported = true,
                message = context.importString(R.string.mod_import_execution_imported),
                patchResults = patchResults
            )
        } catch (error: Throwable) {
            commitMarker?.let { marker ->
                committedTarget?.delete()
                marker.delete()
            }
            if (workingJar.exists()) {
                workingJar.delete()
            }
            if (activeWorkingJar != workingJar && committedTarget == null && activeWorkingJar.exists()) {
                activeWorkingJar.delete()
            }
            ModImportExecutionItemResult(
                itemId = item.id,
                displayName = item.source.displayName,
                modId = item.normalizedModId,
                modName = item.displayModName,
                failed = true,
                message = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun enqueueDuplicateFolderReuseHandoff(
        context: Context,
        targetPath: String,
        reuse: DuplicateReusePlan,
        normalizedModId: String
    ) {
        if (reuse.sourceStoragePaths.isEmpty() && reuse.assignedFolderId.isNullOrBlank()) {
            return
        }
        MainFolderAssignmentHandoffStore.enqueueDuplicateFolderReuse(
            context = context,
            targetStoragePath = targetPath,
            folderId = reuse.assignedFolderId.orEmpty(),
            sourceStoragePaths = reuse.sourceStoragePaths,
            normalizedModId = normalizedModId
        )
    }

    private fun applyDuplicateFileNameAlias(
        context: Context,
        targetPath: String,
        reuse: DuplicateReusePlan,
        decisions: ModImportDecisions
    ) {
        reuse.sourceStoragePaths.forEach { sourcePath ->
            ModAliasStore.setAlias(context, sourcePath, "")
        }
        if (!decisions.reusePreviousFileNameOnReplace) {
            return
        }
        val alias = reuse.targetFileName
            ?.let { fileName -> resolveModFileNameWithoutJar(fileName) }
            ?.trim()
            .orEmpty()
        if (alias.isNotEmpty()) {
            ModAliasStore.setAlias(context, targetPath, alias)
        }
    }

    private fun countItemSteps(
        item: ModImportItemPlan,
        decisions: ModImportDecisions,
        modulesById: Map<String, ImportPatchModule>
    ): Int {
        val enabledPatchSteps = item.patchPlans.count { patchPlan ->
            patchPlan.moduleId != DuplicateZipEntryPatchModule.id &&
                decisions.isPatchEnabled(item.id, patchPlan) &&
                item.preparedPatchResults.none { it.moduleId == patchPlan.moduleId } &&
                modulesById.containsKey(patchPlan.moduleId)
        }
        val replaceExisting = item.duplicateConflictKey?.let { conflictKey ->
            decisions.duplicateDecisionFor(conflictKey) == DuplicateImportDecision.ReplaceExisting
        } == true
        return 1 + enabledPatchSteps + 1 + if (replaceExisting) 1 else 0 + 1 + 1 + 1 + 1
    }

    private fun resolvePatchProgressName(context: Context, patchPlan: ImportPatchPlan): String {
        return if (patchPlan.displayNameResId != 0) {
            context.importString(patchPlan.displayNameResId)
        } else {
            patchPlan.displayName
        }
    }

    private data class DuplicateReusePlan(
        val targetFileName: String? = null,
        val assignedFolderId: String? = null,
        val sourceStoragePaths: List<String> = emptyList()
    )

    private fun buildReusePlan(
        plan: ModImportPlan,
        item: ModImportItemPlan,
        decisions: ModImportDecisions
    ): DuplicateReusePlan {
        val conflict = plan.duplicateConflicts.firstOrNull {
            it.normalizedModId == item.duplicateConflictKey
        } ?: return DuplicateReusePlan()
        val firstExisting = conflict.existingSources
            .sortedWith(compareBy({ it.fileName.lowercase() }, { it.fileName }, { it.storagePath }))
            .firstOrNull()
        return DuplicateReusePlan(
            targetFileName = if (decisions.reusePreviousFileNameOnReplace) firstExisting?.fileName else null,
            assignedFolderId = if (decisions.reusePreviousFolderOnReplace) {
                conflict.existingSources.firstOrNull { !it.assignedFolderId.isNullOrBlank() }?.assignedFolderId
            } else {
                null
            },
            sourceStoragePaths = conflict.existingSources.map { it.storagePath }
        )
    }

    private fun applyFolderDecision(
        context: Context,
        storagePath: String,
        folderId: String?,
        sourceStoragePaths: Collection<String> = emptyList(),
        normalizedModId: String = ""
    ) {
        val normalizedFolderId = folderId?.trim().orEmpty()
        if (normalizedFolderId.isEmpty()) {
            return
        }
        val store = MainFolderStateStore().apply { ensureLoaded(context) }
        if (store.folders.none { it.id == normalizedFolderId }) {
            return
        }
        sourceStoragePaths.forEach { sourcePath ->
            resolveModStoragePathCandidates(sourcePath).forEach { candidate ->
                store.assignments.remove(candidate)
            }
        }
        val normalizedKey = ModManager.normalizeModId(normalizedModId)
        if (normalizedKey.isNotEmpty()) {
            store.assignments.remove(normalizedKey)
        }
        store.assignments[storagePath] = normalizedFolderId
        store.persist(context, synchronous = true)
        if (sourceStoragePaths.isNotEmpty() || normalizedModId.isNotBlank()) {
            MainFolderAssignmentHandoffStore.enqueueDuplicateFolderReuse(
                context = context,
                targetStoragePath = storagePath,
                folderId = normalizedFolderId,
                sourceStoragePaths = sourceStoragePaths,
                normalizedModId = normalizedModId
            )
        }
    }

    private fun clearFolderDecision(
        context: Context,
        storagePath: String,
        sourceStoragePaths: Collection<String> = emptyList(),
        normalizedModId: String = ""
    ) {
        val store = MainFolderStateStore().apply { ensureLoaded(context) }
        var changed = false
        resolveModStoragePathCandidates(storagePath).forEach { candidate ->
            if (store.assignments.remove(candidate) != null) {
                changed = true
            }
        }
        sourceStoragePaths.forEach { sourcePath ->
            resolveModStoragePathCandidates(sourcePath).forEach { candidate ->
                if (store.assignments.remove(candidate) != null) {
                    changed = true
                }
            }
        }
        val normalizedKey = ModManager.normalizeModId(normalizedModId)
        if (normalizedKey.isNotEmpty() && store.assignments.remove(normalizedKey) != null) {
            changed = true
        }
        store.unassignedIsCollapsed = false
        if (changed) {
            store.persist(context, synchronous = true)
        }
    }

    private fun buildLegacyPatchInfo(
        item: ModImportItemPlan,
        patchResults: List<ImportPatchResult>
    ): ImportedModPatchInfo {
        var patchedAtlasEntries = 0
        var patchedFilterLines = 0
        var downscaledAtlasEntries = 0
        var downscaledAtlasPageEntries = 0
        var downscaledAtlasRuntimeMemorySavedMb = 0
        var patchedManifestRootEntries = 0
        var patchedManifestRootPrefix = ""
        var patchedFrieren = false
        var patchedDownfallClassEntries = 0
        var patchedDownfallMerchant = 0
        var patchedDownfallHexaghost = 0
        var patchedDownfallBossPanel = 0
        var patchedVupShion = false
        var patchedJacketShaderEntries = 0
        var patchedJacketVersionDirectives = 0
        var patchedJacketPrecisionBlocks = 0

        patchResults.filter { it.applied }.forEach { result ->
            when (result.moduleId) {
                AtlasFilterPatchModule.id -> {
                    patchedAtlasEntries += result.metrics["patchedAtlasEntries"] ?: 0
                    patchedFilterLines += result.metrics["patchedFilterLines"] ?: 0
                }
                AtlasOfflineDownscalePatchModule.id -> {
                    downscaledAtlasEntries += result.metrics["patchedAtlasEntries"] ?: 0
                    downscaledAtlasPageEntries += result.metrics["downscaledPageEntries"] ?: 0
                    downscaledAtlasRuntimeMemorySavedMb +=
                        result.metrics["estimatedRuntimeBytesSavedMb"] ?: 0
                }
                ManifestRootPatchModule.id -> {
                    patchedManifestRootEntries += result.metrics["patchedFileEntries"] ?: 0
                    patchedManifestRootPrefix = result.attributes["sourceRootPrefix"].orEmpty()
                }
                FrierenImportPatchModule.id -> patchedFrieren = true
                DownfallImportPatchModule.id -> {
                    patchedDownfallClassEntries += result.metrics["patchedClassEntries"] ?: 0
                    patchedDownfallMerchant += result.metrics["patchedMerchantClassEntries"] ?: 0
                    patchedDownfallHexaghost += result.metrics["patchedHexaghostBodyClassEntries"] ?: 0
                    patchedDownfallBossPanel += result.metrics["patchedBossMechanicPanelClassEntries"] ?: 0
                }
                VupShionImportPatchModule.id -> patchedVupShion = true
                JacketNoAnoKoImportPatchModule.id -> {
                    patchedJacketShaderEntries += result.metrics["patchedShaderEntries"] ?: 0
                    patchedJacketVersionDirectives += result.metrics["removedDesktopVersionDirectives"] ?: 0
                    patchedJacketPrecisionBlocks += result.metrics["insertedFragmentPrecisionBlocks"] ?: 0
                }
            }
        }
        return ImportedModPatchInfo(
            modId = item.normalizedModId,
            modName = item.displayModName,
            patchedAtlasEntries = patchedAtlasEntries,
            patchedFilterLines = patchedFilterLines,
            downscaledAtlasEntries = downscaledAtlasEntries,
            downscaledAtlasPageEntries = downscaledAtlasPageEntries,
            downscaledAtlasRuntimeMemorySavedMb = downscaledAtlasRuntimeMemorySavedMb,
            patchedManifestRootEntries = patchedManifestRootEntries,
            patchedManifestRootPrefix = patchedManifestRootPrefix,
            patchedFrierenAntiPirateMethod = patchedFrieren,
            patchedDownfallClassEntries = patchedDownfallClassEntries,
            patchedDownfallMerchantClassEntries = patchedDownfallMerchant,
            patchedDownfallHexaghostBodyClassEntries = patchedDownfallHexaghost,
            patchedDownfallBossMechanicPanelClassEntries = patchedDownfallBossPanel,
            patchedVupShionWebButtonConstructor = patchedVupShion,
            patchedJacketNoAnoKoShaderEntries = patchedJacketShaderEntries,
            patchedJacketNoAnoKoDesktopVersionDirectives = patchedJacketVersionDirectives,
            patchedJacketNoAnoKoFragmentPrecisionBlocks = patchedJacketPrecisionBlocks
        )
    }

    private fun copyFile(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (!source.exists()) {
            throw IOException("Source file not found: ${source.absolutePath}")
        }
        if (renameFile(source, target)) {
            return
        }
        val temp = File(
            parent ?: throw IOException("Target has no parent: ${target.absolutePath}"),
            ".${target.name}.${System.nanoTime()}.importing"
        )
        if (temp.exists() && !temp.delete()) {
            throw IOException("Failed to clear stale temporary import file: ${temp.absolutePath}")
        }
        var committed = false
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temp, false).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (renameFile(temp, target)) {
                committed = true
            } else {
                throw IOException("Failed to commit imported mod file: ${target.absolutePath}")
            }
        } finally {
            if (!committed) {
                temp.delete()
            }
        }
        source.delete()
    }

    private fun renameFile(source: File, target: File): Boolean {
        if (source.renameTo(target)) {
            return true
        }
        return try {
            Os.rename(source.absolutePath, target.absolutePath)
            true
        } catch (_: ErrnoException) {
            false
        }
    }

    private fun importCommitMarker(target: File): File {
        val parent = target.parentFile ?: throw IOException("Target has no parent: ${target.absolutePath}")
        return File(parent, ".${target.name}.importing.marker")
    }

    private fun writeImportCommitMarker(marker: File, target: File) {
        val parent = marker.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        marker.writeText(target.name, Charsets.UTF_8)
    }
}
