package com.example.ytdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val speedRegex = Regex("""at\s+([\d.]+\s*[KMGT]?i?B/s)""")

class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var cancelled = false
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cancel request, from the app button or the notification
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            if (!running) stopSelf()
            return START_NOT_STICKY
        }

        cancelled = false
        createChannels()
        startForeground(NOTIFICATION_ID, buildNotification("Starting...", 0, true))

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val link = intent.getStringExtra("link") ?: ""
        val format = intent.getStringExtra("format") ?: "best"
        val audioFormat = intent.getStringExtra("audioFormat")
        val isVideo = intent.getBooleanExtra("isVideo", false)
        val folder = Uri.parse(intent.getStringExtra("folder") ?: "")
        val fileName = intent.getStringExtra("fileName") ?: ""
        val replace = intent.getBooleanExtra("replace", false)

        scope.launch {
            running = true
            // Refreshes the notification from the same state the app shows
            val syncJob = launch { keepNotificationInSync() }

            val result = try {
                download(link, format, audioFormat, isVideo, folder, fileName, replace)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            syncJob.cancelAndJoin()
            running = false
            File(cacheDir, "downloads").deleteRecursively()

            if (cancelled && !result.startsWith("Saved")) {
                DownloadState.state.value = DownloadUi()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val ok = result.startsWith("Saved")
            val old = DownloadState.state.value
            DownloadState.state.value = old.copy(
                running = false,
                progress = if (ok) 100f else old.progress,
                status = result,
                speed = "",
                eta = -1
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            showResultNotification(result, folder, ok)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun keepNotificationInSync() {
        while (true) {
            val s = DownloadState.state.value
            val percent = s.progress.toInt()
            val text = if (s.speed.isNotEmpty()) "$percent%  •  ${s.speed}" else "$percent%"
            updateNotification(buildNotification(text, percent, percent == 0))
            delay(500)
        }
    }

    private fun download(
        link: String,
        format: String,
        audioFormat: String?,
        isVideo: Boolean,
        folder: Uri,
        fileName: String,
        replace: Boolean
    ): String {
        val dir = File(cacheDir, "downloads")
        dir.deleteRecursively()
        dir.mkdirs()

        val request = YoutubeDLRequest(link)
        request.addOption("--no-playlist")
        request.addOption("-f", format)
        if (audioFormat != null) {
            request.addOption("-x")
            request.addOption("--audio-format", audioFormat)
        } else if (isVideo) {
            request.addOption("--merge-output-format", "mp4")
        }
        // The final name is chosen when copying, so the cache name is simple
        request.addOption("-o", "${dir.absolutePath}/download.%(ext)s")

        var stream = 0
        var lastOverall = 0f
        var lastSpeed = ""

        YoutubeDL.getInstance().execute(request, PROCESS_ID) { p, eta, line ->
            // Each new stream (video, then audio) starts with a Destination line
            if (line.contains("[download] Destination:")) stream++
            speedRegex.find(line)?.let { lastSpeed = it.groupValues[1] }
            if (p < 0) return@execute

            val percent = p.coerceIn(0f, 100f)
            // Video = two streams: the first fills 0-90%, the audio fills 90-100%
            val overall = when {
                !isVideo -> percent
                stream <= 1 -> percent * 0.9f
                else -> 90f + percent * 0.1f
            }
            lastOverall = maxOf(lastOverall, overall)

            DownloadState.state.value = DownloadUi(
                running = true,
                progress = lastOverall,
                status = "Downloading...",
                speed = lastSpeed,
                eta = eta
            )
        }

        val file = dir.listFiles()?.maxByOrNull { it.lastModified() }
            ?: return "Error: no file was created"

        val name = fileName.ifBlank { file.nameWithoutExtension }
        val saved = copyToFolder(this, file, folder, name, replace)
        return if (saved != null) {
            HistoryStore.add(
                this,
                HistoryItem(
                    name = saved.name ?: name,
                    uri = saved.uri.toString(),
                    mime = saved.type ?: "*/*",
                    time = System.currentTimeMillis()
                )
            )
            "Saved: ${saved.name}"
        } else {
            "Error: could not save the file"
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL, "Download progress", NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL, "Download results", NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent())
            .build()

    private fun updateNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun showResultNotification(text: String, folder: Uri, ok: Boolean) {
        val builder = NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (ok) "Download complete" else "Download failed")
            .setContentText(text.take(100))
            .setAutoCancel(true)

        if (ok) {
            try {
                // Tapping the notification opens the folder in the file manager
                val folderDoc = DocumentsContract.buildDocumentUriUsingTree(
                    folder, DocumentsContract.getTreeDocumentId(folder)
                )
                val openFolder = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(folderDoc, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, openFolder,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } catch (e: Exception) {
                // No click action if the folder link can't be built
            }
        }

        getSystemService(NotificationManager::class.java).notify(RESULT_ID, builder.build())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "cancel_download"
        private const val PROCESS_ID = "yt_download"
        private const val PROGRESS_CHANNEL = "downloads"
        private const val RESULT_CHANNEL = "results"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_ID = 2
    }
}

// Copies a file from the app cache into the folder the user picked
fun copyToFolder(
    context: Context,
    file: File,
    folderUri: Uri,
    name: String,
    replace: Boolean
): DocumentFile? {
    val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return null
    val fullName = "$name.${file.extension}"

    // Without this, Android would silently save "name (1).mp4" instead
    if (replace) folder.findFile(fullName)?.delete()

    val mime = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    val target = folder.createFile(mime, fullName) ?: return null
    context.contentResolver.openOutputStream(target.uri)?.use { out ->
        file.inputStream().use { input -> input.copyTo(out) }
    }
    file.delete()
    return target
}