package io.stamethyst.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModCardBodyContent(
    mod: ModItemUi,
    isExpanded: Boolean,
    showModFileName: Boolean,
    showActionsButton: Boolean,
    actionsEnabled: Boolean,
    onActionsClick: () -> Unit,
    modSuggestionText: String? = null,
    suggestionUnread: Boolean = false,
    suggestionBadgeEnabled: Boolean = true,
    onSuggestionClick: () -> Unit = {},
    importPatchBadgeEnabled: Boolean = true,
    onImportPatchClick: () -> Unit = {},
    updateBadgeEnabled: Boolean = true,
    onUpdateBadgeClick: () -> Unit = {},
    headerLeading: @Composable RowScope.() -> Unit = {},
    headerTrailing: @Composable RowScope.() -> Unit
) {
    var showWorkshopBadgeDialog by remember(mod.storagePath) { mutableStateOf(false) }
    val resolvedName = resolveModDisplayName(mod, showModFileName = showModFileName)
    val resolvedModId = mod.manifestModId.ifBlank { mod.modId }
    val resolvedVersion = mod.version.ifBlank { stringResource(R.string.main_mod_unknown_version) }
    val resolvedDescription = mod.description.ifBlank { stringResource(R.string.main_mod_no_description) }
    val dependencies = mod.dependencies
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    Row(verticalAlignment = Alignment.CenterVertically) {
        headerLeading()
        ModCardLeadingImage(mod)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = resolvedName,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.main_mod_modid_format, resolvedModId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            ModCardBadges(
                mod = mod,
                modSuggestionText = modSuggestionText,
                suggestionUnread = suggestionUnread,
                suggestionBadgeEnabled = suggestionBadgeEnabled,
                onSuggestionClick = onSuggestionClick,
                importPatchBadgeEnabled = importPatchBadgeEnabled,
                onImportPatchClick = onImportPatchClick,
                updateBadgeEnabled = updateBadgeEnabled,
                onUpdateBadgeClick = onUpdateBadgeClick,
                onWorkshopBadgeClick = { showWorkshopBadgeDialog = true }
            )
        }
        headerTrailing()
    }

    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = stringResource(R.string.main_mod_version_format, resolvedVersion),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Text(
        text = resolvedDescription,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis
    )
    val workshopStatus = mod.workshop
        ?.takeUnless { it.state == WorkshopModState.ImportedPatched }
        ?.statusText
        .orEmpty()
    if (workshopStatus.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = workshopStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (isExpanded && dependencies.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.main_mod_dependencies_format, dependencies.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    if (showActionsButton && isExpanded) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onActionsClick,
                enabled = actionsEnabled
            ) {
                Text(text = stringResource(R.string.main_mod_actions))
            }
        }
    }

    if (showWorkshopBadgeDialog) {
        AlertDialog(
            onDismissRequest = { showWorkshopBadgeDialog = false },
            text = { Text("该模组从市场中下载，将会自动检查更新。") },
            confirmButton = {
                TextButton(onClick = { showWorkshopBadgeDialog = false }) {
                    Text(text = stringResource(R.string.common_action_confirm))
                }
            }
        )
    }
}

@Composable
private fun ModCardLeadingImage(mod: ModItemUi) {
    val context = LocalContext.current
    val publishedFileId = mod.workshop?.publishedFileId
    val imagePath = mod.workshop?.localPreviewImagePath.orEmpty()
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = publishedFileId, key2 = imagePath) {
        value = withContext(Dispatchers.IO) {
            publishedFileId?.let { WorkshopPreviewCacheStore.decodeCached(context.applicationContext, it) }
                ?: imagePath.takeIf { it.isNotBlank() }?.let(ModCardPreviewImageLoader::load)
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_image_mod),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
    }
}

private object ModCardPreviewImageLoader {
    fun load(path: String): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_SIZE_PX, TARGET_SIZE_PX)
            }
            BitmapFactory.decodeFile(path, options)
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sampleSize >= targetWidth && halfHeight / sampleSize >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private const val TARGET_SIZE_PX = 144
}

@Composable
private fun ModCardBadges(
    mod: ModItemUi,
    modSuggestionText: String?,
    suggestionUnread: Boolean,
    suggestionBadgeEnabled: Boolean,
    onSuggestionClick: () -> Unit,
    importPatchBadgeEnabled: Boolean,
    onImportPatchClick: () -> Unit,
    updateBadgeEnabled: Boolean,
    onUpdateBadgeClick: () -> Unit,
    onWorkshopBadgeClick: () -> Unit,
) {
    val showSuggestion = !modSuggestionText.isNullOrBlank()
    val showImportPatch = !mod.importPatchDetails.isNullOrBlank()
    val showUpdate = mod.workshop?.state == WorkshopModState.UpdateAvailable
    val workshopBadgeState = mod.workshop?.state?.takeIf {
        it != WorkshopModState.ImportedUnpatched &&
            it != WorkshopModState.DownloadFailed &&
            it != WorkshopModState.Downloading &&
            it != WorkshopModState.DownloadPaused &&
            it != WorkshopModState.FileMissing &&
            it != WorkshopModState.UpdateAvailable
    }
    val effectivePriority = mod.effectivePriority
    val hasBadges = showSuggestion || showImportPatch || showUpdate || mod.favorite ||
        mod.newlyImported || workshopBadgeState != null || effectivePriority != null
    if (!hasBadges) return

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showSuggestion) {
            ModSuggestionBadge(
                enabled = suggestionBadgeEnabled,
                unread = suggestionUnread,
                onClick = onSuggestionClick
            )
        }
        if (showImportPatch) {
            ModImportPatchBadge(
                enabled = importPatchBadgeEnabled,
                onClick = onImportPatchClick
            )
        }
        if (showUpdate) {
            WorkshopUpdateBadge(
                enabled = updateBadgeEnabled,
                onClick = onUpdateBadgeClick
            )
        }
        if (mod.favorite) {
            FavoriteBadge()
        }
        if (mod.newlyImported) {
            NewImportBadge()
        }
        workshopBadgeState?.let { state ->
            if (state == WorkshopModState.ImportedPatched) {
                WorkshopBadge(onClick = onWorkshopBadgeClick)
            } else {
                WorkshopStateBadge(state)
            }
        }
        if (effectivePriority != null) {
            PriorityLoadBadge(priority = effectivePriority)
        }
    }
}

@Composable
private fun ModSuggestionBadge(
    enabled: Boolean,
    unread: Boolean,
    onClick: () -> Unit,
) {
    ModCardIconBadge(
        iconResId = R.drawable.ic_error_outline,
        contentDescription = null,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun WorkshopUpdateBadge(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ModCardLabelBadge(
        iconResId = R.drawable.ic_workshop_update,
        text = "更新",
        contentDescription = "更新可用",
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun ModImportPatchBadge(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ModCardIconBadge(
        iconResId = R.drawable.ic_build,
        contentDescription = stringResource(R.string.main_mod_patch_badge_content_description),
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun FavoriteBadge() {
    ModCardIconBadge(
        iconResId = R.drawable.ic_favorite_heart,
        contentDescription = stringResource(R.string.main_mod_favorite_badge_content_description)
    )
}

@Composable
private fun WorkshopBadge(onClick: () -> Unit) {
    ModCardIconBadge(
        iconResId = R.drawable.ic_dock_market,
        contentDescription = stringResource(R.string.main_mod_workshop_badge_content_description),
        onClick = onClick
    )
}

@Composable
private fun NewImportBadge() {
    ModCardTextBadge(text = stringResource(R.string.main_mod_new_import_badge))
}

@Composable
private fun WorkshopStateBadge(state: WorkshopModState) {
    val text = when (state) {
        WorkshopModState.ImportedUnpatched -> "待修补"
        WorkshopModState.ImportedPatched -> "工坊"
        WorkshopModState.Downloading -> "下载中"
        WorkshopModState.DownloadPaused -> "已暂停"
        WorkshopModState.DownloadFailed -> "下载失败"
        WorkshopModState.NonStandardDownloaded -> "需手动处理"
        WorkshopModState.TexturePackInstalled -> "资源包"
        WorkshopModState.UpdateAvailable -> "可更新"
        WorkshopModState.FileMissing -> "文件缺失"
    }
    ModCardTextBadge(text = text)
}

@Composable
private fun PriorityLoadBadge(priority: Int) {
    ModCardTextBadge(text = stringResource(R.string.main_mod_priority_badge_format, priority))
}

@Composable
private fun ModCardIconBadge(
    iconResId: Int,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ModCardBadgeSurface(enabled = enabled, onClick = onClick) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = contentDescription,
            modifier = Modifier.padding(4.dp).size(12.dp)
        )
    }
}

@Composable
private fun ModCardTextBadge(text: String) {
    ModCardBadgeSurface {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun ModCardLabelBadge(
    iconResId: Int,
    text: String,
    contentDescription: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ModCardBadgeSurface(enabled = enabled, onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = contentDescription,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ModCardBadgeSurface(
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val surfaceModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.outline
        },
        shape = RoundedCornerShape(999.dp)
    ) {
        Box(modifier = surfaceModifier) {
            content()
        }
    }
}
