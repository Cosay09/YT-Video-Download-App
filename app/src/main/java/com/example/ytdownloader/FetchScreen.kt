package com.example.ytdownloader

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// One option the user can pick, e.g. "Video 720p" or "Audio (mp3)"
data class DownloadChoice(
    val label: String,
    val format: String,              // yt-dlp format selector
    val audioFormat: String? = null  // set only when converting to mp3
)

@Composable
fun FetchScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val dl by DownloadState.state.collectAsState()

    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(HistoryStore.load(context)) }
    var link by remember { mutableStateOf("") }
    var fetchedLink by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var thumbnail by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showConflict by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf(listOf<DownloadChoice>()) }
    var selected by remember { mutableStateOf<DownloadChoice?>(null) }

    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var folderUri by remember {
        mutableStateOf(
            prefs.getString("folder", null)?.let { Uri.parse(it) })
    }
    val folderName = remember(folderUri) {
        folderUri?.let { DocumentFile.fromTreeUri(context, it)?.name }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Keep access to this folder after the app restarts
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString("folder", uri.toString()).apply()
            folderUri = uri
        }
    }

    // Android 13+ needs permission to show the progress notification
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Updates the download engine quietly, at most once a day
    LaunchedEffect(Unit) {
        val dayMs = 24 * 60 * 60 * 1000L
        val last = prefs.getLong("last_engine_update", 0L)
        if (System.currentTimeMillis() - last > dayMs && !DownloadState.state.value.running) {
            updating = true
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance()
                        .updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
                }
                prefs.edit().putLong("last_engine_update", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                // No internet or the update failed; it tries again next time the app opens
            }
            updating = false
        }
    }

    // Reload the history when the app opens and after each download ends
    LaunchedEffect(dl.running) {
        history = HistoryStore.load(context)
    }

    val ext = selected?.let { extensionFor(it) } ?: ""
    val cleanedName = cleanName(fileName)

    fun startDownload(replace: Boolean) {
        val choice = selected ?: return
        val folder = folderUri ?: return
        DownloadState.state.value = DownloadUi(running = true, status = "Starting...")
        val intent = Intent(context, DownloadService::class.java).apply {
            putExtra("link", fetchedLink)
            putExtra("format", choice.format)
            putExtra("audioFormat", choice.audioFormat)
            putExtra("isVideo", choice.label.startsWith("Video"))
            putExtra("folder", folder.toString())
            putExtra("fileName", cleanedName)
            putExtra("replace", replace)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    Column(
        modifier = modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "YT Downloader",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Paste a link, pick a quality, save it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = { showHistory = true }) {
                Text("History")
            }
        }

        // Link input
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("YouTube link") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        TextButton(onClick = {
                            if (link.isEmpty()) {
                                clipboard.getText()?.text?.let { link = it.trim() }
                            } else {
                                link = ""
                            }
                        }) {
                            Text(if (link.isEmpty()) "Paste" else "Clear")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !loading && !dl.running && link.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    onClick = {
                        scope.launch {
                            loading = true
                            message = ""
                            title = ""
                            thumbnail = null
                            choices = emptyList()
                            selected = null
                            try {
                                val info = withContext(Dispatchers.IO) {
                                    val request = YoutubeDLRequest(link.trim())
                                    request.addOption("--no-playlist")
                                    YoutubeDL.getInstance().getInfo(request)
                                }
                                title = info.title ?: "No title found"
                                thumbnail = info.thumbnail
                                fileName = cleanName(title)
                                fetchedLink = link.trim()
                                choices = buildChoices(info)
                                selected = choices.firstOrNull()
                            } catch (e: Exception) {
                                message = "Error: ${e.message}"
                            }
                            loading = false
                        }
                    }
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Fetching...")
                    } else {
                        Text("Fetch")
                    }
                }

                TextButton(
                    enabled = !updating && !dl.running,
                    modifier = Modifier.align(Alignment.End),
                    onClick = {
                        scope.launch {
                            updating = true
                            message = "Updating engine..."
                            message = try {
                                val result = withContext(Dispatchers.IO) {
                                    YoutubeDL.getInstance().updateYoutubeDL(
                                        context,
                                        YoutubeDL.UpdateChannel.NIGHTLY
                                    )
                                }
                                val version = YoutubeDL.getInstance().versionName(context)
                                "Engine update: $result (version $version)"
                            } catch (e: Exception) {
                                "Update error: ${e.message}"
                            }
                            updating = false
                        }
                    }
                ) {
                    Text(if (updating) "Updating..." else "Update engine")
                }
            }
        }

        // Fetch / update messages
        if (message.isNotEmpty()) {
            val isError = message.startsWith("Error") || message.startsWith("Update error")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Video details, quality and file name
        if (title.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!thumbnail.isNullOrEmpty()) {
                        AsyncImage(
                            model = thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }

                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selected?.label ?: "Choose quality")
                            Spacer(Modifier.weight(1f))
                            Text("▼")
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            choices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.label) },
                                    onClick = {
                                        selected = choice
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File name") },
                        suffix = { Text(".$ext") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Save location
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Save to",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        folderName ?: "No folder chosen",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text(if (folderUri == null) "Choose" else "Change")
                }
            }
        }

        // Download button, fills with green as the download progresses
        if (title.isNotEmpty()) {
            DownloadButton(
                enabled = selected != null && folderUri != null &&
                        cleanedName.isNotEmpty() && !loading,
                running = dl.running,
                progress = dl.progress,
                onClick = {
                    val folder = folderUri
                    if (folder != null) {
                        scope.launch {
                            val exists = withContext(Dispatchers.IO) {
                                DocumentFile.fromTreeUri(context, folder)
                                    ?.findFile("$cleanedName.$ext") != null
                            }
                            if (exists) showConflict = true else startDownload(replace = false)
                        }
                    }
                }
            )

            // Speed and time left, shown only once yt-dlp reports them
            if (dl.running) {
                val eta = formatEta(dl.eta)
                val info = listOf(dl.speed, if (eta.isNotEmpty()) "ETA $eta" else "")
                    .filter { it.isNotEmpty() }
                    .joinToString("  •  ")

                if (info.isNotEmpty()) {
                    Text(
                        info,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                TextButton(
                    onClick = {
                        context.startService(
                            Intent(context, DownloadService::class.java)
                                .setAction(DownloadService.ACTION_CANCEL)
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Cancel")
                }
            }
        }

        // Shown only when a download fails
        if (!dl.running && dl.status.startsWith("Error")) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    dl.status,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    // Asked when a file with the same name already exists in the folder
    if (showHistory) {
        HistorySheet(
            entries = history,
            onOpen = { item ->
                val exists = DocumentFile.fromSingleUri(context, Uri.parse(item.uri))?.exists() == true
                if (!exists) {
                    HistoryStore.remove(context, item)
                    history = HistoryStore.load(context)
                    Toast.makeText(
                        context, "File not found. It may have been moved or deleted.", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(item.uri), item.mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No app found to open this file.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRemove = { item ->
                HistoryStore.remove(context, item)
                history = HistoryStore.load(context)
            },
            onClearAll = {
                HistoryStore.clear(context)
                history = emptyList()
            },
            onDismiss = { showHistory = false }
        )
    }
}

// Removes characters that are not allowed in file names
fun cleanName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(100)

// The file type the chosen option will produce
fun extensionFor(choice: DownloadChoice): String =
    choice.audioFormat ?: if (choice.label.startsWith("Video")) "mp4" else "m4a"

// Turns the video's real format list into the options shown on screen
fun buildChoices(info: VideoInfo): List<DownloadChoice> {
    val heights = info.formats.orEmpty()
        .filter { it.vcodec != null && it.vcodec != "none" && it.height > 0 }
        .map { it.height }
        .distinct()
        .sortedDescending()

    val video = heights.map { h ->
        DownloadChoice("Video ${h}p", "bv*[height<=$h]+ba/b[height<=$h]")
    }

    val audio = listOf(
        DownloadChoice("Audio (m4a)", "ba[ext=m4a]/ba"),
        DownloadChoice("Audio (mp3)", "ba", audioFormat = "mp3")
    )

    return video + audio
}

// A button that fills with green from left to right while downloading
@Composable
fun DownloadButton(
    enabled: Boolean,
    running: Boolean,
    progress: Float,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val fraction by animateFloatAsState(
        targetValue = if (running) (progress / 100f).coerceIn(0f, 1f) else 0f,
        label = "downloadFill"
    )
    val green = Color(0xFF2E9E5B)
    val base = when {
        running -> Color(0xFF3A3F47)
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val label = when {
        !running -> "Download"
        progress > 0f -> "Downloading ${progress.toInt()}%"
        else -> "Starting..."
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(base)
            .clickable(enabled = enabled && !running, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(green)
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (running) Color.White else MaterialTheme.colorScheme.onPrimary
        )
    }
}

// Formats seconds as m:ss, or nothing if unknown
fun formatEta(seconds: Long): String {
    if (seconds < 0) return ""
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    entries: List<HistoryItem>,
    onOpen: (HistoryItem) -> Unit,
    onRemove: (HistoryItem) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "History",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClearAll) { Text("Clear all") }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    "Nothing downloaded yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn {
                    items(entries, key = { it.uri }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(item) }
                                .padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    dateFormat.format(Date(item.time)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onRemove(item) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}