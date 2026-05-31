package com.example.tailclip.ws

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object HttpManager {

    private const val TAG = "HttpManager"

    /**
     * Uploads a file via HTTP POST (multipart/form-data) to the backend.
     * Uses HttpURLConnection to stream the file, preventing OutOfMemory errors for large files.
     */
    suspend fun uploadFile(
        context: Context,
        uri: Uri,
        host: String,
        port: Int,
        fromDevice: String = "unknown",
        toDevices: String = "all"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = getFileName(context, uri) ?: "shared_file.dat"
            val url = URL("http://$host:$port/push-file")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            
            val boundary = "TailClipBoundary${System.currentTimeMillis()}"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            
            connection.outputStream.use { os ->
                // Write from_device field
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"from_device\"\r\n\r\n".toByteArray())
                os.write("$fromDevice\r\n".toByteArray())

                // Write to_devices field
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"to_devices\"\r\n\r\n".toByteArray())
                os.write("$toDevices\r\n".toByteArray())

                // Write file field
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n".toByteArray())
                os.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                
                // Stream file content
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.copyTo(os)
                }
                
                // Write multipart footer
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            
            val responseCode = connection.responseCode
            Log.i(TAG, "Upload response code: $responseCode")
            return@withContext responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload file: ${e.message}")
            return@withContext false
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    /**
     * Downloads a file from the PC to the Android device using DownloadManager.
     */
    fun downloadFile(context: Context, filename: String, host: String, port: Int) {
        try {
            val url = "http://$host:$port/download/${android.net.Uri.encode(filename)}"
            val request = android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setDescription("Downloading from PC via TailClip")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "TailClip/$filename")
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            downloadManager.enqueue(request)
            Log.i(TAG, "Enqueued download for $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download: ${e.message}")
        }
    }
}
