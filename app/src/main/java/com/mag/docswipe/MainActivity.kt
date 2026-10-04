package com.mag.docswipe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfPasswordException
import com.shockwave.pdfium.PdfiumCore
import app.opendocument.core.Html
import app.opendocument.core.HtmlConfig
import app.opendocument.core.Odr
import app.opendocument.core.android.OdrAndroid
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler

class MainActivity : ComponentActivity() {
    private val model by viewModels<DocSwipeViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DocSwipeTheme { DocSwipeApp(model) } }
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
    private val deferredSkippedIds = mutableSetOf<String>()

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

    fun openMonth(month: String) {
        reviewingSkipped = false
        deferredSkippedIds.clear()
        skippedPrompt = false
        deck = db.documents(month)
        if (deck.isEmpty() && db.hasSkipped(month)) skippedPrompt = true
    }
    fun reviewSkipped(month: String) {
        reviewingSkipped = true
        deferredSkippedIds.clear()
        skippedPrompt = false
        deck = db.documents(month, includeSkipped = true)
    }
    fun totalDocuments(month: String): Int = db.count(month)
    fun leaveSkipped() { skippedPrompt = false; deferredSkippedIds.clear(); deck = emptyList() }
    fun stagedCount(month: String): Int = db.staged(month).size
    fun tutorialShown(month: String): Boolean {
        val prefs = getApplication<DocSwipeApplication>().getSharedPreferences("settings", 0)
        return prefs.getBoolean("tutorial_shown", false) || prefs.all.keys.any { it.startsWith("tutorial_") }
    }
    fun markTutorialShown(month: String) {
        getApplication<DocSwipeApplication>().getSharedPreferences("settings", 0).edit().putBoolean("tutorial_shown", true).apply()
    }
    fun openReview(month: String) { staged = db.staged(month) }
    fun act(doc: Document, action: Triage) {
        db.setStatus(doc.id, action)
        if (reviewingSkipped && action == Triage.SKIPPED) deferredSkippedIds += doc.id
        else deferredSkippedIds -= doc.id
        deck = db.documents(doc.month, includeSkipped = reviewingSkipped)
            .filterNot { it.id in deferredSkippedIds }
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

    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.DECK, Screen.SETTINGS -> Screen.HOME
            Screen.REVIEW -> Screen.DECK
            Screen.HOME -> Screen.HOME
        }
    }

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
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(HomeSort.DATE_OLDEST) }
    val reviewed = model.months.sumOf { it.count - it.pending }
    val total = model.months.sumOf { it.count }
    val progress = if (total == 0) 0f else reviewed.toFloat() / total
    val sortedMonths = remember(model.months, sortMode) {
        val pendingMonths = model.months.filter { it.pending > 0 }
        when (sortMode) {
            HomeSort.DATE_OLDEST -> pendingMonths.sortedBy { it.month }
            HomeSort.DATE_NEWEST -> pendingMonths.sortedByDescending { it.month }
            HomeSort.FILES_FEWEST -> pendingMonths.sortedWith(compareBy<MonthSummary> { it.count }.thenBy { it.month })
            HomeSort.FILES_MOST -> pendingMonths.sortedWith(compareByDescending<MonthSummary> { it.count }.thenBy { it.month })
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("DocSwipe", style = MaterialTheme.typography.headlineMedium)
                        Text("A calmer way to clear your documents", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        IconButton(onClick = onScan) { Icon(Icons.Default.Refresh, "Rescan") }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) { Icon(Icons.Default.Sort, "Sort") }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("Date: oldest first") }, onClick = { sortMode = HomeSort.DATE_OLDEST; sortMenuOpen = false })
                                DropdownMenuItem(text = { Text("Date: newest first") }, onClick = { sortMode = HomeSort.DATE_NEWEST; sortMenuOpen = false })
                                DropdownMenuItem(text = { Text("Files: fewest first") }, onClick = { sortMode = HomeSort.FILES_FEWEST; sortMenuOpen = false })
                                DropdownMenuItem(text = { Text("Files: most first") }, onClick = { sortMode = HomeSort.FILES_MOST; sortMenuOpen = false })
                            }
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text("Your progress", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("${(progress * 100).toInt()}% reviewed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text("$reviewed / $total", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(alpha = .65f))
                        Button(onClick = { model.months.firstOrNull { it.pending > 0 }?.let { onOpen(it.month) } }, enabled = model.months.any { it.pending > 0 }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Text(if (model.scanning) "Scanning…" else "Start cleaning")
                        }
                    }
                }
            }
            item { Text("In progress", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp)) }
            if (sortedMonths.isEmpty() && !model.scanning) item {
                if (model.months.isEmpty()) EmptyState() else AllCaughtUp()
            }
            items(sortedMonths, key = { it.month }) { month ->
                MonthCard(month, onClick = { onOpen(month.month) })
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Failed Deleted Files", style = MaterialTheme.typography.titleMedium)
                            Text("${model.failed.size}", color = MaterialTheme.colorScheme.error)
                        }
                        model.failed.forEach { failure ->
                            Column(Modifier.padding(top = 10.dp)) {
                                Text(failure.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(failure.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { model.retry(failure) }) { Text("Retry") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

private enum class HomeSort { DATE_OLDEST, DATE_NEWEST, FILES_FEWEST, FILES_MOST }

@Composable
private fun MonthCard(month: MonthSummary, onClick: () -> Unit) {
    val reviewed = month.count - month.pending
    val progress = if (month.count == 0) 0f else reviewed.toFloat() / month.count
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(48.dp), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Folder, "Month", tint = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(month.month, style = MaterialTheme.typography.titleMedium)
                Text("$reviewed of ${month.count} reviewed · ${formatBytes(month.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                if (month.pending > 0) Text("Pending review", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Default.ChevronRight, "Open month", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No documents yet", style = MaterialTheme.typography.titleMedium)
            Text("Scan your device to find PDFs, Office files, TXT, and CSV documents.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AllCaughtUp() {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("All caught up", style = MaterialTheme.typography.titleMedium)
            Text("Completed review decks are hidden from this list.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckScreen(model: DocSwipeViewModel, month: String, onBack: () -> Unit, onReview: () -> Unit) {
    var undo by remember { mutableStateOf<Document?>(null) }
    var showCompletionDialog by remember(month) { mutableStateOf(false) }
    var showTutorial by remember(month) { mutableStateOf(!model.tutorialShown(month)) }
    val totalDocuments = model.totalDocuments(month)
    val active = model.deck.firstOrNull()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Surface(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(active?.name ?: month, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text("${model.deck.size} of $totalDocuments remaining", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    TextButton(onClick = onReview) { Text("Review") }
                }
            }
        }
    }, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Icons.Default.Delete, "Delete", MaterialTheme.colorScheme.error) { active?.let { undo = it; model.act(it, Triage.STAGED_DELETE) } }
                ActionButton(Icons.Default.Undo, "Undo", MaterialTheme.colorScheme.surfaceVariant) { undo?.let { model.act(it, Triage.UNREVIEWED); undo = null } }
                ActionButton(Icons.Default.SkipNext, "Skip", MaterialTheme.colorScheme.surfaceVariant) { active?.let { undo = it; model.act(it, Triage.SKIPPED) } }
                ActionButton(Icons.Default.Check, "Keep", MaterialTheme.colorScheme.primary) { active?.let { undo = it; model.act(it, Triage.KEEP) } }
            }
        }
    }) { padding ->
        if (active == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Month review complete", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { showCompletionDialog = true }) { Text("What next?") }
            }
        }
        else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            DocumentCard(active, Modifier.weight(1f).fillMaxWidth(), onLeft = { undo = active; model.act(active, Triage.STAGED_DELETE) }, onRight = { undo = active; model.act(active, Triage.KEEP) })
            Spacer(Modifier.height(12.dp))
        }
    }

    LaunchedEffect(active, month) {
        if (active == null && model.stagedCount(month) > 0) showCompletionDialog = true
    }
    if (showCompletionDialog) {
        val count = model.stagedCount(month)
        AlertDialog(
            onDismissRequest = { showCompletionDialog = false },
            title = { Text("Review complete") },
            text = { Text("You selected $count document${if (count == 1) "" else "s"} for deletion. What would you like to do?") },
            confirmButton = {
                TextButton(onClick = { showCompletionDialog = false; onReview() }) { Text("Review deletion list") }
            },
            dismissButton = {
                TextButton(onClick = { showCompletionDialog = false; model.deleteStaged(month); onBack() }) { Text("Delete selected") }
            }
        )
    }

    if (showTutorial && active != null) {
        TutorialOverlay(onDismiss = { showTutorial = false; model.markTutorialShown(month) })
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onClick, modifier = Modifier.size(54.dp).background(color, CircleShape)) { Icon(icon, label, tint = if (color == MaterialTheme.colorScheme.surfaceVariant) MaterialTheme.colorScheme.onSurface else Color.White) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocumentCard(document: Document, modifier: Modifier, onLeft: () -> Unit, onRight: () -> Unit) {
    var offset by remember(document.id) { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta -> offset += delta }
    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White), modifier = modifier
        .graphicsLayer { translationX = offset; rotationZ = offset / 34f }
        .draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            onDragStopped = {
                when { offset < -180f -> onLeft(); offset > 180f -> onRight() }
                offset = 0f
            }
        )) {
        Box(Modifier.fillMaxSize()) {
            DocumentViewer(document, Modifier.fillMaxSize())
            SwipeActionHint(offset)
        }
    }
}

@Composable
private fun SwipeActionHint(offset: Float) {
    val isDelete = offset < -12f
    val isKeep = offset > 12f
    if (!isDelete && !isKeep) return
    val color = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val alpha = (kotlin.math.abs(offset) / 180f).coerceIn(0.15f, 1f)
    Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = if (isDelete) Alignment.CenterStart else Alignment.CenterEnd) {
        Surface(color = color.copy(alpha = alpha), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (isDelete) Icons.Default.Delete else Icons.Default.Check, if (isDelete) "Delete" else "Keep", tint = Color.White)
                Text(if (isDelete) "DELETE" else "KEEP", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun TutorialOverlay(onDismiss: () -> Unit) {
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            pulse = 1f
            kotlinx.coroutines.delay(650)
            pulse = 0f
            kotlinx.coroutines.delay(350)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0x990F1713)), contentAlignment = Alignment.Center) {
        Surface(Modifier.padding(24.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("How to review", style = MaterialTheme.typography.headlineSmall)
                Text("Read the document, then choose what to do with a simple gesture.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TutorialRow("←", "Delete", MaterialTheme.colorScheme.error, pulse)
                TutorialRow("→", "Keep", MaterialTheme.colorScheme.primary, pulse)
                TutorialRow("↑ ↓", "Read and scroll", MaterialTheme.colorScheme.secondary, pulse)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Got it") }
            }
        }
    }
}

@Composable
private fun TutorialRow(gesture: String, label: String, color: Color, pulse: Float) {
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = .10f), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(gesture, color = color, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Text(if (pulse > 0f) "●" else "○", color = color)
    }
}

@Composable
private fun DocumentViewer(document: Document, modifier: Modifier) {
    Surface(modifier = modifier, color = Color.White) {
        when (document.extension) {
            "pdf" -> PdfPreview(document.path, Modifier.fillMaxSize().padding(10.dp))
            "txt", "csv" -> TextPreview(document.path, Modifier.fillMaxSize().padding(14.dp))
            else -> OfficePreview(document.path, Modifier.fillMaxSize().padding(10.dp))
        }
    }
}

@Composable
private fun PdfPreview(path: String, modifier: Modifier) {
    var totalPages by remember(path) { mutableStateOf(0) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var passwordRequired by remember(path) { mutableStateOf(false) }
    var passwordError by remember(path) { mutableStateOf(false) }
    var password by remember(path) { mutableStateOf("") }
    var submittedPassword by remember(path) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceAtMost(totalPages.coerceAtLeast(1)) } }
    val context = LocalContext.current

    LaunchedEffect(path, submittedPassword) {
        totalPages = 0
        loadError = null
        passwordRequired = false
        passwordError = false
        val result = withContext(Dispatchers.IO) {
            var descriptor: android.os.ParcelFileDescriptor? = null
            var document: PdfDocument? = null
            try {
                descriptor = android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val core = PdfiumCore(context)
                document = core.newDocument(descriptor, submittedPassword)
                val pageCount = core.getPageCount(document!!)
                core.closeDocument(document!!)
                document = null
                descriptor = null
                PdfLoadResult.Ready(pageCount)
            } catch (_: PdfPasswordException) {
                PdfLoadResult.PasswordRequired
            } catch (error: Exception) {
                Log.e("DocSwipe.Pdf", "Unable to open PDF: $path", error)
                PdfLoadResult.Error
            } finally {
                document?.let { runCatching { PdfiumCore(context).closeDocument(it) } }
                descriptor?.let { runCatching { it.close() } }
            }
        }
        when (result) {
            PdfLoadResult.PasswordRequired -> {
                passwordRequired = true
                passwordError = submittedPassword != null
            }
            PdfLoadResult.Error -> loadError = if (submittedPassword == null) "This PDF could not be opened." else "Incorrect password or unreadable PDF."
            is PdfLoadResult.Ready -> totalPages = result.pages
        }
    }

    if (passwordRequired) {
        Surface(modifier, color = Color(0xFFFFF7F5), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Password required", style = MaterialTheme.typography.titleMedium)
                Text(if (passwordError) "That password was not accepted. Try again." else "Enter the password to view this PDF.", color = if (passwordError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("PDF password") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { submittedPassword = password }, enabled = password.isNotEmpty()) { Text("Unlock PDF") }
            }
        }
    } else if (loadError != null) {
        Surface(modifier, color = Color(0xFFFFF7F5), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PDF preview unavailable", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(loadError!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (totalPages == 0) Text("Loading PDF preview…") else Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(totalPages) { index ->
                PdfPage(path, index, submittedPassword)
            }
        }
        Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), color = Color(0xEE18221E), shape = RoundedCornerShape(12.dp)) {
            Text("Page $currentPage of $totalPages", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PdfPage(path: String, index: Int, password: String?) {
    var page by remember(path, index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(path, index) { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(path, index, password) {
        val loaded = withContext(Dispatchers.IO) {
            var document: PdfDocument? = null
            try {
                val descriptor = android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val core = PdfiumCore(context)
                document = core.newDocument(descriptor, password)
                core.openPage(document!!, index)
                val width = core.getPageWidth(document!!, index)
                val height = core.getPageHeight(document!!, index)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                core.renderPageBitmap(document!!, bitmap, index, 0, 0, width, height, true)
                core.closeDocument(document!!)
                document = null
                bitmap
            } catch (exception: Exception) {
                Log.e("DocSwipe.Pdf", "Unable to render page ${index + 1}: $path", exception)
                null
            } finally {
                document?.let { runCatching { PdfiumCore(context).closeDocument(it) } }
            }
        }
        page = loaded
        error = loaded == null
    }
    if (error) {
        Surface(Modifier.fillMaxWidth().height(120.dp), color = Color(0xFFFFF7F5), shape = RoundedCornerShape(8.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Unable to render page ${index + 1}", color = MaterialTheme.colorScheme.error) }
        }
    } else if (page == null) {
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { Text("Loading page ${index + 1}…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        ZoomablePage(page!!, index)
    }
}

@Composable
private fun ZoomablePage(page: android.graphics.Bitmap, index: Int) {
    var scale by remember(index) { mutableFloatStateOf(1f) }
    Surface(Modifier.fillMaxWidth().pointerInput(index) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var previousDistance = 0f
            while (true) {
                val event = awaitPointerEvent()
                val pointers = event.changes
                if (pointers.all { it.changedToUp() }) break
                if (pointers.size >= 2) {
                    val first = pointers[0].position
                    val second = pointers[1].position
                    val dx = first.x - second.x
                    val dy = first.y - second.y
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (previousDistance > 0f) scale = (scale * (distance / previousDistance)).coerceIn(1f, 3f)
                    previousDistance = distance
                    pointers.forEach { it.consumePositionChange() }
                } else {
                    previousDistance = 0f
                }
            }
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }, color = Color.White, shape = RoundedCornerShape(4.dp)) {
        Image(page.asImageBitmap(), "PDF page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

private sealed interface PdfLoadResult {
    data class Ready(val pages: Int) : PdfLoadResult
    data object PasswordRequired : PdfLoadResult
    data object Error : PdfLoadResult
}

@Composable
private fun TextPreview(path: String, modifier: Modifier) {
    var lines by remember(path) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(path) {
        lines = withContext(Dispatchers.IO) {
            runCatching {
                File(path).inputStream().bufferedReader(Charsets.UTF_8).useLines { it.take(400).toList() }
            }.getOrElse { listOf("Preview unavailable") }
        }
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ -> scale = (scale * zoomChange).coerceIn(1f, 3f) }
    Box(modifier.transformable(transformState).graphicsLayer { scaleX = scale; scaleY = scale }) {
        if (lines == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading text…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(lines!!) { Text(it, color = Color(0xFF18221E)) }
            }
        }
    }
}

@Composable
private fun OfficePreview(path: String, modifier: Modifier) {
    val context = LocalContext.current
    var renderedPath by remember(path) { mutableStateOf<String?>(null) }
    var renderError by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        renderedPath = null
        renderError = null
        val result = withContext(Dispatchers.IO) { renderOfficeDocument(context, path) }
        if (result.startsWith("ERROR:")) renderError = result.removePrefix("ERROR:") else renderedPath = result
    }
    when {
        renderError != null -> Surface(modifier, color = Color(0xFFFFF7F5), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Office preview unavailable", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(renderError!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        renderedPath == null -> Box(modifier, contentAlignment = Alignment.Center) { Text("Rendering document…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else -> AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = false
                    webViewClient = WebViewClient()
                    setBackgroundColor(android.graphics.Color.WHITE)
                }
            },
            update = { webView ->
                if (webView.url != renderedPath) webView.loadUrl(renderedPath!!)
            }
        )
    }
}

private fun renderOfficeDocument(context: android.content.Context, path: String): String {
    return try {
    OdrAndroid.init(context.applicationContext)
    val output = File(context.cacheDir, "office-render/${File(path).nameWithoutExtension}-${File(path).lastModified()}").apply { mkdirs() }
    val decoded = Odr.open(path)
    val service = Html.translate(decoded, output.absolutePath, HtmlConfig().apply {
        embedImages = true
        embedShippedResources = true
        formatHtml = true
        viewportContent = "width=device-width, initial-scale=1.0"
    })
    val rendered = service.bringOffline(output.absolutePath)
    val pages = rendered.pages()
    if (pages.isEmpty()) {
        service.close()
        decoded.close()
        "ERROR:The document did not contain a renderable page."
    }
    else if (pages.size == 1) {
        val page = File(pages.first().path).toURI().toString()
        service.close()
        decoded.close()
        page
    }
    else {
        val wrapper = File(output, "docswipe-wrapper.html")
        wrapper.writeText("""
        <!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>
        html,body{margin:0;padding:0;background:#eef2ef;}iframe{display:block;width:100%;min-height:100vh;border:0;margin:0 0 12px;background:white;}
        </style></head><body>${pages.joinToString("") { "<iframe src=\"${File(it.path).toURI()}\"></iframe>" }}</body></html>
        """.trimIndent())
        service.close()
        decoded.close()
        wrapper.toURI().toString()
    }
    } catch (error: Exception) {
        Log.e("DocSwipe.Office", "Unable to render Office document: $path", error)
        "ERROR:${error.message ?: "The file could not be rendered."}"
    }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (model.includeHidden) "Enabled" else "Disabled", color = if (model.includeHidden) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(
                    checked = model.includeHidden,
                    onCheckedChange = { enabled -> model.setHidden(enabled); onScan() },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF43A047),
                        uncheckedThumbColor = Color(0xFFE0E0E0),
                        uncheckedTrackColor = Color(0xFF9E9E9E)
                    )
                )
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
