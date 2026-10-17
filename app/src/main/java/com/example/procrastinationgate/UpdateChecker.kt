package com.example.procrastinationgate

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 从更新源获取的更新信息。 */
data class UpdateInfo(
    val latestVersion: String,
    val versionCode: Long,
    val downloadUrl: String,
    val releaseNotes: String,
    val sha256: String? = null
)

/** 更新检查与下载安装(指向自建站点 acupofttu.top)。 */
object UpdateChecker {
    private const val UPDATE_URL = "https://acupofttu.top/apps/time-controller/update.json"

    var pendingDownloadId = -1L
        private set
    var pendingFile: File? = null
        private set

    /** 在后台线程调用:查询最新版本,失败返回 null(调用方静默处理)。 */
    fun checkLatest(): UpdateInfo? {
        return try {
            val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "ProcrastinationGate")
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            val version = json.optString("version_name", "").removePrefix("v")
            if (version.isBlank()) return null
            val downloadUrl = json.optString("download_url", "").takeIf { it.isNotBlank() } ?: return null
            val versionCode = json.optLong("version_code", -1L)
            val sha256 = json.optString("sha256", "").takeIf { it.isNotBlank() }

            val notes = when (val rawNotes = json.opt("release_notes")) {
                is org.json.JSONArray -> {
                    (0 until rawNotes.length())
                        .mapNotNull { rawNotes.optString(it).takeIf(String::isNotBlank) }
                        .joinToString("\n") { "• $it" }
                }
                is String -> rawNotes
                else -> ""
            }

            UpdateInfo(
                latestVersion = version,
                versionCode = versionCode,
                downloadUrl = downloadUrl,
                releaseNotes = notes,
                sha256 = sha256
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 版本号比较:latest 是否高于 current(支持 0.1.0 / 1.2 等形式)。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").mapNotNull { it.toIntOrNull() }
        val b = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 用系统 DownloadManager 下载 APK 到应用外部目录,返回下载 id。 */
    fun startDownload(context: Context, url: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val file = File(context.getExternalFilesDir(null), "update.apk")
        file.delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("拖延之门 更新下载")
            .setDescription("下载完成后可安装新版本")
            .setDestinationUri(Uri.fromFile(file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        pendingFile = file
        pendingDownloadId = dm.enqueue(request)
        return pendingDownloadId
    }

    /** 通过系统安装器安装已下载的 APK。 */
    fun install(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
