package io.stamethyst.ui.workshop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadLogService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.isActiveDownload
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkshopDownloadCenterScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPause: (WorkshopDownloadTaskUi) -> Unit = {},
    onResume: (WorkshopDownloadTaskUi) -> Unit = {},
    onCancel: (WorkshopDownloadTaskUi) -> Unit = {},
    onRetry: (WorkshopDownloadTaskUi) -> Unit = {},
) {
    val activity = LocalActivity.current
    val tasks = WorkshopDownloadCenterStore.tasks
    var selectedTaskId by remember { mutableStateOf<ULong?>(null) }
    var pendingExportTaskId by remember { mutableStateOf<ULong?>(null) }
    val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val taskId = pendingExportTaskId
        pendingExportTaskId = null
        val task = taskId?.let(WorkshopDownloadCenterStore::find) ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching { WorkshopDownloadLogService.exportLog(requireNotNull(activity), task.toRecord(), uri) }
                .onSuccess { Toast.makeText(activity, "下载日志已导出", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(activity, "导出下载日志失败：${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WorkshopDownloadCenterStore.refresh()
            delay(1000)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("下载中心")
                        Text(
                            text = "查看创意工坊下载进度和任务详情",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tasks.isEmpty()) {
                item { EmptyDownloadCenterCard(onBack = onBack) }
            } else {
                items(tasks, key = { it.publishedFileId.toString() }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onClick = { selectedTaskId = task.publishedFileId },
                        onPause = { onPause(task) },
                        onResume = { onResume(task) },
                        onCancel = { onCancel(task) },
                        onRetry = { onRetry(task) },
                    )
                }
            }
        }
    }

    selectedTaskId?.let { taskId ->
        val task = tasks.firstOrNull { it.publishedFileId == taskId }
        if (task == null) {
            selectedTaskId = null
            return@let
        }
        DownloadTaskDetailDialog(
            task = task,
            onDismiss = { selectedTaskId = null },
            onPause = { onPause(task) },
            onResume = { onResume(task) },
            onCancel = {
                onCancel(task)
                selectedTaskId = null
            },
            onRetry = { onRetry(task) },
            onExportLog = {
                pendingExportTaskId = task.publishedFileId
                exportLogLauncher.launch(WorkshopDownloadLogService.fileName(task.toRecord()))
            },
            onShareLog = { activity?.shareDownloadLog(task) },
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: WorkshopDownloadTaskUi,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick),
        colors = when (task.status) {
            WorkshopDownloadTaskStatus.Failed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            WorkshopDownloadTaskStatus.Completed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else -> CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkshopDownloadPreviewImage(
                    url = task.previewUrl,
                    contentDescription = "${task.title} 预览图",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = task.progressSummary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AssistChip(onClick = onClick, label = { Text(task.status.downloadLabel()) })
                    }
                    DownloadProgressIndicator(task = task)
                    Text(
                        text = task.message.ifBlank { task.status.defaultMessage() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DownloadTaskActions(
                task = task,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
                compact = true,
            )
        }
    }
}

@Composable
private fun DownloadTaskActions(
    task: WorkshopDownloadTaskUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    compact: Boolean,
) {
    if (task.status == WorkshopDownloadTaskStatus.Completed) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            when (task.status) {
                WorkshopDownloadTaskStatus.Queued,
                WorkshopDownloadTaskStatus.Resolving,
                WorkshopDownloadTaskStatus.Downloading -> OutlinedButton(onClick = onPause) { Text("暂停") }
                WorkshopDownloadTaskStatus.Pausing -> OutlinedButton(enabled = false, onClick = {}) { Text("暂停中") }
                WorkshopDownloadTaskStatus.Cancelling -> OutlinedButton(enabled = false, onClick = {}) { Text("取消中") }
                WorkshopDownloadTaskStatus.Paused -> OutlinedButton(onClick = onResume) { Text("继续") }
                WorkshopDownloadTaskStatus.Failed,
                WorkshopDownloadTaskStatus.Cancelled -> OutlinedButton(onClick = onRetry) { Text("重试") }
                WorkshopDownloadTaskStatus.Completed -> Unit
            }
            TextButton(
                enabled = task.status != WorkshopDownloadTaskStatus.Cancelling,
                onClick = onCancel,
            ) { Text("取消") }
            return@Row
        }

        when (task.status) {
            WorkshopDownloadTaskStatus.Queued,
            WorkshopDownloadTaskStatus.Resolving,
            WorkshopDownloadTaskStatus.Downloading -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_pause,
                contentDescription = "暂停",
                onClick = onPause,
            )
            WorkshopDownloadTaskStatus.Pausing -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_pause,
                contentDescription = "暂停中",
                enabled = false,
                onClick = {},
            )
            WorkshopDownloadTaskStatus.Cancelling -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_delete,
                contentDescription = "取消中",
                enabled = false,
                onClick = {},
            )
            WorkshopDownloadTaskStatus.Paused -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_play_arrow,
                contentDescription = "继续",
                onClick = onResume,
            )
            WorkshopDownloadTaskStatus.Failed,
            WorkshopDownloadTaskStatus.Cancelled -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_workshop_retry,
                contentDescription = "重试",
                onClick = onRetry,
            )
            WorkshopDownloadTaskStatus.Completed -> Unit
        }
        DownloadDetailIconButton(
            iconResId = R.drawable.ic_delete,
            contentDescription = "取消任务",
            enabled = task.status != WorkshopDownloadTaskStatus.Cancelling,
            onClick = onCancel,
        )
    }
}

@Composable
private fun DownloadDetailIconButton(
    iconResId: Int,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun DownloadProgressIndicator(task: WorkshopDownloadTaskUi) {
    val percent = task.progressPercent
    if (percent != null) {
        val animatedProgress by animateFloatAsState(
            targetValue = percent.coerceIn(0, 100) / 100f,
            label = "workshopDownloadProgress",
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (task.status.isActiveDownload()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DownloadTaskDetailDialog(
    task: WorkshopDownloadTaskUi,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onExportLog: () -> Unit,
    onShareLog: () -> Unit,
) {
    var showLogActionDialog by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, modifier = Modifier.weight(1f))
                IconButton(onClick = { showLogActionDialog = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_log),
                        contentDescription = "下载日志",
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkshopDownloadPreviewImage(
                        url = task.previewUrl,
                        contentDescription = "${task.title} 预览图",
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text(task.status.downloadLabel()) })
                        Text(task.progressSummary(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DownloadProgressIndicator(task = task)
                DetailRow(label = "状态", value = task.message.ifBlank { task.status.defaultMessage() })
                if (task.status == WorkshopDownloadTaskStatus.Paused) {
                    Text(
                        text = "继续下载会重新启动该任务。当前底层暂不支持断点续传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.status == WorkshopDownloadTaskStatus.Failed && task.errorMessage.isNotBlank()) {
                    DetailRow(label = "错误", value = task.errorMessage)
                    task.errorClass.takeIf(String::isNotBlank)?.let { DetailRow(label = "类型", value = it) }
                }
                DetailRow(label = "Workshop ID", value = task.publishedFileId.toString())
                task.authorName.takeIf(String::isNotBlank)?.let { DetailRow(label = "作者", value = it) }
                DetailRow(label = "大小", value = task.totalBytes?.let(::formatBytes) ?: formatBytes(task.fileSizeBytes))
                task.fileProgressText()?.let { DetailRow(label = "文件", value = it) }
                task.chunkProgressText()?.let { DetailRow(label = "分块", value = it) }
                task.description.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DownloadTaskActions(
                    task = task,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    compact = false,
                )
                DownloadDetailIconButton(
                    iconResId = R.drawable.ic_close,
                    contentDescription = "关闭",
                    onClick = onDismiss,
                )
            }
        },
    )
    if (showLogActionDialog) {
        AlertDialog(
            onDismissRequest = { showLogActionDialog = false },
            title = { Text("下载日志") },
            text = { Text("选择导出到文件，或通过系统分享发送本次下载的详细日志。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogActionDialog = false
                    onExportLog()
                }) { Text("导出日志") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        showLogActionDialog = false
                        onShareLog()
                    }) { Text("分享日志") }
                    TextButton(onClick = { showLogActionDialog = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WorkshopDownloadPreviewImage(url: String, contentDescription: String) {
    val imageState by produceState<DownloadPreviewImageState>(initialValue = DownloadPreviewImageState.Loading, key1 = url) {
        value = when {
            url.isBlank() -> DownloadPreviewImageState.Failed
            else -> withContext(Dispatchers.IO) {
                WorkshopDownloadPreviewImageLoader.load(url)?.let(DownloadPreviewImageState::Loaded) ?: DownloadPreviewImageState.Failed
            }
        }
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CardDefaults.shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = imageState) {
            DownloadPreviewImageState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth(0.62f))
            DownloadPreviewImageState.Failed -> Text("MOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DownloadPreviewImageState.Loaded -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun EmptyDownloadCenterCard(onBack: () -> Unit) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("暂无下载任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "从创意工坊选择模组并点击下载后，任务会显示在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onBack) { Text("返回创意工坊") }
        }
    }
}

private sealed interface DownloadPreviewImageState {
    data object Loading : DownloadPreviewImageState
    data object Failed : DownloadPreviewImageState
    data class Loaded(val bitmap: Bitmap) : DownloadPreviewImageState
}

private object WorkshopDownloadPreviewImageLoader {
    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val client = OkHttpClient()

    fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                BitmapFactory.decodeStream(response.body.byteStream())?.also { bitmap ->
                    cache.put(url, bitmap)
                }
            }
        }.getOrNull()
    }

    private const val CACHE_SIZE_BYTES = 16 * 1024 * 1024
}

private fun WorkshopDownloadTaskStatus.downloadLabel(): String = when (this) {
    WorkshopDownloadTaskStatus.Queued -> "等待"
    WorkshopDownloadTaskStatus.Resolving -> "解析"
    WorkshopDownloadTaskStatus.Downloading -> "下载中"
    WorkshopDownloadTaskStatus.Pausing -> "暂停中"
    WorkshopDownloadTaskStatus.Cancelling -> "取消中"
    WorkshopDownloadTaskStatus.Paused -> "已暂停"
    WorkshopDownloadTaskStatus.Completed -> "完成"
    WorkshopDownloadTaskStatus.Failed -> "失败"
    WorkshopDownloadTaskStatus.Cancelled -> "已取消"
}

private fun WorkshopDownloadTaskStatus.defaultMessage(): String = when (this) {
    WorkshopDownloadTaskStatus.Queued -> "等待下载"
    WorkshopDownloadTaskStatus.Resolving -> "正在解析下载内容"
    WorkshopDownloadTaskStatus.Downloading -> "正在下载"
    WorkshopDownloadTaskStatus.Pausing -> "正在暂停"
    WorkshopDownloadTaskStatus.Cancelling -> "正在取消"
    WorkshopDownloadTaskStatus.Paused -> "下载已暂停，可继续"
    WorkshopDownloadTaskStatus.Completed -> "下载完成，可到模组页安装"
    WorkshopDownloadTaskStatus.Failed -> "下载失败，可重试"
    WorkshopDownloadTaskStatus.Cancelled -> "下载已取消，可重试"
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
    return "$completed / $total"
}

private fun WorkshopDownloadTaskUi.chunkProgressText(): String? {
    val total = totalChunks ?: return null
    val completed = completedChunks ?: 0
    return "$completed / $total"
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
        "${bytes} ${units[unitIndex]}"
    } else {
        "${String.format(java.util.Locale.US, "%.1f", value)} ${units[unitIndex]}"
    }
}

private fun Activity.shareDownloadLog(task: WorkshopDownloadTaskUi) {
    runCatching {
        val shareIntent = WorkshopDownloadLogService.prepareShareIntent(this, task.toRecord())
        startActivity(Intent.createChooser(shareIntent, "分享下载日志"))
    }.onFailure {
        Toast.makeText(this, "分享下载日志失败：${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
    }
}
