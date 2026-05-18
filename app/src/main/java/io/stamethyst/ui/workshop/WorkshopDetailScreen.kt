package io.stamethyst.ui.workshop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.backend.workshop.WorkshopItemDetails
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkshopDetailScreen(
    appId: UInt,
    publishedFileId: ULong,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: WorkshopViewModel = viewModel()
    val state = viewModel.uiState
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(appId, publishedFileId) {
        viewModel.load(context.applicationContext)
        viewModel.loadDetails(context.applicationContext, appId, publishedFileId)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val title = state.selected
                        ?.takeIf { it.summary.publishedFileId == publishedFileId }
                        ?.summary
                        ?.title
                        ?.ifBlank { null }
                    Column {
                        Text(
                            text = title ?: "模组详情",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "创意工坊详情 · ID $publishedFileId",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onOpenDownloadCenter) { Text("下载中心") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.detailLoadingId == publishedFileId && state.selected?.summary?.publishedFileId != publishedFileId -> {
                    item { LoadingDetailCard() }
                }
                state.errorMessage != null && state.selected?.summary?.publishedFileId != publishedFileId -> {
                    item {
                        DetailErrorCard(
                            message = state.errorMessage,
                            onRetry = { viewModel.loadDetails(context.applicationContext, appId, publishedFileId) },
                        )
                    }
                }
                state.selected?.summary?.publishedFileId == publishedFileId -> {
                    val details = state.selected
                    val downloadState = resolveWorkshopModDownloadState(
                        item = details.summary,
                        installedMods = state.installedMods,
                        downloadTasks = WorkshopDownloadCenterStore.tasks,
                    )
                    val downloadTask = WorkshopDownloadCenterStore.tasks.firstOrNull {
                        it.publishedFileId == publishedFileId
                    }
                    val dependencies = resolveWorkshopDependencyUiStates(
                        dependencies = details.dependencies,
                        installedMods = state.installedMods,
                        downloadTasks = WorkshopDownloadCenterStore.tasks,
                    )
                    item {
                        DetailContentCard(
                            details = details,
                            downloadState = downloadState,
                            dependencies = dependencies,
                            onDownload = {
                                requestNotificationPermissionIfNeeded()
                                viewModel.downloadSelected(context.applicationContext)
                            },
                        )
                    }
                    item {
                        DetailDownloadStatusCard(
                            downloadTask = downloadTask,
                            downloadState = downloadState,
                            onOpenDownloadCenter = onOpenDownloadCenter,
                        )
                    }
                }
                else -> {
                    item { LoadingDetailCard() }
                }
            }
        }
    }

    state.pendingDependencyDownload?.let { pending ->
        MissingWorkshopDependenciesDialog(
            modTitle = pending.details.summary.title,
            missingDependencies = pending.missingDependencies,
            onDismiss = { viewModel.dismissPendingDependencyDownload() },
            onConfirm = {
                requestNotificationPermissionIfNeeded()
                viewModel.confirmPendingDependencyDownload(context.applicationContext)
            },
        )
    }
}

@Composable
private fun DetailContentCard(
    details: WorkshopItemDetails,
    downloadState: WorkshopModDownloadState,
    dependencies: List<WorkshopDependencyUiState>,
    onDownload: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            DetailHeroHeader(
                details = details,
                downloadState = downloadState,
                onDownload = onDownload,
            )
            DetailMetaGrid(details = details)
            DetailDescription(text = details.summary.description)
            if (dependencies.isNotEmpty()) DependencySection(dependencies = dependencies)
        }
    }
}

@Composable
private fun DetailHeroHeader(
    details: WorkshopItemDetails,
    downloadState: WorkshopModDownloadState,
    onDownload: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkshopPreviewImage(
            url = details.summary.previewUrl,
            contentDescription = "${details.summary.title} 预览图",
            modifier = Modifier.size(112.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StateBadge(downloadState.statusLabel, downloadState.badgeTone())
            Text(
                text = details.summary.title.ifBlank { "未命名模组" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details.summary.authorName.ifBlank { "未知作者" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WorkshopDownloadActionButton(
                state = downloadState,
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun DetailMetaGrid(details: WorkshopItemDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailMetric(
                label = "大小",
                value = formatBytes(details.summary.fileSizeBytes),
                modifier = Modifier.weight(1f),
            )
            DetailMetric(
                label = "更新",
                value = formatDate(details.summary.updatedAtMillis),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailMetric(
                label = "Workshop ID",
                value = details.summary.publishedFileId.toString(),
                modifier = Modifier.weight(1f),
            )
            DetailMetric(
                label = "来源",
                value = details.downloadSourceLabel(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 68.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.66f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailDescription(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = text.ifBlank { "该模组暂未提供简介。" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun DependencySection(dependencies: List<WorkshopDependencyUiState>) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.36f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("前置模组", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "下载前会自动检查缺失项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
                    )
                }
                StateBadge("${dependencies.count { it.installed }}/${dependencies.size}", BadgeTone.Neutral)
            }
            dependencies.forEachIndexed { index, dependency ->
                DependencyRow(dependency = dependency)
                if (index != dependencies.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f))
                }
            }
        }
    }
}

@Composable
private fun DependencyRow(dependency: WorkshopDependencyUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = dependency.item.title.ifBlank { "Workshop ID ${dependency.item.publishedFileId}" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Workshop ID ${dependency.item.publishedFileId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.64f),
            )
        }
        StateBadge(dependency.statusLabel, if (dependency.installed) BadgeTone.Success else BadgeTone.Warning)
    }
}

@Composable
private fun StateBadge(text: String, tone: BadgeTone) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = when (tone) {
        BadgeTone.Success -> colorScheme.tertiaryContainer
        BadgeTone.Warning -> colorScheme.secondaryContainer
        BadgeTone.Error -> colorScheme.errorContainer
        BadgeTone.Active -> colorScheme.primary
        BadgeTone.Neutral -> colorScheme.surfaceVariant
    }
    val contentColor = when (tone) {
        BadgeTone.Success -> colorScheme.onTertiaryContainer
        BadgeTone.Warning -> colorScheme.onSecondaryContainer
        BadgeTone.Error -> colorScheme.onErrorContainer
        BadgeTone.Active -> colorScheme.onPrimary
        BadgeTone.Neutral -> colorScheme.onSurfaceVariant
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private enum class BadgeTone { Success, Warning, Error, Active, Neutral }

@Composable
private fun DetailDownloadStatusCard(
    downloadTask: WorkshopDownloadTaskUi?,
    downloadState: WorkshopModDownloadState,
    onOpenDownloadCenter: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("下载状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = downloadTask?.message?.ifBlank { null } ?: downloadState.statusLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StateBadge(downloadState.statusLabel, downloadState.badgeTone())
            }
            if (downloadTask != null) {
                DownloadProgressLine(downloadTask)
            } else {
                Text(
                    text = downloadState.idleHint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onOpenDownloadCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text("查看下载中心") }
        }
    }
}

@Composable
private fun DownloadProgressLine(task: WorkshopDownloadTaskUi) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { (task.progressPercent ?: 0).coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StateBadge(task.progressSummary(), BadgeTone.Neutral)
            task.fileProgressText()?.let { StateBadge("文件 $it", BadgeTone.Neutral) }
            task.chunkProgressText()?.let { StateBadge("分块 $it", BadgeTone.Neutral) }
        }
    }
}

@Composable
private fun DetailErrorCard(message: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("详情加载失败", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text("重试加载") }
        }
    }
}

@Composable
private fun LoadingDetailCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("正在加载模组详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun WorkshopItemDetails.downloadSourceLabel(): String = when {
    hcontentFile != null && !fileUrl.isNullOrBlank() -> "Steam / 直链"
    hcontentFile != null -> "Steam 内容"
    !fileUrl.isNullOrBlank() -> "直链"
    else -> "待解析"
}

private fun WorkshopModDownloadState.badgeTone(): BadgeTone = when (this) {
    WorkshopModDownloadState.Downloaded -> BadgeTone.Success
    WorkshopModDownloadState.NotDownloaded -> BadgeTone.Neutral
    WorkshopModDownloadState.UpdateAvailable -> BadgeTone.Warning
    WorkshopModDownloadState.Queued,
    WorkshopModDownloadState.Downloading,
    WorkshopModDownloadState.Paused,
    WorkshopModDownloadState.Cancelling -> BadgeTone.Active
    WorkshopModDownloadState.DownloadFailed -> BadgeTone.Error
}

private fun WorkshopModDownloadState.idleHint(): String = when (this) {
    WorkshopModDownloadState.Downloaded -> "已记录到本地模组列表，可返回模组页查看。"
    WorkshopModDownloadState.NotDownloaded -> "点击上方按钮开始下载，缺失前置会在下载前提示。"
    WorkshopModDownloadState.UpdateAvailable -> "远端版本更新，可重新下载覆盖本地记录。"
    WorkshopModDownloadState.Queued -> "任务已在下载中心排队。"
    WorkshopModDownloadState.Downloading -> "任务正在下载，可在下载中心查看详情。"
    WorkshopModDownloadState.Paused -> "下载已暂停，可继续该任务。"
    WorkshopModDownloadState.Cancelling -> "正在取消下载任务。"
    WorkshopModDownloadState.DownloadFailed -> "上次下载失败，可重试或进入下载中心查看错误。"
}

private fun WorkshopDownloadTaskUi.progressSummary(): String {
    val percentText = progressPercent?.let { "${it.coerceIn(0, 100)}%" }
    val bytesText = when {
        totalBytes != null && totalBytes > 0L -> "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
        downloadedBytes > 0L -> formatBytes(downloadedBytes)
        fileSizeBytes > 0L -> formatBytes(fileSizeBytes)
        else -> "等待大小信息"
    }
    return listOfNotNull(percentText, bytesText).joinToString(" · ")
}

private fun WorkshopDownloadTaskUi.fileProgressText(): String? {
    val total = totalFiles ?: return null
    val completed = completedFiles ?: 0
    return "$completed/$total"
}

private fun WorkshopDownloadTaskUi.chunkProgressText(): String? {
    val total = totalChunks ?: return null
    val completed = completedChunks ?: 0
    return "$completed/$total"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "未知"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        "${String.format(Locale.US, "%.1f", value)} ${units[unitIndex]}"
    }
}

private fun formatDate(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "未知"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))
}
