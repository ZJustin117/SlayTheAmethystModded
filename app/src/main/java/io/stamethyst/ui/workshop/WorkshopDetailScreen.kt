package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.backend.workshop.WorkshopItemDetails

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

    LaunchedEffect(appId, publishedFileId) {
        viewModel.load(context.applicationContext)
        viewModel.loadDetails(context.applicationContext, appId, publishedFileId)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("模组详情")
                        Text(
                            text = "Workshop ID $publishedFileId",
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
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    item {
                        DetailContentCard(
                            details = state.selected,
                            downloading = state.downloadInProgress,
                            onDownload = { viewModel.downloadSelected(context) },
                        )
                    }
                    item {
                        DetailDownloadStatusCard(
                            status = state.downloadStatus,
                            downloading = state.downloadInProgress,
                            waitingImportTitle = state.pendingManualImportTitle,
                            onManualImport = { viewModel.manualImport(context) },
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
}

@Composable
private fun DetailContentCard(
    details: WorkshopItemDetails,
    downloading: Boolean,
    onDownload: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(details.summary.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (details.summary.authorName.isNotBlank()) {
                Text(details.summary.authorName, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (details.summary.description.isNotBlank()) {
                Text(details.summary.description, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(details.summary.publishedFileId.toString()) })
                if (details.hcontentFile != null) AssistChip(onClick = {}, label = { Text("Steam 内容") })
                if (!details.fileUrl.isNullOrBlank()) AssistChip(onClick = {}, label = { Text("直链") })
            }
            Button(enabled = !downloading, onClick = onDownload) {
                Text(if (downloading) "下载中" else "下载并导入")
            }
        }
    }
}

@Composable
private fun DetailDownloadStatusCard(
    status: String,
    downloading: Boolean,
    waitingImportTitle: String?,
    onManualImport: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("下载状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status.ifBlank { "尚未开始下载" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
            waitingImportTitle?.let {
                Button(onClick = onManualImport) { Text("导入 $it") }
            }
            OutlinedButton(onClick = onOpenDownloadCenter) { Text("查看下载中心") }
        }
    }
}

@Composable
private fun DetailErrorCard(message: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("详情加载失败", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message)
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun LoadingDetailCard() {
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator()
            Text("正在加载模组详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
