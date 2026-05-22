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
import androidx.compose.ui.res.stringResource
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
    initialListMode: WorkshopListMode = WorkshopListMode.Browse,
    showSubscriptionsButton: Boolean = false,
    title: String? = null,
    subtitle: String? = null,
    onBack: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
    onOpenSubscriptions: () -> Unit = {},
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

    LaunchedEffect(initialListMode) {
        viewModel.load(context.applicationContext, initialListMode)
    }

    LaunchedEffect(state.listMode) {
        listState.animateScrollToItem(0)
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
                        WorkshopStatusHeader(
                            listMode = state.listMode,
                            onOpenSteamLogin = onOpenSteamLogin,
                        )
                    }
                }

                if (state.listMode == WorkshopListMode.Browse) {
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
                }

                if (state.errorMessage != null) {
                    item(key = "workshop-error") {
                        ErrorPanel(
                            modifier = Modifier.animateItem(),
                            message = state.errorMessage,
                            onRetry = {
                                when (state.listMode) {
                                    WorkshopListMode.Browse -> viewModel.search(context.applicationContext, query, sort, timeFilter)
                                    WorkshopListMode.Subscriptions -> viewModel.showSubscribedWorkshopMods(context.applicationContext)
                                }
                            },
                        )
                    }
                }

                item(key = "workshop-section-title") {
                    SectionTitle(
                        title = when (state.listMode) {
                            WorkshopListMode.Browse -> stringResource(R.string.workshop_section_browse)
                            WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_subscriptions_title)
                        },
                        subtitle = when {
                            state.browseLoading -> stringResource(R.string.workshop_section_loading)
                            state.items.isEmpty() -> stringResource(R.string.workshop_section_no_results)
                            else -> stringResource(R.string.workshop_section_item_count, state.items.size)
                        },
                    )
                }

                when {
                    state.browseLoading && state.items.isEmpty() -> {
                        item(key = "workshop-loading") {
                            LoadingPanel(
                                modifier = Modifier.animateItem(),
                                text = when (state.listMode) {
                                    WorkshopListMode.Browse -> stringResource(R.string.workshop_loading_browse)
                                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_loading_subscriptions)
                                },
                            )
                        }
                    }
                    state.items.isEmpty() && state.errorMessage == null -> {
                        item(key = "workshop-empty") {
                            EmptyPanel(
                                modifier = Modifier.animateItem(),
                                title = when (state.listMode) {
                                    WorkshopListMode.Browse -> stringResource(R.string.workshop_empty_title)
                                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_empty_subscriptions_title)
                                },
                                description = when (state.listMode) {
                                    WorkshopListMode.Browse -> stringResource(R.string.workshop_empty_description)
                                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_empty_subscriptions_description)
                                },
                                actionLabel = when (state.listMode) {
                                    WorkshopListMode.Browse -> stringResource(R.string.common_action_refresh)
                                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_action_refresh_subscriptions)
                                },
                                onRetry = {
                                    when (state.listMode) {
                                        WorkshopListMode.Browse -> viewModel.search(context.applicationContext, query, sort, timeFilter)
                                        WorkshopListMode.Subscriptions -> viewModel.showSubscribedWorkshopMods(context.applicationContext)
                                    }
                                },
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
                    showSubscriptionsButton = showSubscriptionsButton && state.steamLoggedIn,
                    title = title ?: stringResource(R.string.workshop_market_title),
                    subtitle = subtitle ?: stringResource(R.string.workshop_market_subtitle),
                    onBack = onBack,
                    onOpenSubscriptions = onOpenSubscriptions,
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
    showSubscriptionsButton: Boolean,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    val downloadCenterDescription = if (activeDownloadTaskCount > 0) {
        stringResource(R.string.workshop_download_center_with_active_tasks, activeDownloadTaskCount)
    } else {
        stringResource(R.string.workshop_download_center_title)
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
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showSubscriptionsButton) {
            IconButton(
                onClick = onOpenSubscriptions,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_workshop_subscriptions),
                    contentDescription = stringResource(R.string.workshop_subscriptions_title),
                )
            }
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
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_first_run_action_back)) }
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
                    Text(stringResource(R.string.workshop_loading_more), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Text(stringResource(R.string.workshop_no_more_items), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkshopStatusHeader(
    listMode: WorkshopListMode,
    onOpenSteamLogin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when (listMode) {
                    WorkshopListMode.Browse -> stringResource(R.string.workshop_not_logged_in_browse)
                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_not_logged_in_subscriptions)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSteamLogin) { Text(stringResource(R.string.workshop_action_login_steam)) }
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
                        placeholder = { Text(stringResource(R.string.workshop_search_placeholder)) },
                        trailingIcon = {
                            TextButton(
                                enabled = !loading,
                                onClick = { submitSearch() },
                            ) { Text(stringResource(R.string.workshop_search_action)) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sort.usesTimeFilter) {
                        Box {
                            OutlinedButton(
                                enabled = !loading,
                                onClick = { timeMenuExpanded = true }
                            ) {
                                Text(timeFilter.displayName())
                            }
                            DropdownMenu(
                                expanded = timeMenuExpanded,
                                onDismissRequest = { timeMenuExpanded = false }
                            ) {
                                WorkshopBrowseTimeFilter.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName()) },
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
                            Text(sort.displayName())
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            WorkshopBrowseSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.displayName()) },
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
                contentDescription = stringResource(R.string.workshop_preview_content_description, item.title),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = item.authorName.ifBlank { item.description.ifBlank { stringResource(R.string.workshop_open_detail_hint) } },
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
                contentDescription = stringResource(state.actionLabelResId),
            )
        }
        return
    }
    when (state) {
        WorkshopModDownloadState.Downloaded -> OutlinedButton(
            modifier = modifier,
            enabled = false,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.NotDownloaded -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.UpdateAvailable -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.Paused -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.DownloadFailed -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.Queued,
        WorkshopModDownloadState.Cancelling,
        WorkshopModDownloadState.Downloading -> OutlinedButton(
            modifier = modifier,
            enabled = false,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
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
        title = { Text(stringResource(R.string.workshop_missing_dependencies_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.workshop_missing_dependencies_message, modTitle))
                missingDependencies.forEach { dependency ->
                    Text(
                        text = "${dependency.title.ifBlank { stringResource(R.string.workshop_dependency_fallback_title, dependency.publishedFileId.toString()) }} (${dependency.publishedFileId})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.workshop_action_install_and_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_folder_dialog_cancel)) } },
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
            Text(stringResource(R.string.workshop_error_loading_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.workshop_action_retry)) }
        }
    }
}

@Composable
private fun EmptyPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    actionLabel: String? = null,
    onRetry: () -> Unit,
) {
    val resolvedTitle = title ?: stringResource(R.string.workshop_empty_title)
    val resolvedDescription = description ?: stringResource(R.string.workshop_empty_description)
    val resolvedActionLabel = actionLabel ?: stringResource(R.string.common_action_refresh)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(resolvedTitle, style = MaterialTheme.typography.titleMedium)
            Text(resolvedDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRetry) { Text(resolvedActionLabel) }
        }
    }
}

@Composable
private fun WorkshopBrowseSort.displayName(): String = when (this) {
    WorkshopBrowseSort.MostPopular -> stringResource(R.string.workshop_sort_most_popular)
    WorkshopBrowseSort.MostRecent -> stringResource(R.string.workshop_sort_most_recent)
    WorkshopBrowseSort.LastUpdated -> stringResource(R.string.workshop_sort_last_updated)
    WorkshopBrowseSort.MostSubscribed -> stringResource(R.string.workshop_sort_most_subscribed)
}

@Composable
private fun WorkshopBrowseTimeFilter.displayName(): String = when (this) {
    WorkshopBrowseTimeFilter.Today -> stringResource(R.string.workshop_time_today)
    WorkshopBrowseTimeFilter.OneWeek -> stringResource(R.string.workshop_time_one_week)
    WorkshopBrowseTimeFilter.ThirtyDays -> stringResource(R.string.workshop_time_thirty_days)
    WorkshopBrowseTimeFilter.ThreeMonths -> stringResource(R.string.workshop_time_three_months)
    WorkshopBrowseTimeFilter.SixMonths -> stringResource(R.string.workshop_time_six_months)
    WorkshopBrowseTimeFilter.OneYear -> stringResource(R.string.workshop_time_one_year)
    WorkshopBrowseTimeFilter.AllTime -> stringResource(R.string.workshop_time_all_time)
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
