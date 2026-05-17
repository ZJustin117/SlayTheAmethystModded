package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkshopDownloadCenterScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: WorkshopViewModel = viewModel()
    val tasks = WorkshopDownloadCenterStore.tasks

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("下载中心")
                        Text(
                            text = "查看创意工坊下载和导入状态",
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
                        onManualImport = {
                            task.details?.let { details ->
                                viewModel.setSelectedForImport(details)
                                viewModel.manualImport(context)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: WorkshopDownloadTaskUi,
    onManualImport: () -> Unit,
) {
    val active = task.status == WorkshopDownloadTaskStatus.Queued ||
        task.status == WorkshopDownloadTaskStatus.Resolving ||
        task.status == WorkshopDownloadTaskStatus.Downloading ||
        task.status == WorkshopDownloadTaskStatus.Importing
    Card(
        colors = when (task.status) {
            WorkshopDownloadTaskStatus.Failed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            WorkshopDownloadTaskStatus.Imported -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else -> CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(task.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(onClick = {}, label = { Text(task.status.label()) })
            }
            if (active) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                text = "Workshop ID ${task.publishedFileId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.canManualImport) {
                Button(onClick = onManualImport) { Text("导入") }
            }
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

private fun WorkshopDownloadTaskStatus.label(): String = when (this) {
    WorkshopDownloadTaskStatus.Queued -> "等待"
    WorkshopDownloadTaskStatus.Resolving -> "解析"
    WorkshopDownloadTaskStatus.Downloading -> "下载"
    WorkshopDownloadTaskStatus.WaitingImport -> "待导入"
    WorkshopDownloadTaskStatus.Importing -> "导入中"
    WorkshopDownloadTaskStatus.Imported -> "已导入"
    WorkshopDownloadTaskStatus.Failed -> "失败"
}
