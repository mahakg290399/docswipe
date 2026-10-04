package com.mag.docswipe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {
    private val model by viewModels<DocSwipeViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DocSwipeApp(model) }
    }

    override fun onResume() {
        super.onResume()
        if (Environment.isExternalStorageManager()) model.onStorageAccessGranted()
    }
}

class DocSwipeViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val db = (application as DocSwipeApplication).database
    private val scanner = StorageScanner(db)
    var months by mutableStateOf<List<MonthSummary>>(emptyList()); private set
    var failed by mutableStateOf<List<FailedDeletion>>(emptyList()); private set
    var deck by mutableStateOf<List<Document>>(emptyList()); private set
    var staged by mutableStateOf<List<Document>>(emptyList()); private set
    var includeHidden by mutableStateOf(application.getSharedPreferences("settings", 0).getBoolean("hidden", false)); private set
    var scanning by mutableStateOf(false); private set
    var result by mutableStateOf<DeleteResult?>(null); private set
    var storageGranted by mutableStateOf(Environment.isExternalStorageManager()); private set
    var skippedPrompt by mutableStateOf(false); private set
    private var reviewingSkipped = false

    fun refresh() { months = db.months(); failed = db.failed() }

    fun onStorageAccessGranted() {
        storageGranted = true
        if (contextPrefs().getBoolean("hidden_prompted", false)) {
            if (months.isEmpty() && !scanning) scan() else refresh()
        }
    }

    fun scan() {
        scanning = true
        viewModelScope.launch {
            scanner.scan(includeHidden)
            refresh()
            scanning = false
        }
    }

    fun setHidden(value: Boolean) {
        includeHidden = value
        getApplication<DocSwipeApplication>().getSharedPreferences("settings", 0).edit().putBoolean("hidden", value).apply()
    }

    fun openMonth(month: String) { reviewingSkipped = false; skippedPrompt = false; deck = db.documents(month) }
    fun reviewSkipped(month: String) { reviewingSkipped = true; skippedPrompt = false; deck = db.documents(month, includeSkipped = true) }
    fun leaveSkipped() { skippedPrompt = false; deck = emptyList() }
    fun openReview(month: String) { staged = db.staged(month) }
    fun act(doc: Document, action: Triage) {
        db.setStatus(doc.id, action)
        deck = db.documents(doc.month, includeSkipped = reviewingSkipped)
        if (deck.isEmpty() && !reviewingSkipped && db.hasSkipped(doc.month)) skippedPrompt = true
        refresh()
    }
    fun restore(doc: Document) { db.setStatus(doc.id, Triage.UNREVIEWED); staged = db.staged(doc.month); refresh() }

    fun deleteStaged(month: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = db.staged(month); var success = 0; var bytes = 0L; val failures = mutableListOf<FailedDeletion>()
            items.forEach { doc ->
                val file = File(doc.path); val size = file.length()
                val deleted = try { !file.exists() || file.delete() } catch (_: Exception) { false }
                if (deleted) { db.remove(doc.id); success++; bytes += size }
                else { db.addFailure(doc, "The file could not be deleted."); failures += FailedDeletion(doc.id, doc.name, doc.path, "The file could not be deleted.") }
            }
            withContext(Dispatchers.Main) {
                result = DeleteResult(items.size, success, failures.size, bytes)
                refresh()
            }
        }
    }

    fun retry(failure: FailedDeletion) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { !File(failure.path).exists() || File(failure.path).delete() } catch (_: Exception) { false }
            if (ok) db.removeFailure(failure.id)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun clearResult() { result = null }
    fun clearSkippedPrompt() { skippedPrompt = false }

    fun markHiddenPrompted(value: Boolean) { contextPrefs().edit().putBoolean("hidden_prompted", value).apply() }
    private fun contextPrefs() = getApplication<DocSwipeApplication>().getSharedPreferences("settings", 0)
}

data class DeleteResult(val attempted: Int, val deleted: Int, val failed: Int, val bytes: Long)

private enum class Screen { HOME, DECK, REVIEW, SETTINGS }

@Composable
fun DocSwipeApp(model: DocSwipeViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedMonth by remember { mutableStateOf("") }
    var showPermissionInfo by remember { mutableStateOf(!model.storageGranted) }
    var showHiddenPrompt by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(model.storageGranted) {
        showPermissionInfo = !model.storageGranted
        showHiddenPrompt = model.storageGranted && !contextPrefs(model).getBoolean("hidden_prompted", false)
    }

    if (showPermissionInfo) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Storage access required") },
            text = { Text("DocSwipe needs local storage access to find and review your documents. Nothing is uploaded.") },
            confirmButton = { TextButton(onClick = {
                showPermissionInfo = false
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            }) { Text("Continue") } }
        )
    }

    if (!showPermissionInfo && showHiddenPrompt) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Scan hidden folders?") },
            text = { Text("Hidden folders may contain user documents but can also contain application data. You can change this later in Settings.") },
            confirmButton = { TextButton(onClick = {
                model.setHidden(true); model.markHiddenPrompted(true); showHiddenPrompt = false; model.scan()
            }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = {
                model.setHidden(false); model.markHiddenPrompted(true); showHiddenPrompt = false; model.scan()
            }) { Text("No") } }
        )
    }

    when (screen) {
        Screen.HOME -> HomeScreen(model, onOpen = { selectedMonth = it; model.openMonth(it); screen = Screen.DECK }, onSettings = { screen = Screen.SETTINGS }, onScan = model::scan)
        Screen.DECK -> DeckScreen(model, selectedMonth, onBack = { screen = Screen.HOME }, onReview = { model.openReview(selectedMonth); screen = Screen.REVIEW })
        Screen.REVIEW -> ReviewScreen(model, selectedMonth, onBack = { screen = Screen.DECK }, onDeleted = { model.deleteStaged(selectedMonth); screen = Screen.HOME })
        Screen.SETTINGS -> SettingsScreen(model, onBack = { screen = Screen.HOME }, onScan = model::scan)
    }

    model.result?.let { result ->
        AlertDialog(
            onDismissRequest = model::clearResult,
            title = { Text("Deletion complete") },
            text = { Text("Attempted: ${result.attempted}\nDeleted: ${result.deleted}\nFailed: ${result.failed}\nReclaimed: ${formatBytes(result.bytes)}") },
            confirmButton = { TextButton(onClick = model::clearResult) { Text("Done") } }
        )
    }

    if (model.skippedPrompt) {
        AlertDialog(
            onDismissRequest = model::clearSkippedPrompt,
            title = { Text("Skipped files remain") },
            text = { Text("Review the skipped files now?") },
            confirmButton = { TextButton(onClick = { model.reviewSkipped(selectedMonth) }) { Text("Review") } },
            dismissButton = { TextButton(onClick = model::leaveSkipped) { Text("Later") } }
        )
    }
}

private fun contextPrefs(model: DocSwipeViewModel) = model.getApplication<DocSwipeApplication>().getSharedPreferences("settings", 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(model: DocSwipeViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit, onScan: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("DocSwipe") }, actions = {
        IconButton(onClick = onScan) { Icon(Icons.Default.Refresh, "Rescan") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.scanning) item { Text("Scanning local documents…") }
            if (model.months.isEmpty() && !model.scanning) item { Text("No supported documents found.") }
            items(model.months, key = { it.month }) { month ->
                Card(onClick = { onOpen(month.month) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(month.month, style = MaterialTheme.typography.titleLarge)
                        Text("${month.count} files · ${formatBytes(month.bytes)}")
                        if (month.pending > 0) Text("Pending review", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Text("Failed Deleted Files (${model.failed.size})", style = MaterialTheme.typography.titleMedium)
                model.failed.forEach { failure ->
                    Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(failure.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(failure.path, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { model.retry(failure) }) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckScreen(model: DocSwipeViewModel, month: String, onBack: () -> Unit, onReview: () -> Unit) {
    var undo by remember { mutableStateOf<Document?>(null) }
    val active = model.deck.firstOrNull()
    Scaffold(topBar = { TopAppBar(title = { Text(month) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { TextButton(onClick = onReview) { Text("Review") } }) }, bottomBar = {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { undo?.let { model.act(it, Triage.UNREVIEWED); undo = null } }) { Icon(Icons.Default.Undo, "Undo") }
            IconButton(onClick = { active?.let { undo = it; model.act(it, Triage.SKIPPED) } }) { Icon(Icons.Default.SkipNext, "Skip") }
            IconButton(onClick = { active?.let { undo = it; model.act(it, Triage.STAGED_DELETE) } }) { Icon(Icons.Default.Delete, "Delete") }
            IconButton(onClick = { active?.let { undo = it; model.act(it, Triage.KEEP) } }) { Icon(Icons.Default.Check, "Keep") }
        }
    }) { padding ->
        if (active == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Month review complete") }
        else DocumentCard(active, Modifier.fillMaxSize().padding(padding).padding(12.dp), onLeft = { undo = active; model.act(active, Triage.STAGED_DELETE) }, onRight = { undo = active; model.act(active, Triage.KEEP) })
    }
}

@Composable
private fun DocumentCard(document: Document, modifier: Modifier, onLeft: () -> Unit, onRight: () -> Unit) {
    var offset by remember(document.id) { mutableFloatStateOf(0f) }
    Card(modifier.pointerInput(document.id) {
        detectDragGestures(onDragEnd = {
            when { offset < -180f -> onLeft(); offset > 180f -> onRight() }
            offset = 0f
        }, onDragCancel = { offset = 0f }) { change, drag ->
            if (kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y)) { offset += drag.x; change.consume() }
        }
    }) { DocumentViewer(document, Modifier.fillMaxSize()) }
}

@Composable
private fun DocumentViewer(document: Document, modifier: Modifier) {
    Column(modifier.padding(16.dp)) {
        Text(document.name, style = MaterialTheme.typography.titleLarge)
        Text("${document.extension.uppercase()} · ${formatBytes(document.size)}")
        Spacer(Modifier.height(12.dp))
        when (document.extension) {
            "pdf" -> PdfPreview(document.path)
            else -> TextPreview(document.path)
        }
    }
}

@Composable
private fun PdfPreview(path: String) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val descriptor = android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = android.graphics.pdf.PdfRenderer(descriptor)
                if (renderer.pageCount == 0) null else renderer.openPage(0).let { page ->
                    val result = android.graphics.Bitmap.createBitmap(page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(result, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close(); renderer.close(); descriptor.close(); result
                }
            } catch (_: Exception) { null }
        }
    }
    if (bitmap == null) Text("Loading PDF preview…") else Image(bitmap!!.asImageBitmap(), "PDF preview", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
}

@Composable
private fun TextPreview(path: String) {
    val lines = remember(path) { runCatching { File(path).bufferedReader().useLines { it.take(200).toList() } }.getOrElse { listOf("Preview unavailable") } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(lines) { Text(it) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(model: DocSwipeViewModel, month: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Review deletions") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("${model.staged.size} files · ${formatBytes(model.staged.sumOf { it.size })}", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f)) { items(model.staged, key = { it.id }) { doc ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(doc.name); Text(doc.path, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { model.restore(doc) }) { Text("Restore") }
                }
            } }
            Button(onClick = onDeleted, enabled = model.staged.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Delete selected") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(model: DocSwipeViewModel, onBack: () -> Unit, onScan: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan hidden folders", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (model.includeHidden) "Yes" else "No")
                OutlinedButton(onClick = { model.setHidden(!model.includeHidden); onScan() }) { Text("Change and rescan") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${DecimalFormat("0.0").format(bytes / 1024.0)} KB"
    bytes < 1024 * 1024 * 1024 -> "${DecimalFormat("0.0").format(bytes / 1024.0 / 1024.0)} MB"
    else -> "${DecimalFormat("0.0").format(bytes / 1024.0 / 1024.0 / 1024.0)} GB"
}
