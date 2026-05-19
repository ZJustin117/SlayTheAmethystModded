package io.stamethyst.ui.workshop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopComment
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkshopDetailScreen(
    appId: UInt,
    publishedFileId: ULong,
    viewModel: WorkshopViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    val context = LocalContext.current
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

    LaunchedEffect(state.downloadInProgress) {
        if (!state.downloadInProgress) return@LaunchedEffect
        while (true) {
            delay(1000L)
            viewModel.refreshDownloadState(context.applicationContext)
        }
    }

    val selectedDetails = state.selected?.takeIf { it.summary.publishedFileId == publishedFileId }
    val primaryContentState = when {
        state.detailLoadingId == publishedFileId && selectedDetails == null -> DetailPrimaryContentState.Loading
        state.errorMessage != null && selectedDetails == null -> DetailPrimaryContentState.Error
        selectedDetails != null -> DetailPrimaryContentState.Content
        else -> DetailPrimaryContentState.Loading
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
            item(key = "workshop-detail-primary") {
                AnimatedContent(
                    targetState = primaryContentState,
                    label = "workshop-detail-primary-content",
                ) { targetState ->
                    when (targetState) {
                        DetailPrimaryContentState.Loading -> LoadingDetailCard()
                        DetailPrimaryContentState.Error -> DetailErrorCard(
                            message = state.errorMessage.orEmpty(),
                            onRetry = { viewModel.loadDetails(context.applicationContext, appId, publishedFileId) },
                        )
                        DetailPrimaryContentState.Content -> selectedDetails?.let { details ->
                            DetailModCard(
                                details = details,
                                downloadState = resolveWorkshopModDownloadState(
                                    item = details.summary,
                                    installedMods = state.installedMods,
                                    downloadTasks = WorkshopDownloadCenterStore.tasks,
                                ),
                                onDownload = {
                                    requestNotificationPermissionIfNeeded()
                                    viewModel.downloadSelected(context.applicationContext)
                                },
                            )
                        } ?: LoadingDetailCard()
                    }
                }
            }

            selectedDetails?.let { details ->
                val dependencies = resolveWorkshopDependencyUiStates(
                    dependencies = details.dependencies,
                    installedMods = state.installedMods,
                    downloadTasks = WorkshopDownloadCenterStore.tasks,
                )
                if (dependencies.isNotEmpty()) {
                    item(key = "workshop-detail-dependencies") {
                        DependencyCard(
                            modifier = Modifier.animateItem(),
                            dependencies = dependencies,
                            onDownloadDependency = { dependency ->
                                requestNotificationPermissionIfNeeded()
                                viewModel.download(context.applicationContext, dependency)
                            },
                            onOpenDependency = onOpenDetails,
                        )
                    }
                }
                item(key = "workshop-detail-description") {
                    DetailDescriptionCard(
                        modifier = Modifier.animateItem(),
                        publishedFileId = details.summary.publishedFileId,
                        text = details.summary.description,
                    )
                }
                item(key = "workshop-detail-comments") {
                    DetailCommentsCard(
                        modifier = Modifier.animateItem(),
                        details = details,
                        isLoading = state.commentLoadingId == publishedFileId,
                        errorMessage = state.commentErrorMessage,
                        onRetry = { viewModel.retryWorkshopCommentsPage(context.applicationContext) },
                        onPreviousPage = { viewModel.loadPreviousWorkshopCommentsPage(context.applicationContext) },
                        onNextPage = { viewModel.loadNextWorkshopCommentsPage(context.applicationContext) },
                    )
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
private fun DetailModCard(
    details: WorkshopItemDetails,
    downloadState: WorkshopModDownloadState,
    onDownload: () -> Unit,
) {
    Card(
        colors = workshopDetailCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkshopPreviewImage(
                    publishedFileId = details.summary.publishedFileId,
                    url = details.summary.previewUrl,
                    contentDescription = "${details.summary.title} 预览图",
                    modifier = Modifier.size(112.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            DetailMetaGrid(details = details)
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
                label = "上次更新",
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
                label = "下载次数",
                value = formatCount(details.summary.downloadCount),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 68.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun DependencyCard(
    modifier: Modifier = Modifier,
    dependencies: List<WorkshopDependencyUiState>,
    onDownloadDependency: (WorkshopItemSummary) -> Unit,
    onOpenDependency: (WorkshopItemSummary) -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("前置模组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (dependencies.isEmpty()) "该模组没有声明前置模组。" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (dependencies.isEmpty()) "0" else "${dependencies.count { it.installed }}/${dependencies.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (dependencies.isNotEmpty()) {
                dependencies.forEach { dependency ->
                    DependencyItemCard(
                        dependency = dependency,
                        onDownload = { onDownloadDependency(dependency.item) },
                        onOpenDetails = { onOpenDependency(dependency.item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DependencyItemCard(
    dependency: WorkshopDependencyUiState,
    onDownload: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    Card(
        onClick = onOpenDetails,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        colors = workshopDetailCardColors(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkshopPreviewImage(
                publishedFileId = dependency.item.publishedFileId,
                url = dependency.item.previewUrl,
                contentDescription = "${dependency.item.title} 预览图",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = dependency.item.title.ifBlank { "Workshop ID ${dependency.item.publishedFileId}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = dependency.item.authorName.ifBlank { dependency.item.description.ifBlank { dependency.statusLabel } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Workshop ID ${dependency.item.publishedFileId} · ${dependency.statusLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WorkshopDownloadActionButton(
                state = if (dependency.defaultInstalled) WorkshopModDownloadState.Downloaded else dependency.downloadState,
                onClick = onDownload,
                iconOnly = true,
            )
        }
    }
}

@Composable
private fun DetailDescriptionCard(
    modifier: Modifier = Modifier,
    publishedFileId: ULong,
    text: String,
) {
    var expanded by rememberSaveable(publishedFileId.toString()) { mutableStateOf(false) }
    val description = text.ifBlank { "该模组暂未提供简介。" }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right),
                        contentDescription = if (expanded) "收起简介" else "展开简介",
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailCommentsCard(
    modifier: Modifier = Modifier,
    details: WorkshopItemDetails,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val showInlineLoading = isLoading && details.comments.isEmpty()
    val showOverlayLoading = isLoading && details.comments.isNotEmpty()
    val overlayAlpha by animateFloatAsState(
        targetValue = if (showOverlayLoading) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "workshop-detail-comments-page-loading-alpha",
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 220)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("评论", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = commentsSummary(details, showInlineLoading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(
                                onClick = onRetry,
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("重试加载评论") }
                        }
                    }
                }
                if (showInlineLoading) {
                    CommentsLoadingIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
                if (details.commentTotalPages?.let { it > 1 } == true ||
                    details.hasPreviousCommentPage ||
                    details.hasNextCommentPage
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onPreviousPage,
                            enabled = !isLoading && details.hasPreviousCommentPage,
                            modifier = Modifier.weight(1f),
                        ) { Text("上一页") }
                        OutlinedButton(
                            onClick = onNextPage,
                            enabled = !isLoading && details.hasNextCommentPage,
                            modifier = Modifier.weight(1f),
                        ) { Text("下一页") }
                    }
                }
                details.comments.forEach { comment -> CommentItemCard(comment = comment) }
            }

            if (showOverlayLoading || overlayAlpha > 0f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(overlayAlpha),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(999.dp),
                    tonalElevation = 2.dp,
                ) {
                    CommentsLoadingIndicator(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentsLoadingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = " 正在加载评论",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommentItemCard(comment: WorkshopComment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = comment.authorName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatCommentTime(comment),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = comment.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
    Card(colors = workshopDetailCardColors()) {
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("正在加载模组详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun workshopDetailCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
)

private enum class DetailPrimaryContentState {
    Loading,
    Error,
    Content,
}

private fun commentsSummary(details: WorkshopItemDetails, isLoading: Boolean): String = when {
    isLoading && details.comments.isEmpty() -> "评论区加载中，每页显示 5 条。"
    details.commentCount == 0L -> "这件模组目前没有公开评论。"
    details.commentCount != null && details.commentTotalPages != null ->
        "当前第 ${details.commentPage} / ${details.commentTotalPages} 页，Steam 共 ${formatCount(details.commentCount)} 条评论。"
    details.commentCount != null ->
        "当前第 ${details.commentPage} 页，Steam 共 ${formatCount(details.commentCount)} 条评论。"
    details.comments.isNotEmpty() ->
        "当前第 ${details.commentPage} 页，已加载 ${details.comments.size} 条公开评论。"
    else -> "暂时没有读取到公开评论。"
}

private fun formatCommentTime(comment: WorkshopComment): String =
    comment.postedEpochSeconds?.let { formatDate(it * 1000L) }
        ?: comment.postedDisplayText.ifBlank { "时间未知" }

private fun formatCount(value: Long): String {
    if (value <= 0L) return "未知"
    return when {
        value >= 100_000_000L -> String.format(Locale.US, "%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format(Locale.US, "%.1f万", value / 10_000.0)
        else -> value.toString()
    }
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
