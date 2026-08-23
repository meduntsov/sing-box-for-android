package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

class GitHubUpdateChecker : Closeable {
    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/meduntsov/sing-box-for-android/releases"
        private const val METADATA_FILENAME = "SFA-version-metadata.json"
        private const val BELKA_APK_PREFIX = "BelkaVPN-"
        private val BELKA_VERSION_RE = Regex("^BelkaVPN\\s+(.+)$")
        private val BELKA_TAG_RE = Regex("^belkavpn-(\\d+)$")
    }

    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(track: UpdateTrack, githubToken: String): UpdateInfo? {
        val releases = getReleases(githubToken)
        var selected: ReleaseCandidate? = null

        for (release in releases) {
            if (!isReleaseInTrack(release, track)) continue
            if (release.assets.none { it.name.startsWith(BELKA_APK_PREFIX) && it.name.endsWith(".apk") }) {
                continue
            }

            val metadata =
                runCatching { downloadMetadata(release) }.getOrNull()
                    ?: metadataFromBelkaRelease(release)
                    ?: continue

            // Android PackageManager requires an update to have a versionCode
            // greater than the installed package. Do not offer an APK that has
            // a newer-looking semantic version but would be rejected as a
            // downgrade by Android.
            if (!isNewerThanCurrent(metadata)) continue

            val currentBest = selected
            if (currentBest == null || isBetterVersion(metadata, currentBest.metadata)) {
                selected = ReleaseCandidate(release, metadata)
            }
        }

        val release = selected?.release ?: return null
        val metadata = selected.metadata

        val isLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val apkAsset = release.assets.find { asset ->
            asset.name.endsWith(".apk") &&
                asset.name.startsWith(BELKA_APK_PREFIX) &&
                !asset.name.contains("play") &&
                asset.name.contains("legacy-android-5") == isLegacy
        }

        return UpdateInfo(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl,
            releaseUrl = release.htmlUrl,
            releaseNotes = release.body,
            isPrerelease = release.prerelease,
            fileSize = apkAsset?.size ?: 0,
        )
    }

    private fun getReleases(githubToken: String): List<GitHubRelease> {
        val request = client.newRequest()
        request.setURL(RELEASES_URL)
        request.setHeader("Accept", "application/vnd.github.v3+json")
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            request.setHeader("Authorization", "Bearer $token")
        }
        request.setUserAgent(HTTPClient.userAgent)

        val response = request.execute()
        val content = response.content.unwrap
        return json.decodeFromString(content)
    }

    private fun isReleaseInTrack(release: GitHubRelease, track: UpdateTrack): Boolean {
        if (release.draft) return false
        return when (track) {
            UpdateTrack.STABLE -> !release.prerelease
            UpdateTrack.BETA -> true
        }
    }

    private fun isNewerThanCurrent(metadata: VersionMetadata): Boolean =
        metadata.versionCode > BuildConfig.VERSION_CODE

    private fun isBetterVersion(version: VersionMetadata, other: VersionMetadata): Boolean {
        if (Libbox.compareSemver(version.versionName, other.versionName)) return true
        if (Libbox.compareSemver(other.versionName, version.versionName)) return false
        return version.versionCode > other.versionCode
    }

    private fun metadataFromBelkaRelease(release: GitHubRelease): VersionMetadata? {
        val versionName = BELKA_VERSION_RE.matchEntire(release.name)?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val versionCode = BELKA_TAG_RE.matchEntire(release.tagName)?.groupValues?.get(1)
            ?.toIntOrNull()
            ?: return null
        return VersionMetadata(versionCode = versionCode, versionName = versionName)
    }

    private fun downloadMetadata(release: GitHubRelease): VersionMetadata? {
        val metadataAsset = release.assets.find { it.name == METADATA_FILENAME } ?: return null

        val request = client.newRequest()
        request.setURL(metadataAsset.browserDownloadUrl)
        request.setUserAgent(HTTPClient.userAgent)

        val response = request.execute()
        val content = response.content.unwrap
        return json.decodeFromString<VersionMetadata>(content)
    }

    override fun close() {
        client.close()
    }

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class VersionMetadata(
        @SerialName("version_code") val versionCode: Int = 0,
        @SerialName("version_name") val versionName: String = "",
    )

    private data class ReleaseCandidate(
        val release: GitHubRelease,
        val metadata: VersionMetadata,
    )
}
