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

/** 从 GitHub Releases 获取的更新信息。 */
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String
)

/** 更新检查与下载安装(基于 GitHub Releases,无需自建服务器)。 */
object UpdateChecker {
    private const val REPO = "ttu-ax/timeController"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    var pendingDownloadId = -1L
        private set
    var pendingFile: File? = null
        private set

    /** 在后台线程调用:查询最新 Release,失败返回 null(调用方静默处理)。 */
    fun checkLatest(): UpdateInfo? {
        return try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ProcrastinationGate")
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            val version = json.optString("tag_name", "").removePrefix("v")
            if (version.isBlank()) return null
            val assets = json.optJSONArray("assets") ?: return null
            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    downloadUrl = asset.optString("browser_download_url")
                    break
                }
            }
            val url = downloadUrl ?: return null
            UpdateInfo(
                latestVersion = version,
                downloadUrl = url,
                releaseNotes = json.optString("body", "")
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
