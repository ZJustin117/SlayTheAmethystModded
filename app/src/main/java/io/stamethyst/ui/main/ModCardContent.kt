package io.stamethyst.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
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
    headerLeading: @Composable RowScope.() -> Unit = {},
    headerTrailing: @Composable RowScope.() -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = resolvedName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!modSuggestionText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    ModSuggestionIcon(
                        enabled = suggestionBadgeEnabled,
                        unread = suggestionUnread,
                        onClick = onSuggestionClick
                    )
                }
                if (!mod.importPatchDetails.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    ModImportPatchIcon(
                        enabled = importPatchBadgeEnabled,
                        onClick = onImportPatchClick
                    )
                }
                if (mod.favorite) {
                    Spacer(modifier = Modifier.width(6.dp))
                    FavoriteBadge()
                }
                if (mod.newlyImported) {
                    Spacer(modifier = Modifier.width(6.dp))
                    NewImportBadge()
                }
                mod.workshop?.takeIf {
                    it.state != WorkshopModState.ImportedUnpatched &&
                        it.state != WorkshopModState.DownloadFailed &&
                        it.state != WorkshopModState.Downloading &&
                        it.state != WorkshopModState.DownloadPaused &&
                        it.state != WorkshopModState.FileMissing
                }?.let { workshop ->
                    Spacer(modifier = Modifier.width(6.dp))
                    WorkshopStateBadge(workshop.state)
                }
                val effectivePriority = mod.effectivePriority
                if (effectivePriority != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    PriorityLoadBadge(priority = effectivePriority)
                }
            }
            Text(
                text = stringResource(R.string.main_mod_modid_format, resolvedModId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
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
    val workshopStatus = mod.workshop?.statusText.orEmpty()
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
}

@Composable
private fun ModCardLeadingImage(mod: ModItemUi) {
    val imagePath = mod.workshop?.localPreviewImagePath.orEmpty()
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = imagePath) {
        value = if (imagePath.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { ModCardPreviewImageLoader.load(imagePath) }
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_image_mod),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    }
}

private object ModCardPreviewImageLoader {
    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_SIZE_PX, TARGET_SIZE_PX)
            }
            BitmapFactory.decodeFile(path, options)?.also { cache.put(path, it) }
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

    private const val TARGET_SIZE_PX = 96
    private const val CACHE_SIZE_BYTES = 8 * 1024 * 1024
}

@Composable
private fun ModSuggestionIcon(
    enabled: Boolean,
    unread: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        painter = painterResource(R.drawable.ic_error_outline),
        contentDescription = null,
        tint = if (enabled) {
            if (unread) SuggestionUnreadColor else MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
        modifier = Modifier
            .size(22.dp)
            .clickable(enabled = enabled, onClick = onClick)
    )
}

private val SuggestionUnreadColor = Color(0xFFD2A72E)

@Composable
private fun ModImportPatchIcon(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Surface(
        color = Color.Transparent,
        contentColor = borderColor,
        shape = CircleShape,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .size(18.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_build),
                contentDescription = stringResource(R.string.main_mod_patch_badge_content_description),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun FavoriteBadge() {
    Surface(
        color = FavoritePink,
        contentColor = Color.White,
        shape = RoundedCornerShape(999.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_favorite_heart),
            contentDescription = stringResource(R.string.main_mod_favorite_badge_content_description),
            modifier = Modifier.padding(4.dp).size(12.dp)
        )
    }
}

private val FavoritePink = Color(0xFFE85D9E)

@Composable
private fun NewImportBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = stringResource(R.string.main_mod_new_import_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
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
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PriorityLoadBadge(priority: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = stringResource(R.string.main_mod_priority_badge_format, priority),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
