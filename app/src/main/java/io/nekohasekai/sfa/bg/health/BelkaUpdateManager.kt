package io.nekohasekai.sfa.bg.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.nekohasekai.sfa.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class BelkaUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val shaUrl: String,
)

data class BelkaUpdateUiState(
    val checking: Boolean = false,
    val available: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val info: BelkaUpdateInfo? = null,
    val message: String? = null,
    val lastCheckedAt: Long = 0L,
)

object BelkaUpdateManager {
    private const val REPOSITORY = "meduntsov/sing-box-for-android"
    private const val RELEASE_API =
        "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val RELEASE_TAG_PREFIX = "belkavpn-"
    private const val APK_ASSET = "BelkaVPN-arm64-v8a.apk"
    private const val SHA_ASSET = "BelkaVPN-arm64-v8a.apk.sha256"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "belkavpn_update"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(BelkaUpdateUiState())
    val state: StateFlow<BelkaUpdateUiState> = _state.asStateFlow()

    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastChecked = prefs.getLong("last_checked", 0L)

            if (!force && now - lastChecked < CHECK_INTERVAL_MS) {
                restoreCachedState(prefs, lastChecked)?.let {
                    _state.value = it
                    return@launch
                }
            }

            _state.update { it.copy(checking = true, message = null) }
            try {
                val release = JSONObject(readText(RELEASE_API, 512 * 1024))
                val tag = release.getString("tag_name")
                val remoteCode = tag
                    .removePrefix(RELEASE_TAG_PREFIX)
                    .toIntOrNull()
                    ?: error("Неверный tag релиза: $tag")

                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                var shaUrl: String? = null
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    when (asset.optString("name")) {
                        APK_ASSET -> apkUrl = asset.optString("browser_download_url")
                        SHA_ASSET -> shaUrl = asset.optString("browser_download_url")
                    }
                }
                require(!apkUrl.isNullOrBlank()) { "$APK_ASSET не найден в latest release" }
                require(!shaUrl.isNullOrBlank()) { "$SHA_ASSET не найден в latest release" }

                val versionName = release.optString("name")
                    .removePrefix("BelkaVPN ")
                    .ifBlank { tag }

                val info = BelkaUpdateInfo(
                    versionCode = remoteCode,
                    versionName = versionName,
                    apkUrl = apkUrl,
                    shaUrl = shaUrl,
                )
                val available = remoteCode > BuildConfig.VERSION_CODE

                prefs.edit()
                    .putLong("last_checked", now)
                    .putInt("version_code", remoteCode)
                    .putString("version_name", versionName)
                    .putString("apk_url", apkUrl)
                    .putString("sha_url", shaUrl)
                    .apply()

                _state.value = BelkaUpdateUiState(
                    checking = false,
                    available = available,
                    info = info,
                    message = if (available) "Доступна BelkaVPN $versionName" else null,
                    lastCheckedAt = now,
                )
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        checking = false,
                        message = "Проверка обновлений: ${e.message ?: "ошибка"}",
                    )
                }
            }
        }
    }

    fun downloadAndInstall(context: Context) {
        val app = context.applicationContext
        val info = _state.value.info ?: return
        if (_state.value.downloading) return

        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !app.packageManager.canRequestPackageInstalls()
                ) {
                    _state.update {
                        it.copy(message = "Разрешите BelkaVPN устанавливать обновления")
                    }
                    withContext(Dispatchers.Main) {
                        app.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${app.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    return@launch
                }

                _state.update {
                    it.copy(
                        downloading = true,
                        progress = 0,
                        message = "Скачивание обновления…",
                    )
                }

                val expectedSha = readText(info.shaUrl, 8 * 1024)
                    .trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.lowercase(Locale.US)
                    ?: error("Пустой SHA-256")
                require(expectedSha.matches(Regex("[0-9a-f]{64}"))) {
                    "Некорректный SHA-256"
                }

                val updateDir = File(app.cacheDir, "belkavpn-update").apply { mkdirs() }
                val apk = File(updateDir, APK_ASSET)
                downloadFile(info.apkUrl, apk)

                _state.update { it.copy(message = "Проверка SHA-256…") }
                val actualSha = sha256(apk)
                require(actualSha == expectedSha) {
                    "SHA-256 не совпадает; установка отменена"
                }

                _state.update {
                    it.copy(
                        downloading = false,
                        progress = 100,
                        message = "SHA-256 проверен. Открываю установщик…",
                    )
                }

                val uri = FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.cache",
                    apk,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    app.startActivity(intent)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        downloading = false,
                        progress = null,
                        message = "Обновление: ${e.message ?: "ошибка"}",
                    )
                }
            }
        }
    }

    private fun restoreCachedState(
        prefs: android.content.SharedPreferences,
        lastChecked: Long,
    ): BelkaUpdateUiState? {
        val code = prefs.getInt("version_code", 0)
        val name = prefs.getString("version_name", null) ?: return null
        val apk = prefs.getString("apk_url", null) ?: return null
        val sha = prefs.getString("sha_url", null) ?: return null
        if (code <= 0) return null

        val info = BelkaUpdateInfo(code, name, apk, sha)
        val available = code > BuildConfig.VERSION_CODE
        return BelkaUpdateUiState(
            checking = false,
            available = available,
            info = info,
            message = if (available) "Доступна BelkaVPN $name" else null,
            lastCheckedAt = lastChecked,
        )
    }

    private fun readText(url: String, maxBytes: Int): String {
        val connection = open(url)
        require(connection.responseCode in 200..299) {
            "HTTP ${connection.responseCode}"
        }
        val data = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (output.size() < maxBytes) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size, maxBytes - output.size()),
                )
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        connection.disconnect()
        return String(data, Charsets.UTF_8)
    }

    private fun downloadFile(url: String, target: File) {
        val connection = open(url)
        connection.setRequestProperty("Accept-Encoding", "identity")
        require(connection.responseCode in 200..299) {
            "HTTP ${connection.responseCode}"
        }
        val totalBytes = connection.contentLengthLong
        var downloaded = 0L
        target.outputStream().buffered().use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    if (totalBytes > 0) {
                        val progress = ((downloaded * 100L) / totalBytes)
                            .toInt()
                            .coerceIn(0, 100)
                        _state.update { it.copy(progress = progress) }
                    }
                }
            }
        }
        connection.disconnect()
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BelkaVPN/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
