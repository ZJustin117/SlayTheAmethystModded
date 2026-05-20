@file:OptIn(ExperimentalMaterial3Api::class)

package io.stamethyst.ui.workshop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.backend.workshop.WorkshopBrowseTimeFilter
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.ui.CollapsibleFloatingGlassHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun WorkshopScreen(
    viewModel: WorkshopViewModel,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onOpenSteamLogin: () -> Unit,
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
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val headerHazeState = rememberHazeState()
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerCollapsed = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > with(density) { 24.dp.roundToPx() }
    val measuredHeaderHeight = with(density) { headerHeightPx.toDp() }
    val headerContentTopInset = (if (headerHeightPx == 0) 102.dp else measuredHeaderHeight) + 16.dp
    val refreshIndicatorTopInset = (if (headerHeightPx == 0) 102.dp else measuredHeaderHeight) + 8.dp
    val pullToRefreshState = rememberPullToRefreshState()
    val activeDownloadTaskCount = WorkshopDownloadCenterStore.tasks.count { it.status.isActiveDownload() }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(WorkshopBrowseSort.MostPopular) }
    var timeFilter by rememberSaveable { mutableStateOf(WorkshopBrowseTimeFilter.OneWeek) }
    fun searchWithPopularAllTime() {
        val searchSort = WorkshopBrowseSort.MostPopular
        val searchTimeFilter = WorkshopBrowseTimeFilter.AllTime
        sort = searchSort
        timeFilter = searchTimeFilter
        viewModel.search(context.applicationContext, query, searchSort, searchTimeFilter)
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 4
        }
    }

    LaunchedEffect(Unit) {
        viewModel.load(context.applicationContext)
    }

    LaunchedEffect(state.downloadInProgress) {
        if (!state.downloadInProgress) return@LaunchedEffect
        while (true) {
            delay(1000L)
            viewModel.refreshDownloadState(context.applicationContext)
        }
    }

    LaunchedEffect(shouldLoadMore, state.items.size, state.hasMorePages) {
        if (shouldLoadMore) {
            viewModel.loadNextPage(context.applicationContext)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 16.dp, top = 18.dp, end = 16.dp),
    ) {
        PullToRefreshBox(
            isRefreshing = state.browseLoading && state.items.isNotEmpty(),
            onRefresh = { viewModel.refreshBrowse(context.applicationContext) },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = refreshIndicatorTopInset),
                    isRefreshing = state.browseLoading && state.items.isNotEmpty(),
                    state = pullToRefreshState,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = headerHazeState)
                .fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(modifier = Modifier.height(headerContentTopInset))
                }
                if (!state.steamLoggedIn) {
                    item(key = "workshop-status-header") {
                        WorkshopStatusHeader(onOpenSteamLogin = onOpenSteamLogin)
                    }
                }

                item(key = "workshop-search-panel") {
                    SearchPanel(
                        query = query,
                        loading = state.browseLoading,
                        sort = sort,
                        timeFilter = timeFilter,
                        onQueryChange = { query = it },
                        onSearch = ::searchWithPopularAllTime,
                        onSortChange = { selectedSort ->
                            sort = selectedSort
                            viewModel.search(context.applicationContext, query, selectedSort, timeFilter)
                        },
                        onTimeFilterChange = { selectedTimeFilter ->
                            timeFilter = selectedTimeFilter
                            viewModel.search(context.applicationContext, query, sort, selectedTimeFilter)
                        },
                    )
                }

                if (state.errorMessage != null) {
                    item(key = "workshop-error") {
                        ErrorPanel(
                            modifier = Modifier.animateItem(),
                            message = state.errorMessage,
                            onRetry = { viewModel.search(context.applicationContext, query, sort, timeFilter) },
                        )
                    }
                }

                item(key = "workshop-section-title") {
                    SectionTitle(
                        title = "工坊列表",
                        subtitle = when {
                            state.browseLoading -> "正在加载"
                            state.items.isEmpty() -> "没有结果"
                            else -> "${state.items.size} 个条目"
                        },
                    )
                }

                when {
                    state.browseLoading && state.items.isEmpty() -> {
                        item(key = "workshop-loading") {
                            LoadingPanel(
                                modifier = Modifier.animateItem(),
                                text = "正在连接 Steam 创意工坊",
                            )
                        }
                    }
                    state.items.isEmpty() && state.errorMessage == null -> {
                        item(key = "workshop-empty") {
                            EmptyPanel(
                                modifier = Modifier.animateItem(),
                                onRetry = { viewModel.search(context.applicationContext, query, sort, timeFilter) },
                            )
                        }
                    }
                    else -> {
                        items(state.items, key = { it.publishedFileId.toString() }) { item ->
                            val downloadState = resolveWorkshopModDownloadState(
                                item = item,
                                installedMods = state.installedMods,
                                downloadTasks = WorkshopDownloadCenterStore.tasks,
                            )
                            WorkshopItemCard(
                                modifier = Modifier.animateItem(),
                                item = item,
                                downloadState = downloadState,
                                onClick = { onOpenDetails(item) },
                                onDownload = {
                                    requestNotificationPermissionIfNeeded()
                                    viewModel.download(context.applicationContext, item)
                                },
                            )
                        }
                        item(key = "workshop-pagination-footer") {
                            BrowsePaginationFooter(
                                modifier = Modifier.animateItem(),
                                loading = state.loadingMore,
                                hasMorePages = state.hasMorePages,
                                itemCount = state.items.size,
                            )
                        }
                    }
                }
            }
        }

        CollapsibleFloatingGlassHeader(
            modifier = Modifier.align(Alignment.TopCenter),
            hazeState = headerHazeState,
            collapsed = headerCollapsed,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            onHeightChanged = {
                if (!headerCollapsed) {
                    headerHeightPx = maxOf(headerHeightPx, it)
                }
            },
            pinnedContent = {
                WorkshopHeaderPinnedContent(
                    showBackButton = showBackButton,
                    activeDownloadTaskCount = activeDownloadTaskCount,
                    onBack = onBack,
                    onOpenDownloadCenter = onOpenDownloadCenter,
                )
            },
        )
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
private fun WorkshopHeaderPinnedContent(
    showBackButton: Boolean,
    activeDownloadTaskCount: Int,
    onBack: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    val downloadCenterDescription = if (activeDownloadTaskCount > 0) {
        "下载中心，正在进行 $activeDownloadTaskCount 个下载任务"
    } else {
        "下载中心"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "模组市场",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "浏览并下载创意工坊模组",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onOpenDownloadCenter,
            modifier = Modifier.size(48.dp),
        ) {
            BadgedBox(
                badge = {
                    if (activeDownloadTaskCount > 0) {
                        Badge { Text(if (activeDownloadTaskCount > 99) "99+" else activeDownloadTaskCount.toString()) }
                    }
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_workshop_download),
                    contentDescription = downloadCenterDescription,
                )
            }
        }
        if (showBackButton) {
            TextButton(onClick = onBack) { Text("返回") }
        }
    }
}

@Composable
private fun BrowsePaginationFooter(
    modifier: Modifier = Modifier,
    loading: Boolean,
    hasMorePages: Boolean,
    itemCount: Int,
) {
    val showFooter = loading || (!hasMorePages && itemCount > 0)
    if (!showFooter) {
        Spacer(modifier = Modifier.height(72.dp))
        return
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("正在加载更多模组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Text("已经到底了", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkshopStatusHeader(
    onOpenSteamLogin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Steam 尚未登录，部分模组不会显示。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSteamLogin) { Text("登录 Steam") }
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    loading: Boolean,
    sort: WorkshopBrowseSort,
    timeFilter: WorkshopBrowseTimeFilter,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSortChange: (WorkshopBrowseSort) -> Unit,
    onTimeFilterChange: (WorkshopBrowseTimeFilter) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var timeMenuExpanded by remember { mutableStateOf(false) }
    fun submitSearch() {
        if (!loading) {
            keyboardController?.hide()
            onSearch()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchBar(
                modifier = Modifier.fillMaxWidth(),
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearch = { submitSearch() },
                        expanded = false,
                        onExpandedChange = {},
                        enabled = !loading,
                        placeholder = { Text("搜索模组") },
                        trailingIcon = {
                            TextButton(
                                enabled = !loading,
                                onClick = { submitSearch() },
                            ) { Text("搜索") }
                        },
                    )
                },
                expanded = false,
                onExpandedChange = {},
            ) {}
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "留空浏览推荐/默认排序条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sort.usesTimeFilter) {
                        Box {
                            OutlinedButton(
                                enabled = !loading,
                                onClick = { timeMenuExpanded = true }
                            ) {
                                Text(timeFilter.displayName)
                            }
                            DropdownMenu(
                                expanded = timeMenuExpanded,
                                onDismissRequest = { timeMenuExpanded = false }
                            ) {
                                WorkshopBrowseTimeFilter.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName) },
                                        onClick = {
                                            timeMenuExpanded = false
                                            if (option != timeFilter) {
                                                onTimeFilterChange(option)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Box {
                        OutlinedButton(enabled = !loading, onClick = { sortMenuExpanded = true }) {
                            Text(sort.displayName)
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            WorkshopBrowseSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.displayName) },
                                    onClick = {
                                        sortMenuExpanded = false
                                        if (option != sort) {
                                            onSortChange(option)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = loading,
                label = "workshop-search-loading"
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WorkshopItemCard(
    modifier: Modifier = Modifier,
    item: WorkshopItemSummary,
    downloadState: WorkshopModDownloadState,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkshopPreviewImage(
                publishedFileId = item.publishedFileId,
                url = item.previewUrl,
                contentDescription = "${item.title} 预览图",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = item.authorName.ifBlank { item.description.ifBlank { "点击查看详情" } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WorkshopDownloadActionButton(
                state = downloadState,
                onClick = onDownload,
                iconOnly = true,
            )
        }
    }
}

@Composable
internal fun WorkshopDownloadActionButton(
    state: WorkshopModDownloadState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
) {
    val enabled = state.canStartDownload
    if (iconOnly) {
        IconButton(
            modifier = modifier.size(48.dp),
            enabled = enabled,
            onClick = onClick,
        ) {
            Icon(
                painter = painterResource(state.actionIconRes),
                contentDescription = state.actionLabel,
            )
        }
        return
    }
    when (state) {
        WorkshopModDownloadState.Downloaded -> OutlinedButton(
            modifier = modifier,
            enabled = false,
            onClick = onClick,
        ) { Text(state.actionLabel) }
        WorkshopModDownloadState.NotDownloaded -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(state.actionLabel) }
        WorkshopModDownloadState.UpdateAvailable -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(state.actionLabel) }
        WorkshopModDownloadState.Paused -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(state.actionLabel) }
        WorkshopModDownloadState.DownloadFailed -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(state.actionLabel) }
        WorkshopModDownloadState.Queued,
        WorkshopModDownloadState.Cancelling,
        WorkshopModDownloadState.Downloading -> OutlinedButton(
            modifier = modifier,
            enabled = false,
            onClick = onClick,
        ) { Text(state.actionLabel) }
    }
}

@Composable
internal fun MissingWorkshopDependenciesDialog(
    modTitle: String,
    missingDependencies: List<WorkshopItemSummary>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安装前置模组？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$modTitle 需要以下未安装前置，是否一并加入下载队列？")
                missingDependencies.forEach { dependency ->
                    Text(
                        text = "${dependency.title.ifBlank { "Workshop ID ${dependency.publishedFileId}" }} (${dependency.publishedFileId})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("安装并下载") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val WorkshopModDownloadState.actionIconRes: Int
    get() = when (this) {
        WorkshopModDownloadState.Downloaded -> R.drawable.ic_workshop_installed
        WorkshopModDownloadState.NotDownloaded -> R.drawable.ic_workshop_download
        WorkshopModDownloadState.UpdateAvailable -> R.drawable.ic_workshop_update
        WorkshopModDownloadState.Queued -> R.drawable.ic_workshop_queue
        WorkshopModDownloadState.Downloading -> R.drawable.ic_workshop_downloading
        WorkshopModDownloadState.Paused -> R.drawable.ic_workshop_paused
        WorkshopModDownloadState.Cancelling -> R.drawable.ic_workshop_cancelling
        WorkshopModDownloadState.DownloadFailed -> R.drawable.ic_workshop_retry
    }

@Composable
internal fun WorkshopPreviewImage(
    publishedFileId: ULong,
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier.size(72.dp),
) {
    val context = LocalContext.current
    val imageState by produceState<PreviewImageState>(
        initialValue = PreviewImageState.Loading,
        key1 = publishedFileId,
        key2 = url,
    ) {
        value = when {
            url.isBlank() -> PreviewImageState.Failed
            else -> withContext(Dispatchers.IO) {
                WorkshopPreviewCacheStore.load(context.applicationContext, publishedFileId, url)
                    ?.let(PreviewImageState::Loaded)
                    ?: PreviewImageState.Failed
            }
        }
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = imageState) {
                PreviewImageState.Loading -> PreviewImageSkeleton(showLabel = false)
                PreviewImageState.Failed -> PreviewImageSkeleton(showLabel = true)
                is PreviewImageState.Loaded -> Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

private sealed interface PreviewImageState {
    data object Loading : PreviewImageState
    data object Failed : PreviewImageState
    data class Loaded(val bitmap: android.graphics.Bitmap) : PreviewImageState
}

@Composable
private fun PreviewImageSkeleton(showLabel: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (showLabel) {
            Text("MOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(0.62f))
        }
    }
}

@Composable
private fun ErrorPanel(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("加载失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyPanel(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("没有找到条目", style = MaterialTheme.typography.titleMedium)
            Text("换个关键词试试，或稍后刷新 Steam 创意工坊。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRetry) { Text("刷新") }
        }
    }
}

@Composable
private fun LoadingPanel(
    modifier: Modifier = Modifier,
    text: String,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
