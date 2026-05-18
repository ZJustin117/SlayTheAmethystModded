package io.stamethyst.ui.workshop

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.ui.CollapsibleFloatingGlassHeader
import io.stamethyst.backend.workshop.WorkshopItemSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
internal fun WorkshopScreen(
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: WorkshopViewModel = viewModel()
    val state = viewModel.uiState
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val headerHazeState = rememberHazeState()
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerCollapsed = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > with(density) { 24.dp.roundToPx() }
    val measuredHeaderHeight = with(density) { headerHeightPx.toDp() }
    val headerContentTopInset = (if (headerHeightPx == 0) 102.dp else measuredHeaderHeight) + 16.dp
    var query by rememberSaveable { mutableStateOf("") }
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 4
        }
    }

    LaunchedEffect(Unit) {
        viewModel.load(context.applicationContext)
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = headerHazeState)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(headerContentTopInset))
            }
            item {
                WorkshopStatusHeader(
                    state = state,
                    onOpenSteamLogin = onOpenSteamLogin,
                    onAutoImportChanged = { viewModel.setAutoImport(context.applicationContext, it) },
                )
            }

            item {
                SearchPanel(
                    query = query,
                    loading = state.browseLoading,
                    onQueryChange = { query = it },
                    onSearch = { viewModel.search(context.applicationContext, query) },
                    onRefresh = { viewModel.search(context.applicationContext, query) },
                )
            }

            if (state.errorMessage != null) {
                item {
                    ErrorPanel(
                        message = state.errorMessage,
                        onRetry = { viewModel.search(context.applicationContext, query) },
                    )
                }
            }

            item {
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
                    item { LoadingPanel("正在连接 Steam 创意工坊") }
                }
                state.items.isEmpty() && state.errorMessage == null -> {
                    item { EmptyPanel(onRetry = { viewModel.search(context.applicationContext, query) }) }
                }
                else -> {
                    items(state.items, key = { it.publishedFileId.toString() }) { item ->
                        WorkshopItemCard(
                            item = item,
                            onClick = { onOpenDetails(item) },
                        )
                    }
                    item {
                        BrowsePaginationFooter(
                            loading = state.loadingMore,
                            hasMorePages = state.hasMorePages,
                            itemCount = state.items.size,
                        )
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
                    onBack = onBack,
                    onOpenDownloadCenter = onOpenDownloadCenter,
                )
            },
        )
    }
}

@Composable
private fun WorkshopHeaderPinnedContent(
    showBackButton: Boolean,
    onBack: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
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
                text = "STS 创意工坊",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "浏览、下载并导入 Slay the Spire 模组",
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
            Icon(
                painter = painterResource(R.drawable.ic_cloud),
                contentDescription = "下载中心",
            )
        }
        if (showBackButton) {
            TextButton(onClick = onBack) { Text("返回") }
        }
    }
}

@Composable
private fun BrowsePaginationFooter(
    loading: Boolean,
    hasMorePages: Boolean,
    itemCount: Int,
) {
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
            !hasMorePages && itemCount > 0 -> Text("已经到底了", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkshopStatusHeader(
    state: WorkshopUiState,
    onOpenSteamLogin: () -> Unit,
    onAutoImportChanged: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("下载后自动导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (state.autoImportEnabled) "下载完成后会替换同 modid 的旧模组" else "下载完成后会等待你手动导入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.autoImportEnabled, onCheckedChange = onAutoImportChanged)
            }
            if (!state.steamLoggedIn) {
                Text(
                    text = "未检测到启动器 Steam 登录。公开条目可浏览，受限下载需要先登录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenSteamLogin) { Text("登录 Steam") }
            }
            if (state.installedMods.isNotEmpty()) {
                Text(
                    text = "已记录 ${state.installedMods.size} 个创意工坊模组，可用于更新检查。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = onQueryChange,
                label = { Text("搜索模组") },
                singleLine = true,
                supportingText = { Text("留空浏览推荐/默认排序条目") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !loading, onClick = onSearch) { Text("搜索") }
                OutlinedButton(enabled = !loading, onClick = onRefresh) { Text("刷新") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WorkshopItemCard(
    item: WorkshopItemSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkshopPreviewImage(
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
        }
    }
}

@Composable
private fun WorkshopPreviewImage(url: String, contentDescription: String) {
    val imageState by produceState<PreviewImageState>(initialValue = PreviewImageState.Loading, key1 = url) {
        value = when {
            url.isBlank() -> PreviewImageState.Failed
            else -> withContext(Dispatchers.IO) {
                WorkshopPreviewImageLoader.load(url)?.let(PreviewImageState::Loaded) ?: PreviewImageState.Failed
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

private object WorkshopPreviewImageLoader {
    private val cache = object : LruCache<String, android.graphics.Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    private val client = OkHttpClient()

    fun load(url: String): android.graphics.Bitmap? {
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

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("加载失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyPanel(onRetry: () -> Unit) {
    Card {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("没有找到条目", style = MaterialTheme.typography.titleMedium)
            Text("换个关键词试试，或稍后刷新 Steam 创意工坊。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRetry) { Text("刷新") }
        }
    }
}

@Composable
private fun LoadingPanel(text: String) {
    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator()
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
