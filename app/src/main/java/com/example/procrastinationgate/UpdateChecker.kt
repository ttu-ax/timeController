package com.example.procrastinationgate

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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

    /** 本次进程内是否已经就「下载完成」处理过一次,避免每次 onResume 重复动作。 */
    var promptedThisProcess = false

    /**
     * 用系统 DownloadManager 下载更新包。
     *
     * 落到公共「下载」目录而不是 getExternalFilesDir:
     * 后者在 Android 11+ 属于应用专属目录,DownloadManager 未必能写进去(实测 vivo 上
     * 请求被忽略,文件留在了它自己的临时目录、并且丢了 .apk 后缀,装不上也找不到)。
     * 公共下载目录对所有文件管理器可见,后缀也不会丢,用户在应用内失败时还能手动装。
     */
    fun startDownload(context: Context, url: String, versionName: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "拖延之门-v$versionName.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("拖延之门 v$versionName")
            .setDescription("下载完成后可以直接安装")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = dm.enqueue(request)
        GatePreferences(context).pendingUpdateDownloadId = id
        promptedThisProcess = false
        return id
    }

    /**
     * 查询已经下载完的更新包,交付一个能喂给安装器的 uri;没完成或失败返回 null。
     *
     * 不直接把手里的 uri 交给安装器:DownloadManager 记的可能是
     * content://downloads/... ,那个 provider 不支持跨应用授权读取,
     * 安装器会拿不到文件。所以一律先读出来复制一份到自己目录,
     * 再用 FileProvider 交付——这条路径才受我们控制。
     */
    fun downloadedApk(context: Context): Uri? {
        val id = GatePreferences(context).pendingUpdateDownloadId
        if (id <= 0) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = try {
            dm.query(DownloadManager.Query().setFilterById(id))
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            if (!c.moveToFirst()) return null
            val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0 || c.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) {
                return null
            }
            val uriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val localUri = if (uriIndex >= 0) c.getString(uriIndex) else null
            if (localUri.isNullOrBlank()) return null
            return materialize(context, Uri.parse(localUri))
        }
        return null
    }

    /** 把下载结果读成一个位于应用专属目录、能被 FileProvider 交付的文件。 */
    private fun materialize(context: Context, source: Uri): Uri? {
        val ownDir = context.getExternalFilesDir(null) ?: return null
        val target = File(ownDir, "update.apk")
        try {
            when (source.scheme) {
                "content" -> context.contentResolver.openInputStream(source)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                "file" -> {
                    val origin = source.path?.let { File(it) } ?: return null
                    if (!origin.exists()) return null
                    origin.copyTo(target, overwrite = true)
                }
                else -> return null
            }
        } catch (e: Exception) {
            return null
        }
        if (target.length() <= 0L) return null
        return try {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        } catch (e: Exception) {
            null
        }
    }

    /** 下载记录用完后清掉,免得下次前台又去查一个旧的 id。 */
    fun clearPending(context: Context) {
        GatePreferences(context).pendingUpdateDownloadId = -1L
        promptedThisProcess = false
    }

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

    /** 通过系统安装器安装已下载的 APK。 */
    fun install(context: Context, uri: Uri): Boolean = try {
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
