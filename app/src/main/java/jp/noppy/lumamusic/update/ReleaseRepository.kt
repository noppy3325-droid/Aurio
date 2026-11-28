package jp.noppy.lumamusic.update

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class ReleaseHttpException(val status: Int, val retryAfterMillis: Long?) : IOException("Release request returned HTTP $status")

internal class ReleaseRepository(
    private val deviceSdk: Int = Build.VERSION.SDK_INT,
    private val deviceAbis: Set<String> = Build.SUPPORTED_ABIS.toSet(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun check(currentName: String, currentCode: Long, etag: String?): ReleaseCheck = withContext(Dispatchers.IO) {
        val conditional = etag?.takeIf(::validEtag)
        val response = request(latestUrl, apiAccept, conditional) { connection, status, ensureActive ->
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (conditional == null) throw ReleaseMetadataException("GitHub returned 304 without a conditional request")
                return@request null
            }
            requireSuccess(connection, status)
            val bytes = readMetadata(connection, maxReleaseBytes, ensureActive)
            bytes to connection.getHeaderField("ETag")?.takeIf(::validEtag)
        } ?: return@withContext ReleaseCheck.NotModified

        // The release publisher selects the stable version by maintaining GitHub's latest designation.
        val (bytes, receivedEtag) = response
        val latest = ReleaseContract.latest(bytes, currentName)
        if (!latest.isNewer) return@withContext ReleaseCheck.Fresh(null, receivedEtag, latest.versionName)
        val manifest = ReleaseContract.asset(latest, ReleaseContract.manifestName, ReleaseContract.maxManifestBytes.toLong())
        val manifestBytes = requestAsset(manifest.url) { connection, ensureActive ->
            readMetadata(connection, manifest.size.toInt(), ensureActive)
        }
        val candidate = ReleaseContract.candidate(latest, manifest, manifestBytes, currentCode)
        requireCompatible(candidate)
        ReleaseCheck.Fresh(candidate, receivedEtag)
    }

    // The caller supplies an app-private temporary file and owns cleanup and promotion.
    suspend fun download(candidate: UpdateCandidate, target: File, onProgress: (Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        ReleaseContract.validateDownload(candidate)
        requireCompatible(candidate)
        requestAsset(candidate.url) { connection, ensureActive ->
            val declaredLength = contentLength(connection)
            if (declaredLength != null && declaredLength != candidate.size) {
                throw ReleaseIntegrityException("APK response length differs from release metadata")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var received = 0L
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        ensureActive()
                        if (read == -1) break
                        received += read
                        if (received > candidate.size) throw ReleaseIntegrityException("APK exceeds its declared size")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        onProgress(received)
                    }
                }
            }
            if (received != candidate.size) throw ReleaseIntegrityException("APK download is incomplete")
            if (!digest.digest().toReleaseHex().equals(candidate.sha256, ignoreCase = true)) {
                throw ReleaseIntegrityException("APK SHA-256 differs from release metadata")
            }
        }
    }

    private fun requireCompatible(candidate: UpdateCandidate) {
        if (candidate.minSdk > deviceSdk) throw ReleaseCompatibilityException("Update requires a newer Android version")
        if (candidate.abis != setOf("universal") && candidate.abis.none(deviceAbis::contains)) {
            throw ReleaseCompatibilityException("Update has no ABI supported by this device")
        }
    }

    private sealed interface AssetResponse<out T> {
        data class Redirect(val url: String) : AssetResponse<Nothing>
        data class Complete<T>(val value: T) : AssetResponse<T>
    }

    private suspend fun <T> requestAsset(initialUrl: String, read: (HttpURLConnection, () -> Unit) -> T): T {
        var url = initialUrl
        repeat(maxRedirects + 1) { redirectCount ->
            val response = request(url, "application/octet-stream", null) { connection, status, ensureActive ->
                if (status in redirectStatusCodes) {
                    if (redirectCount >= maxRedirects) throw ReleaseMetadataException("Too many release asset redirects")
                    AssetResponse.Redirect(cdnRedirect(connection.getHeaderField("Location")))
                } else {
                    requireSuccess(connection, status)
                    AssetResponse.Complete(read(connection, ensureActive))
                }
            }
            when (response) {
                is AssetResponse.Redirect -> url = response.url
                is AssetResponse.Complete -> return response.value
            }
        }
        error("Release redirect loop exhausted")
    }

    private suspend fun <T> request(
        url: String,
        accept: String,
        etag: String?,
        read: (HttpURLConnection, Int, () -> Unit) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        try {
            val connection = openConnection(URL(url))
            try {
                continuation.invokeOnCancellation { connection.disconnect() }
                val ensureActive = {
                    if (!continuation.isActive) throw CancellationException("Release request cancelled")
                }
                ensureActive()
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Aurio-Android")
                if (accept == apiAccept) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                continuation.resume(read(connection, connection.responseCode, ensureActive))
            } finally {
                connection.disconnect()
            }
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

    private fun readMetadata(connection: HttpURLConnection, maxBytes: Int, ensureActive: () -> Unit): ByteArray {
        val declaredLength = contentLength(connection)
        if (declaredLength != null && declaredLength > maxBytes) {
            throw ReleaseMetadataException("Release metadata exceeds the size limit")
        }
        return connection.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val output = ByteArrayOutputStream()
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                if (output.size() + read > maxBytes) throw ReleaseMetadataException("Release metadata exceeds the size limit")
                output.write(buffer, 0, read)
            }
            if (declaredLength != null && output.size().toLong() != declaredLength) {
                throw ReleaseMetadataException("Release metadata response is incomplete")
            }
            ensureActive()
            output.toByteArray()
        }
    }

    private fun contentLength(connection: HttpURLConnection): Long? = connection.getHeaderField("Content-Length")?.let {
        it.toLongOrNull()?.takeIf { length -> length >= 0 } ?: throw ReleaseMetadataException("Invalid Content-Length header")
    }

    private fun requireSuccess(connection: HttpURLConnection, status: Int) {
        if (status == HttpURLConnection.HTTP_OK) return
        val now = nowMillis()
        val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.let { value ->
            secondsAsMillis(value) ?: try {
                (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0L)
            } catch (_: DateTimeParseException) {
                null
            }
        }
        val resetDelay = connection.getHeaderField("X-RateLimit-Remaining")?.takeIf { it == "0" }?.let {
            connection.getHeaderField("X-RateLimit-Reset")?.let(::secondsAsMillis)?.let { (it - now).coerceAtLeast(0) }
        }
        throw ReleaseHttpException(status, maxOf(retryAfter ?: 0L, resetDelay ?: 0L).takeIf { retryAfter != null || resetDelay != null })
    }

    private fun secondsAsMillis(value: String): Long? = value.toLongOrNull()?.takeIf { it >= 0 }
        ?.coerceAtMost(Long.MAX_VALUE / 1_000L)?.times(1_000L)

    private fun validEtag(value: String): Boolean = value.length <= 512 && etagPattern.matches(value)

    private fun cdnRedirect(location: String?): String {
        val uri = try {
            URI(location ?: throw ReleaseMetadataException("Missing release asset redirect URL"))
        } catch (failure: URISyntaxException) {
            throw ReleaseMetadataException("Invalid release asset redirect URL", failure)
        }
        if (uri.scheme != "https" || uri.host !in cdnHosts || uri.port !in setOf(-1, 443) ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.rawPath.isNullOrEmpty()
        ) throw ReleaseMetadataException("Release asset redirect is outside the approved GitHub CDN")
        return uri.toASCIIString()
    }

    companion object {
        const val releasesUrl = "https://github.com/${ReleaseContract.repository}/releases"
        private const val latestUrl = "https://api.github.com/repos/${ReleaseContract.repository}/releases/latest"
        private const val apiAccept = "application/vnd.github+json"
        private const val maxReleaseBytes = 1024 * 1024
        private const val maxRedirects = 3
        private val redirectStatusCodes = setOf(301, 302, 303, 307, 308)
        private val cdnHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val etagPattern = Regex("(?:W/)?\"[!#-~]*\"")
    }
}
