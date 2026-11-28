package jp.noppy.lumamusic.update

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun comparesNumericVersionsAndRejectsInvalidStableReleases() = runBlocking {
        val transport = Transport()
        assertEquals("1.10.0", transport.fresh("1.9.0").candidate?.versionName)
        assertEquals("1.10.0", transport.fresh("1.10.0").latestVersionName)
        assertNull(transport.fresh("1.10.0").candidate)
        assertNull(transport.fresh("2.0.0").candidate)
        expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0", 100) }
        for (tag in listOf("1.10.0", "v01.10.0", "v1.10.0-rc.1", "garbage")) {
            transport.release = releaseJson(tag = tag)
            expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0") }
        }
        for (release in listOf(releaseJson(prerelease = true), releaseJson(draft = true))) {
            transport.release = release
            expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0") }
        }
    }

    @Test fun usesDesignatedLatestAndRejectsAmbiguousAssets() = runBlocking {
        val transport = Transport()
        transport.apiHeaders = mapOf("Link" to "<https://api.github.com/other?page=2>; rel=\"next\"")
        assertEquals("1.10.0", transport.fresh("1.9.0").candidate?.versionName)
        assertEquals(listOf(latestUrl, manifestUrl), transport.requests.map { it.url.toString() })
        transport.release = releaseJson(assets = listOf(assetJson("update.json", manifestBytes), apkAsset, apkAsset))
        expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0") }
        transport.release = releaseJson(assets = listOf(assetJson("update.json", manifestBytes), apkAsset, assetJson("update.json", manifestBytes)))
        expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0") }
        Unit
    }

    @Test fun sendsConditionalHeaderAndHandlesMissingEtag() = runBlocking {
        val transport = Transport()
        assertNull(transport.fresh("1.9.0").etag)
        transport.apiHeaders = mapOf("ETag" to "W/\"current\"")
        assertEquals("W/\"current\"", transport.fresh("1.9.0").etag)
        assertNull(transport.requests.first().sentHeaders["If-None-Match"])
        transport.apiStatus = 304
        val result = transport.repository.check("1.9.0", 90, "W/\"current\"")
        assertSame(ReleaseCheck.NotModified, result)
        assertEquals("W/\"current\"", transport.requests.last().sentHeaders["If-None-Match"])
        expectFailure<ReleaseMetadataException> { transport.repository.check("1.9.0", 90, null) }
        expectFailure<ReleaseMetadataException> { transport.repository.check("1.9.0", 90, "invalid\r\nvalue") }
        assertNull(transport.requests.last().sentHeaders["If-None-Match"])
        assertTrue(transport.requests.all { it.disconnected && !it.instanceFollowRedirects })
        assertTrue(transport.requests.all { it.connectTimeout > 0 && it.readTimeout > 0 })
    }

    @Test fun exposesHttpRetryDelaysAndNetworkFailures() = runBlocking {
        val transport = Transport()
        transport.apiStatus = 429
        transport.apiHeaders = mapOf("Retry-After" to "30")
        val rateLimit = expectFailure<ReleaseHttpException> { transport.fresh("1.9.0") }
        assertEquals(429, rateLimit.status)
        assertEquals(30_000L, rateLimit.retryAfterMillis)
        transport.apiStatus = 403
        transport.apiHeaders = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "124")
        assertEquals(24_000L, expectFailure<ReleaseHttpException> { transport.fresh("1.9.0") }.retryAfterMillis)
        transport.apiStatus = 503
        transport.apiHeaders = mapOf("Retry-After" to "Thu, 01 Jan 1970 00:02:10 GMT")
        assertEquals(30_000L, expectFailure<ReleaseHttpException> { transport.fresh("1.9.0") }.retryAfterMillis)
        transport.apiHeaders = emptyMap()
        assertNull(expectFailure<ReleaseHttpException> { transport.fresh("1.9.0") }.retryAfterMillis)
        transport.apiFailure = UnknownHostException()
        expectFailure<UnknownHostException> { transport.fresh("1.9.0") }
        transport.apiFailure = SocketTimeoutException()
        expectFailure<SocketTimeoutException> { transport.fresh("1.9.0") }
        Unit
    }

    @Test fun validatesCdnRedirectsByteCountAndSha256() = runBlocking {
        val transport = Transport()
        val repository = transport.repository
        val candidate = transport.fresh("1.9.0").candidate!!
        val target = temporary.newFile()
        val progress = mutableListOf<Long>()
        repository.download(candidate, target, progress::add)
        assertEquals(apkBytes.toList(), target.readBytes().toList())
        assertEquals(apkBytes.size.toLong(), progress.last())
        assertEquals(cdnUrl, transport.requests.last().url.toString())
        assertNull(transport.requests.last().sentHeaders["If-None-Match"])

        expectFailure<ReleaseMetadataException> {
            repository.download(candidate.copy(url = apkUrl + "?overwrite"), target) {}
        }
        for (redirect in listOf("http://release-assets.githubusercontent.com/asset", "https://release-assets.githubusercontent.com.example/asset", "https://release-assets.githubusercontent.com:8443/asset")) {
            transport.redirect = redirect
            expectFailure<ReleaseMetadataException> { repository.download(candidate, target) {} }
            assertEquals(apkUrl, transport.requests.last().url.toString())
        }
        transport.redirect = cdnUrl
        for (bytes in listOf(byteArrayOf(1), apkBytes + 1, ByteArray(apkBytes.size))) {
            transport.downloadBytes = bytes
            expectFailure<ReleaseIntegrityException> { repository.download(candidate, target) {} }
        }
        transport.downloadBytes = apkBytes
        transport.downloadHeaders = mapOf("Content-Length" to "${apkBytes.size + 1}")
        expectFailure<ReleaseIntegrityException> { repository.download(candidate, target) {} }
        Unit
    }

    @Test fun cancellationDisconnectsAnActiveTransfer() = runBlocking {
        val candidate = Transport().fresh("1.9.0").candidate!!
        val reading = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val source = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(disconnected.await(8, TimeUnit.SECONDS))
                return -1
            }
        }
        val repository = ReleaseRepository(
            deviceSdk = 34,
            deviceAbis = setOf("arm64-v8a"),
            openConnection = { Connection(it, 200, source = source, onDisconnect = disconnected::countDown) },
        )
        val target = temporary.newFile()
        val transfer = launch { repository.download(candidate, target) {} }
        assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
        withTimeout(5_000) { transfer.cancelAndJoin() }
        assertTrue(transfer.isCancelled)
        assertEquals(0L, disconnected.count)
        assertEquals(0L, target.length())
    }

    @Test fun validatesManifestAndRejectsIncompatibleOrNonStandaloneAssets() = runBlocking {
        val transport = Transport()
        for (metadata in listOf(manifestJson(minSdk = 35), manifestJson(abis = "\"x86_64\""))) {
            transport.setManifest(metadata)
            expectFailure<ReleaseCompatibilityException> { transport.fresh("1.9.0") }
        }
        transport.setManifest(manifestJson(abis = "\"arm64-v8a\""))
        assertEquals(setOf("arm64-v8a"), transport.fresh("1.9.0").candidate?.abis)
        for (metadata in listOf(
            manifestJson(abis = "\"universal\", \"x86_64\""),
            manifestJson(versionName = "v1.10.0"),
            manifestJson(versionCode = "100.0"),
            manifestJson(versionCode = "\"100\""),
            manifestJson().replace("\"versionCode\":100", "\"versionCode\":99,\"versionCode\":100"),
            manifestJson().dropLast(1) + ",\"unexpected\":true}",
        )) {
            transport.setManifest(metadata)
            expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0") }
        }
        transport.setManifest(manifestJson())
        transport.release = releaseJson(assets = listOf(assetJson("update.json", transport.manifest), apkAsset.replace(apkDigest, "0".repeat(64))))
        expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0") }
        val otherAssets = listOf("Aurio.aab", "Aurio-arm64-v8a.apk", "Aurio-debug.apk").map { assetJson(it, apkBytes) }
        transport.release = releaseJson(assets = listOf(assetJson("update.json", transport.manifest)) + otherAssets)
        expectFailure<ReleaseMetadataException> { transport.fresh("1.9.0") }
        assertFalse(transport.requests.any { it.url.toString() == apkUrl })
    }

    private class Transport {
        var release = releaseJson()
        var manifest = manifestBytes
        var apiStatus = 200
        var apiHeaders = emptyMap<String, String>()
        var apiFailure: Exception? = null
        var redirect = cdnUrl
        var downloadBytes = apkBytes
        var downloadHeaders = emptyMap<String, String>()
        val requests = mutableListOf<Connection>()
        val repository = ReleaseRepository(
            deviceSdk = 34,
            deviceAbis = setOf("arm64-v8a"),
            nowMillis = { 100_000L },
            openConnection = { url ->
                when (url.toString()) {
                    latestUrl -> Connection(url, apiStatus, release.toByteArray(), apiHeaders, apiFailure)
                    manifestUrl -> Connection(url, 200, manifest)
                    apkUrl -> Connection(url, 302, headers = mapOf("Location" to redirect))
                    cdnUrl -> Connection(url, 200, downloadBytes, downloadHeaders)
                    else -> error("Unexpected request to $url")
                }.also(requests::add)
            },
        )

        fun setManifest(json: String) {
            manifest = json.toByteArray()
            release = releaseJson(assets = listOf(assetJson("update.json", manifest), apkAsset))
        }

        suspend fun fresh(current: String, code: Long = 90): ReleaseCheck.Fresh =
            repository.check(current, code, null) as ReleaseCheck.Fresh
    }

    private class Connection(
        url: URL,
        private val status: Int,
        private val bytes: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap(),
        private val failure: Exception? = null,
        private val source: InputStream = ByteArrayInputStream(bytes),
        private val onDisconnect: () -> Unit = {},
    ) : HttpURLConnection(url) {
        val sentHeaders = mutableMapOf<String, String>()
        var disconnected = false
        override fun setRequestProperty(key: String, value: String) { sentHeaders[key] = value }
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getResponseCode(): Int = failure?.let { throw it } ?: status
        override fun getInputStream(): InputStream = source
        override fun connect() = Unit
        override fun disconnect() {
            disconnected = true
            onDisconnect()
        }
        override fun usingProxy(): Boolean = false
    }

    private suspend inline fun <reified T : Exception> expectFailure(action: () -> Unit): T {
        try {
            action()
        } catch (failure: Exception) {
            if (failure is T) return failure
            throw failure
        }
        error("Expected ${T::class.simpleName}")
    }

    private companion object {
        const val latestUrl = "https://api.github.com/repos/noppy3325-droid/Aurio/releases/latest"
        const val assetRoot = "https://github.com/noppy3325-droid/Aurio/releases/download/v1.10.0"
        const val apkName = "Aurio-1.10.0.apk"
        const val apkUrl = "$assetRoot/$apkName"
        const val manifestUrl = "$assetRoot/update.json"
        const val cdnUrl = "https://release-assets.githubusercontent.com/github-production-release-asset-2e65be/example?signature=opaque"
        val apkBytes = byteArrayOf(1, 2, 3, 4, 5)
        val apkDigest = sha256(apkBytes)
        val apkAsset = assetJson(apkName, apkBytes)
        val manifestBytes = manifestJson().toByteArray()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun assetJson(name: String, bytes: ByteArray): String =
            """{"name":"$name","size":${bytes.size},"state":"uploaded","digest":"sha256:${sha256(bytes)}","browser_download_url":"$assetRoot/$name"}"""

        fun releaseJson(
            tag: String = "v1.10.0",
            draft: Boolean = false,
            prerelease: Boolean = false,
            assets: List<String> = listOf(assetJson("update.json", manifestBytes), apkAsset),
        ): String = """{"id":16,"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":"Notes","assets":[${assets.joinToString()}]}"""

        fun manifestJson(
            versionName: String = "1.10.0",
            versionCode: String = "100",
            minSdk: Int = 29,
            abis: String = "\"universal\"",
        ): String = """{"versionName":"$versionName","versionCode":$versionCode,"minSdk":$minSdk,"abis":[$abis],"assetName":"$apkName","size":${apkBytes.size},"sha256":"$apkDigest"}"""
    }
}
