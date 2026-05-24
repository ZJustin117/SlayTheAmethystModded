package io.stamethyst.backend.mods.importing

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.stamethyst.R
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.DuplicateZipNormalizationResult
import io.stamethyst.backend.mods.DuplicateZipEntryNormalizer
import io.stamethyst.backend.mods.JarFileIoUtils
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModManifestRootCompatPatcher
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.backend.mods.importing.patches.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.ManifestRootPatchModule
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.resolveAssignedFolderId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

internal object ModImportPlanner {
    private val ARCHIVE_EXTENSIONS = arrayOf(
        ".zip",
        ".rar",
        ".7z",
        ".tar",
        ".tgz",
        ".tar.gz",
        ".gz",
        ".bz2",
        ".tar.bz2",
        ".xz",
        ".tar.xz",
        ".zst",
        ".tar.zst",
        ".lz4",
        ".tar.lz4"
    )
    private val ARCHIVE_MIME_TYPES = setOf(
        "application/zip",
        "application/x-zip",
        "application/x-zip-compressed",
        "multipart/x-zip",
        "application/vnd.rar",
        "application/rar",
        "application/x-rar",
        "application/x-rar-compressed",
        "application/x-7z-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip2",
        "application/x-xz",
        "application/zstd",
        "application/x-zstd"
    )

    fun plan(
        context: Context,
        uris: List<Uri>,
        options: ModImportPlanningOptions = ModImportPlanningOptions(),
        onProgress: (ModImportPlanningProgress) -> Unit = {},
    ): ModImportPlan {
        val session = createSession(context)
        val progress = PlanningProgressReporter(
            totalSteps = uris.size * (1 + PLANNING_INSPECTION_STEPS_PER_SOURCE) + 1,
            onProgress = onProgress
        )
        try {
            val preparedSources = uris.mapIndexed { index, uri ->
                prepareSource(context, session, index, uri, progress)
            }
            val plannedItems = preparedSources.map { source ->
                inspectSource(context, session, source, options, progress)
            }
            return progress.runStep("正在检查已安装模组冲突", "") {
                applyDuplicateConflicts(context, session, plannedItems)
            }
        } catch (error: Throwable) {
            cleanup(session)
            throw error
        }
    }

    fun planLocalFiles(
        context: Context,
        files: List<File>,
        options: ModImportPlanningOptions = ModImportPlanningOptions(),
        onProgress: (ModImportPlanningProgress) -> Unit = {},
    ): ModImportPlan {
        val session = createSession(context)
        val progress = PlanningProgressReporter(
            totalSteps = files.size * PLANNING_INSPECTION_STEPS_PER_SOURCE + 1,
            onProgress = onProgress
        )
        try {
            val preparedSources = files.mapIndexed { index, file ->
                PreparedImportSource(
                    index = index,
                    uri = null,
                    displayName = file.name.ifBlank { "unknown.jar" },
                    mimeType = null,
                    file = file
                )
            }
            val plannedItems = preparedSources.map { source ->
                inspectSource(context, session, source, options, progress)
            }
            return progress.runStep("正在检查已安装模组冲突", "") {
                applyDuplicateConflicts(context, session, plannedItems)
            }
        } catch (error: Throwable) {
            cleanup(session)
            throw error
        }
    }

    fun cleanup(session: ModImportSession) {
        runCatching {
            if (session.sessionDir.exists()) {
                session.sessionDir.deleteRecursively()
            }
        }
    }

    private fun createSession(context: Context): ModImportSession {
        val id = System.currentTimeMillis().coerceAtLeast(1L) * 1000L + (System.nanoTime() and 999L)
        val dir = File(context.cacheDir, "mod-import-sessions/$id")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create mod import session directory: ${dir.absolutePath}")
        }
        return ModImportSession(id = id, sessionDir = dir)
    }

    private fun prepareSource(
        context: Context,
        session: ModImportSession,
        index: Int,
        uri: Uri,
        progress: PlanningProgressReporter
    ): PreparedImportSource {
        val displayName = resolveDisplayName(context, uri)
        val mimeType = context.contentResolver.getType(uri)?.trim()?.takeIf { it.isNotEmpty() }
        val target = File(session.sessionDir, "source-$index.jar")
        progress.runStep("正在复制导入文件", displayName) {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    throw IOException("Unable to open selected file")
                }
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return PreparedImportSource(
            index = index,
            uri = uri,
            displayName = displayName,
            mimeType = mimeType,
            file = target
        )
    }

    private fun inspectSource(
        context: Context,
        session: ModImportSession,
        source: PreparedImportSource,
        options: ModImportPlanningOptions,
        progress: PlanningProgressReporter
    ): ModImportItemPlan {
        val itemId = "item-${source.index}"
        if (!shouldAttemptJarInspection(source.displayName, source.file)) {
            if (isLikelyCompressedArchive(source)) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.CompressedArchive,
                    detail = context.importString(R.string.mod_import_block_archive_detail)
                )
            }
            return blockedItem(
                itemId = itemId,
                source = source,
                reason = ModImportBlockingReason.UnsupportedFile,
                detail = context.importString(R.string.mod_import_block_unsupported_detail)
            )
        }
        val inspectionFile = File(session.sessionDir, "inspect-${source.index}.jar")
        var activeInspectionFile = inspectionFile
        var preparedFileToKeep: File? = null
        return try {
            progress.runStepWithProgress("正在复制检查副本", source.displayName) { stepProgress ->
                copyFile(source.file, inspectionFile, stepProgress)
            }
            val normalizationResult = progress.runStep("正在规范化 jar 结构", source.displayName) {
                normalizeAndValidateInspectionJar(inspectionFile)
            }
            if (normalizationResult.rewritten) {
                activeInspectionFile = File(session.sessionDir, "normalized-inspect-${source.index}.jar")
                copyFile(inspectionFile, activeInspectionFile)
            }
            val duplicatePlan = buildDuplicateZipPlan(
                context = context,
                result = normalizationResult
            )
            val manifestRootPatch = progress.runStep("正在检查嵌套 manifest", source.displayName) {
                runManifestRootPlan(context, activeInspectionFile)
            }
            val manifestRootPlan = manifestRootPatch?.plan
            val manifestResult = progress.runStep("正在读取 ModTheSpire 清单", source.displayName) {
                runCatching { ModJarSupport.readModManifest(activeInspectionFile) }
            }
            val manifest = manifestResult.getOrElse { error ->
                if (isLikelyModTheSpireJar(activeInspectionFile)) {
                    return blockedItem(
                        itemId = itemId,
                        source = source,
                        reason = ModImportBlockingReason.ReservedCoreComponent,
                        detail = context.importString(R.string.mod_import_block_modthespire_core_detail)
                    )
                }
                if (isLikelyCompressedArchive(source)) {
                    return blockedItem(
                        itemId = itemId,
                        source = source,
                        reason = ModImportBlockingReason.CompressedArchive,
                        detail = context.importString(R.string.mod_import_block_archive_detail)
                    )
                }
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.MissingManifest,
                    detail = error.message ?: context.importString(R.string.mod_import_block_manifest_read_failed)
                )
            }
            val normalizedModId = ModManager.normalizeModId(manifest.normalizedModId)
            if (normalizedModId.isEmpty()) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.MissingModId,
                    detail = context.importString(R.string.mod_import_block_missing_modid_detail)
                )
            }
            val reservedComponent = resolveReservedComponent(normalizedModId)
            if (reservedComponent.isNotEmpty()) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.ReservedCoreComponent,
                    detail = context.importString(R.string.mod_import_block_reserved_core_detail, reservedComponent),
                    manifest = manifest,
                    normalizedModId = normalizedModId,
                    reservedComponent = reservedComponent
                )
            }

            val launchFailure = progress.runStep("正在校验 ModTheSpire 启动清单", source.displayName) {
                MtsLaunchManifestValidator.validateModJar(activeInspectionFile)
            }
            if (launchFailure != null) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.InvalidMtsLaunchManifest,
                    detail = launchFailure.reason,
                    manifest = manifest,
                    normalizedModId = normalizedModId
                )
            }
            val launchModId = MtsLaunchManifestValidator.resolveLaunchModId(activeInspectionFile).trim()
            val atlasFilterPatch = progress.runStep("正在预处理 atlas 过滤器兼容性", source.displayName) {
                runAtlasFilterPlan(context, activeInspectionFile)
            }
            val atlasFilterPlan = atlasFilterPatch?.plan
            val preparedPatchResults = listOfNotNull(
                manifestRootPatch?.result,
                atlasFilterPatch?.result,
            )
            val hasPreparedChanges = normalizationResult.rewritten || preparedPatchResults.any { it.applied }
            val baseItem = ModImportItemPlan(
                id = itemId,
                source = source,
                status = ModImportItemStatus.IMPORTABLE,
                manifest = manifest,
                normalizedModId = normalizedModId,
                launchModId = launchModId
            )
            val patchPlans = progress.runStep("正在生成导入补丁列表", source.displayName) {
                buildPatchPlans(
                    context = context,
                    item = baseItem,
                    duplicatePlan = duplicatePlan,
                    manifestRootPlan = manifestRootPlan,
                    atlasFilterPlan = atlasFilterPlan,
                    inspectionFile = activeInspectionFile,
                    options = options
                )
            }
            val preparedImportFile = if (hasPreparedChanges) {
                File(session.sessionDir, "prepared-${source.index}.jar").also { preparedFile ->
                    if (preparedFile.exists() && !preparedFile.delete()) {
                        throw IOException("Failed to clear stale prepared import file: ${preparedFile.absolutePath}")
                    }
                    if (!activeInspectionFile.renameTo(preparedFile)) {
                        copyFile(activeInspectionFile, preparedFile)
                    }
                    preparedFileToKeep = preparedFile
                }
            } else {
                null
            }
            baseItem.copy(
                preparedImportFile = preparedImportFile,
                preparedPatchResults = preparedPatchResults,
                patchPlans = patchPlans,
            )
        } catch (error: Throwable) {
            if (isLikelyCompressedArchive(source)) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.CompressedArchive,
                    detail = context.importString(R.string.mod_import_block_archive_detail)
                )
            }
            blockedItem(
                itemId = itemId,
                source = source,
                reason = ModImportBlockingReason.UnreadableJar,
                detail = error.message ?: error.javaClass.simpleName
            )
        } finally {
            if (inspectionFile != preparedFileToKeep && inspectionFile.exists()) {
                inspectionFile.delete()
            }
            if (activeInspectionFile != inspectionFile && activeInspectionFile != preparedFileToKeep && activeInspectionFile.exists()) {
                activeInspectionFile.delete()
            }
        }
    }

    private fun buildPatchPlans(
        context: Context,
        item: ModImportItemPlan,
        duplicatePlan: ImportPatchPlan?,
        manifestRootPlan: ImportPatchPlan?,
        atlasFilterPlan: ImportPatchPlan?,
        inspectionFile: File,
        options: ModImportPlanningOptions
    ): List<ImportPatchPlan> {
        val plans = ArrayList<ImportPatchPlan>()
        duplicatePlan?.let { plans.add(it) }
        manifestRootPlan?.let { plans.add(it) }
        atlasFilterPlan?.let { plans.add(it) }
        val modules = ImportPatchRegistry.modules(context)
        modules.forEach { module ->
            if (module.id == DuplicateZipEntryPatchModule.id ||
                module.id == ManifestRootPatchModule.id ||
                module.id == AtlasFilterPatchModule.id
            ) {
                return@forEach
            }
            if (!options.includeUserConfigurablePatches && module.userConfigurable) {
                return@forEach
            }
            if (options.deferUserConfigurablePatchInspection && module.userConfigurable) {
                plans.add(module.basePlan(applicable = true))
                return@forEach
            }
            val plan = module.plan(context, item, inspectionFile) ?: return@forEach
            if (plan.applicable) {
                plans.add(plan)
            }
        }
        return plans.sortedBy { plan ->
            modules.firstOrNull { it.id == plan.moduleId }?.order ?: Int.MAX_VALUE
        }
    }

    internal fun normalizeAndValidateInspectionJar(inspectionFile: File): DuplicateZipNormalizationResult {
        return DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(inspectionFile)
    }

    internal fun shouldAttemptJarInspection(displayName: String, file: File): Boolean {
        if (displayName.trim().endsWith(".jar", ignoreCase = true)) {
            return true
        }
        return isReadableZipContainer(file)
    }

    internal fun isReadableZipContainer(file: File): Boolean {
        if (!file.isFile) {
            return false
        }
        return try {
            var hasEntry = false
            JarFileIoUtils.forEachZipEntry(file) { _, _ ->
                hasEntry = true
                false
            }
            hasEntry
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildDuplicateZipPlan(
        context: Context,
        result: DuplicateZipNormalizationResult
    ): ImportPatchPlan? {
        if (!result.changed) {
            return null
        }
        return DuplicateZipEntryPatchModule.basePlan(
            applicable = true,
            details = listOf(
                context.importString(R.string.mod_import_patch_zip_entry_detail_total, result.totalEntries),
                context.importString(R.string.mod_import_patch_zip_entry_detail_unique, result.uniqueEntries),
                context.importString(R.string.mod_import_patch_zip_entry_detail_removed, result.duplicateEntriesRemoved)
            )
        )
    }

    private fun runManifestRootPlan(context: Context, inspectionFile: File): PreAppliedPatch? {
        if (!CompatibilitySettings.isModManifestRootCompatEnabled(context)) {
            return null
        }
        val result = ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(inspectionFile)
        if (!result.hasPatchedChanges) {
            return null
        }
        val details = listOf(
            context.importString(R.string.mod_import_patch_manifest_root_detail_moved, result.patchedFileEntries),
            context.importString(R.string.mod_import_patch_manifest_root_detail_prefix, result.sourceRootPrefix)
        )
        return PreAppliedPatch(
            plan = ManifestRootPatchModule.basePlan(applicable = true, details = details),
            result = ImportPatchResult(
                moduleId = ManifestRootPatchModule.id,
                moduleVersion = ManifestRootPatchModule.version,
                displayNameResId = ManifestRootPatchModule.displayNameResId,
                summaryResId = ManifestRootPatchModule.summaryResId,
                displayName = context.importString(ManifestRootPatchModule.displayNameResId),
                applied = true,
                summary = context.importString(R.string.mod_import_patch_manifest_root_applied),
                details = details,
                metrics = mapOf("patchedFileEntries" to result.patchedFileEntries),
                attributes = mapOf("sourceRootPrefix" to result.sourceRootPrefix)
            )
        )
    }

    private fun runAtlasFilterPlan(context: Context, inspectionFile: File): PreAppliedPatch? {
        if (!CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)) {
            return null
        }
        val result = ModAtlasFilterCompatPatcher.patchMipMapFiltersInPlace(inspectionFile)
        if (!result.hasPatchedChanges) {
            return null
        }
        val details = listOf(
            context.importString(R.string.mod_import_patch_atlas_filter_detail_files, result.patchedAtlasEntries),
            context.importString(R.string.mod_import_patch_atlas_filter_detail_lines, result.patchedFilterLines)
        )
        return PreAppliedPatch(
            plan = AtlasFilterPatchModule.basePlan(applicable = true, details = details),
            result = ImportPatchResult(
                moduleId = AtlasFilterPatchModule.id,
                moduleVersion = AtlasFilterPatchModule.version,
                displayNameResId = AtlasFilterPatchModule.displayNameResId,
                summaryResId = AtlasFilterPatchModule.summaryResId,
                displayName = context.importString(AtlasFilterPatchModule.displayNameResId),
                applied = true,
                summary = context.importString(R.string.mod_import_patch_atlas_filter_applied),
                details = details,
                metrics = mapOf(
                    "patchedAtlasEntries" to result.patchedAtlasEntries,
                    "patchedFilterLines" to result.patchedFilterLines,
                )
            )
        )
    }

    private fun applyDuplicateConflicts(
        context: Context,
        session: ModImportSession,
        items: List<ModImportItemPlan>
    ): ModImportPlan {
        val incomingByModId = LinkedHashMap<String, MutableList<ModImportItemPlan>>()
        items.filter { it.importable }.forEach { item ->
            val modId = item.normalizedModId
            if (modId.isNotEmpty()) {
                incomingByModId.getOrPut(modId) { ArrayList() }.add(item)
            }
        }
        val existingByModId = buildExistingDuplicateSources(context)
        val conflicts = ArrayList<DuplicateImportConflict>()
        val conflictedIds = LinkedHashSet<String>()
        incomingByModId.forEach { (modId, incoming) ->
            val existing = existingByModId[modId].orEmpty()
            if (incoming.size <= 1 && existing.isEmpty()) {
                return@forEach
            }
            conflictedIds.add(modId)
            conflicts.add(
                DuplicateImportConflict(
                    normalizedModId = modId,
                    displayModId = incoming.firstOrNull()?.displayModId?.ifBlank { modId } ?: modId,
                    importingItemIds = incoming.map { it.id },
                    importingDisplayNames = incoming.map { it.source.displayName },
                    existingSources = existing
                )
            )
        }
        val updatedItems = items.map { item ->
            if (item.importable && conflictedIds.contains(item.normalizedModId)) {
                item.copy(
                    status = ModImportItemStatus.NEEDS_DECISION,
                    duplicateConflictKey = item.normalizedModId
                )
            } else {
                item
            }
        }
        return ModImportPlan(
            session = session,
            items = updatedItems,
            duplicateConflicts = conflicts.sortedBy { it.normalizedModId }
        )
    }

    private fun buildExistingDuplicateSources(context: Context): Map<String, List<ExistingDuplicateImportSource>> {
        val result = LinkedHashMap<String, MutableList<ExistingDuplicateImportSource>>()
        val folderStateStore = MainFolderStateStore().apply { ensureLoaded(context) }
        val validFolderIds = folderStateStore.folders.map { it.id }.toHashSet()
        ModManager.listInstalledMods(context).forEach { mod ->
            if (mod.required || !mod.jarFile.isFile) {
                return@forEach
            }
            val modId = ModManager.normalizeModId(mod.modId).ifBlank {
                ModManager.normalizeModId(mod.manifestModId)
            }
            if (modId.isEmpty()) {
                return@forEach
            }
            val assignedFolderId = resolveAssignedFolderId(
                mod = mod.toModItemUi(mod.jarFile.absolutePath),
                folderAssignments = folderStateStore.assignments,
                validFolderIds = validFolderIds
            )
            result.getOrPut(modId) { ArrayList() }.add(
                ExistingDuplicateImportSource(
                    storagePath = mod.jarFile.absolutePath,
                    fileName = mod.jarFile.name,
                    assignedFolderId = assignedFolderId
                )
            )
        }
        return result
    }

    private fun ModManager.InstalledMod.toModItemUi(storagePath: String): ModItemUi {
        return ModItemUi(
            modId = modId,
            manifestModId = manifestModId,
            storagePath = storagePath,
            name = name,
            version = version,
            description = description,
            dependencies = dependencies,
            required = required,
            installed = installed,
            enabled = enabled,
            explicitPriority = explicitPriority,
            effectivePriority = effectivePriority
        )
    }

    private fun blockedItem(
        itemId: String,
        source: PreparedImportSource,
        reason: ModImportBlockingReason,
        detail: String,
        manifest: ModJarSupport.ModManifestInfo? = null,
        normalizedModId: String = "",
        reservedComponent: String = ""
    ): ModImportItemPlan {
        return ModImportItemPlan(
            id = itemId,
            source = source,
            status = if (reason == ModImportBlockingReason.CompressedArchive) {
                ModImportItemStatus.SKIPPED
            } else {
                ModImportItemStatus.BLOCKED
            },
            blockingReason = reason,
            blockingDetail = detail,
            manifest = manifest,
            normalizedModId = normalizedModId,
            reservedComponent = reservedComponent
        )
    }

    private fun resolveReservedComponent(normalizedModId: String): String {
        return when (ModManager.normalizeModId(normalizedModId)) {
            ModManager.MOD_ID_BASEMOD -> "BaseMod"
            ModManager.MOD_ID_STSLIB -> "StSLib"
            ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT -> "Amethyst Runtime Compat"
            "modthespire" -> "ModTheSpire"
            else -> ""
        }
    }

    private fun isLikelyModTheSpireJar(file: File): Boolean {
        return JarFileIoUtils.hasZipEntry(file, "com/evacipated/cardcrawl/modthespire/Loader.class")
    }

    private fun isLikelyCompressedArchive(source: PreparedImportSource): Boolean {
        val name = source.displayName.trim().lowercase(Locale.ROOT)
        if (name.endsWith(".jar")) {
            return false
        }
        if (name.isNotEmpty() && !name.endsWith(".jar") && ARCHIVE_EXTENSIONS.any { name.endsWith(it) }) {
            return true
        }
        val mime = source.mimeType?.trim()?.lowercase(Locale.ROOT) ?: return false
        return mime in ARCHIVE_MIME_TYPES
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) {
                        return value
                    }
                }
            }
            uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "unknown.jar"
        } catch (_: Throwable) {
            "unknown.jar"
        } finally {
            cursor?.close()
        }
    }

    private fun copyFile(source: File, target: File, onProgressPercent: (Int) -> Unit = {}) {
        val totalBytes = source.length().takeIf { it > 0L }
        var copiedBytes = 0L
        var lastEmittedPercent = -1
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copiedBytes += read.toLong()
                    val percent = totalBytes?.let { total ->
                        ((copiedBytes.coerceAtMost(total) * 100L) / total).toInt().coerceIn(0, 100)
                    } ?: continue
                    if (percent == 100 || percent >= lastEmittedPercent + COPY_PROGRESS_PERCENT_STEP) {
                        onProgressPercent(percent)
                        lastEmittedPercent = percent
                    }
                }
            }
        }
    }

    private class PlanningProgressReporter(
        private val totalSteps: Int,
        private val onProgress: (ModImportPlanningProgress) -> Unit,
    ) {
        private var completedSteps: Int = 0

        fun runStep(message: String, currentFileName: String) {
            runStep(message, currentFileName) { }
        }

        fun <T> runStep(message: String, currentFileName: String, block: () -> T): T {
            emit(message, currentFileName, 0)
            val result = block()
            completedSteps = (completedSteps + 1).coerceAtMost(totalSteps)
            emit(message, currentFileName, 100)
            return result
        }

        fun <T> runStepWithProgress(
            message: String,
            currentFileName: String,
            block: ((Int) -> Unit) -> T
        ): T {
            emit(message, currentFileName, 0)
            val result = block { stepPercent -> emit(message, currentFileName, stepPercent) }
            completedSteps = (completedSteps + 1).coerceAtMost(totalSteps)
            emit(message, currentFileName, 100)
            return result
        }

        private fun emit(message: String, currentFileName: String, stepPercent: Int) {
            val percent = if (totalSteps <= 0) {
                0
            } else {
                val rawProgress = (completedSteps * 100L) + stepPercent.coerceIn(0, 100)
                ((rawProgress + totalSteps - 1) / totalSteps)
                    .toInt()
                    .coerceIn(0, 100)
            }
            onProgress(
                ModImportPlanningProgress(
                    currentStep = completedSteps,
                    totalSteps = totalSteps,
                    currentFileName = currentFileName,
                    message = message,
                    percent = percent,
                )
            )
        }
    }

    private data class PreAppliedPatch(
        val plan: ImportPatchPlan,
        val result: ImportPatchResult,
    )

    private const val PLANNING_INSPECTION_STEPS_PER_SOURCE = 7
    private const val COPY_PROGRESS_PERCENT_STEP = 1
}
