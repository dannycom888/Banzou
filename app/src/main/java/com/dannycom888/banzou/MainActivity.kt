package com.dannycom888.banzou

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BanzouApp()
            }
        }
    }
}

private const val PREFS = "banzou"
private const val KEY_TREE_URI = "tree_uri"

@Composable
fun BanzouApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playingItem by remember { mutableStateOf<SongItem?>(null) }

    val queueState by QueueManager.state.collectAsState()

    fun rescan(treeUri: String) {
        scanning = true
        scanError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tree = SongLibrary.treeFromUri(context, treeUri)
                        ?: error("歌库文件夹打不开，授权可能已失效，请重新选择")
                    folderName = tree.name
                    SongLibrary.scan(tree)
                }
            }
            scanning = false
            result.onSuccess { songs = it }
                .onFailure { scanError = it.message ?: "扫描失败" }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (ignored: Exception) {
            }
            prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
            selected = emptySet()
            rescan(uri.toString())
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* progress still visible in-app either way */ }

    LaunchedEffect(Unit) {
        prefs.getString(KEY_TREE_URI, null)?.let { rescan(it) }
    }

    // Refresh the list when songs finish (accompaniment marks change).
    val doneCount = queueState.doneCount
    LaunchedEffect(doneCount) {
        if (doneCount > 0) {
            prefs.getString(KEY_TREE_URI, null)?.let { uri ->
                scope.launch {
                    val fresh = withContext(Dispatchers.IO) {
                        runCatching {
                            SongLibrary.treeFromUri(context, uri)?.let { SongLibrary.scan(it) }
                        }.getOrNull()
                    }
                    if (fresh != null) songs = fresh
                }
            }
        }
    }

    fun startProcessing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val items = songs.filter { it.uri.toString() in selected && !it.hasAccompaniment }
        if (items.isEmpty()) {
            Toast.makeText(context, "先勾选要做的歌", Toast.LENGTH_SHORT).show()
            return
        }
        QueueManager.enqueue(context, items)
        selected = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("分轨伴奏", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "把原唱 MP3 做成伴奏，生成「（伴奏）」文件放在原歌旁边，供家里卡拉OK用。原文件只读、不会改动。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { folderPicker.launch(null) }) {
                Text(if (folderName == null) "选择歌库文件夹" else "重新选择文件夹")
            }
            Spacer(Modifier.padding(4.dp))
            Text(
                when {
                    scanning -> "扫描中…"
                    folderName != null -> "$folderName · 共 ${songs.size} 首"
                    else -> "还没选文件夹"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        scanError?.let {
            Text("⚠ $it", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索歌名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        if (queueState.entries.isNotEmpty()) {
            QueuePanel(
                state = queueState,
                onCancel = { QueueManager.cancelAll() },
                onRetry = { QueueManager.retryFailed(context) },
                onPlay = { entry -> playingItem = entry.item },
            )
            Spacer(Modifier.height(8.dp))
        }

        playingItem?.let { item ->
            PlayerPanel(item = item, onClose = { playingItem = null })
            Spacer(Modifier.height(8.dp))
        }

        val visible = songs.filter { it.name.contains(query, ignoreCase = true) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(visible, key = { it.uri.toString() }) { item ->
                val key = item.uri.toString()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = key in selected,
                        enabled = !item.hasAccompaniment,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + key else selected - key
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (item.hasAccompaniment) "已有伴奏" else "未做伴奏",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.hasAccompaniment) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                selected = visible.filter { !it.hasAccompaniment }.map { it.uri.toString() }.toSet()
            }) { Text("全选未做的") }
            Button(
                onClick = { startProcessing() },
                enabled = selected.isNotEmpty() && !queueState.running,
                modifier = Modifier.weight(1f),
            ) { Text("开始分轨（已选 ${selected.size} 首）") }
        }
    }
}

@Composable
fun QueuePanel(
    state: QueueState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (QueueEntry) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("处理队列", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { state.overallProgress }, modifier = Modifier.fillMaxWidth())
            Text(
                "总进度 ${(state.overallProgress * 100).toInt()}% · 完成 ${state.doneCount}/${state.entries.size}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "⚠ 分轨很吃 CPU，手机会发热、耗电快，建议插着电跑。可以息屏，进度会继续；请别在处理中强行关掉 App。",
                style = MaterialTheme.typography.bodySmall,
            )
            state.entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.item.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (entry.status) {
                                EntryStatus.WAITING -> "等待中"
                                EntryStatus.RUNNING -> "${entry.stage} · ${(entry.progress * 100).toInt()}%"
                                EntryStatus.DONE -> "完成"
                                EntryStatus.FAILED -> "失败：${entry.error ?: ""}"
                                EntryStatus.CANCELLED -> "已取消"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (entry.status == EntryStatus.DONE) {
                        OutlinedButton(onClick = { onPlay(entry) }) { Text("试听") }
                    }
                }
                if (entry.status == EntryStatus.RUNNING) {
                    LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    OutlinedButton(onClick = onCancel) { Text("取消全部") }
                }
                if (state.failedEntries.isNotEmpty() && !state.running) {
                    Button(onClick = onRetry) { Text("重试失败的（${state.failedEntries.size}）") }
                }
            }
        }
    }
}

@Composable
fun PlayerPanel(item: SongItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember(item.uri) { ExoPlayer.Builder(context).build() }
    var useAccompaniment by remember(item.uri) { mutableStateOf(false) }
    var isPlaying by remember(item.uri) { mutableStateOf(false) }
    val accompaniment: DocumentFile? = remember(item.uri) {
        item.parent.findFile(Naming.accompanimentName(item.name))
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    fun load(acc: Boolean, keepPosition: Boolean) {
        val uri = if (acc) accompaniment?.uri ?: item.uri else item.uri
        val pos = if (keepPosition) player.currentPosition else 0L
        val wasPlaying = player.playWhenReady
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.seekTo(pos)
        player.playWhenReady = wasPlaying
        useAccompaniment = acc
    }

    LaunchedEffect(item.uri) {
        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("试听对比：${item.name}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (player.isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        player.play()
                        isPlaying = true
                    }
                }) { Text(if (isPlaying) "暂停" else "播放") }
                OutlinedButton(
                    onClick = { load(acc = false, keepPosition = true) },
                    enabled = useAccompaniment,
                ) { Text("原唱") }
                OutlinedButton(
                    onClick = { load(acc = true, keepPosition = true) },
                    enabled = !useAccompaniment && accompaniment != null,
                ) { Text("伴奏") }
                OutlinedButton(onClick = onClose) { Text("关闭") }
            }
            Text(
                "当前：${if (useAccompaniment) "伴奏" else "原唱"}。切换时进度不跳。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
