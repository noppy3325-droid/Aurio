package jp.noppy.lumamusic.update

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import jp.noppy.lumamusic.BuildConfig

internal data class UpdateCandidate(
    val releaseId: Long,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val abis: Set<String>,
    val assetName: String,
    val size: Long,
    val sha256: String,
    val url: String,
    val notes: String,
)

internal sealed interface ReleaseCheck {
    data class Fresh(
        val candidate: UpdateCandidate?,
        val etag: String?,
        val latestVersionName: String? = candidate?.versionName,
    ) : ReleaseCheck

    data object NotModified : ReleaseCheck
}

internal class ReleaseMetadataException(message: String, cause: Throwable? = null) : IOException(message, cause)
internal class ReleaseIntegrityException(message: String) : IOException(message)
internal class ReleaseCompatibilityException(message: String) : IOException(message)

internal object ReleaseContract {
    const val repository = BuildConfig.UPDATE_REPOSITORY
    const val manifestName = "update.json"
    const val maxManifestBytes = 64 * 1024
    const val maxApkBytes = 2L * 1024 * 1024 * 1024 - 1

    private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    private val abiPattern = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    private val sha256Pattern = Regex("[a-fA-F0-9]{64}")
    private val manifestFields = setOf("versionName", "versionCode", "minSdk", "abis", "assetName", "size", "sha256")

    data class Latest(val releaseId: Long, val versionName: String, val isNewer: Boolean, val payload: JsonObject)
    data class Asset(val name: String, val size: Long, val url: String, val sha256: String?)

    fun latest(bytes: ByteArray, currentName: String): Latest {
        val payload = parseJson(bytes)
        validate(!payload.boolean("draft") && !payload.boolean("prerelease"), "GitHub latest is not a stable release")
        val tag = payload.string("tag_name")
        validate(tag.startsWith('v'), "GitHub latest tag is invalid")
        val releaseId = payload.integer("id")
        validate(releaseId > 0, "Invalid GitHub release ID")
        val versionName = tag.substring(1)
        return Latest(releaseId, versionName, parseVersion(versionName) > parseVersion(currentName), payload)
    }

    fun asset(latest: Latest, assetName: String, maxSize: Long): Asset {
        val matches = latest.payload.array("assets").map { it.objectValue("asset") }
            .filter { it.string("name") == assetName }
        val asset = matches.singleOrNull() ?: throw ReleaseMetadataException("Missing or ambiguous $assetName asset")
        val url = asset.string("browser_download_url")
        val size = asset.integer("size")
        validate(url == canonicalAssetUrl(latest.versionName, assetName), "Invalid asset URL")
        validate(asset.string("state") == "uploaded", "Asset upload is incomplete")
        validate(size in 1..maxSize, "Invalid asset size")
        val digest = asset.optionalString("digest")?.let { digest ->
            validate(digest.startsWith("sha256:"), "Invalid GitHub asset digest")
            normalizeSha256(digest.removePrefix("sha256:"))
        }
        return Asset(assetName, size, url, digest)
    }

    fun candidate(latest: Latest, manifest: Asset, bytes: ByteArray, currentCode: Long): UpdateCandidate {
        validate(bytes.size.toLong() == manifest.size, "Update manifest size differs from GitHub asset size")
        validate(manifest.sha256 == null || bytes.sha256() == manifest.sha256, "Update manifest digest differs from GitHub asset digest")
        val metadata = parseJson(bytes)
        validate(metadata.keySet() == manifestFields, "Invalid update manifest fields")
        validate(metadata.string("versionName") == latest.versionName, "Update version differs from GitHub tag")
        val versionCode = metadata.integer("versionCode")
        validate(versionCode > currentCode && versionCode > 0, "Update version code must exceed the installed version code")
        val minSdk = metadata.integer("minSdk")
        validate(minSdk in 1..Int.MAX_VALUE.toLong(), "Invalid minimum SDK")
        val abis = metadata.array("abis").map { it.stringValue("abi") }
        validate(abis.isNotEmpty() && abis.all(abiPattern::matches), "Invalid ABI list")
        val abiSet = abis.toSet()
        validate(abiSet.size == abis.size && ("universal" !in abiSet || abiSet.size == 1), "Ambiguous ABI list")
        val name = metadata.string("assetName")
        validate(name == apkName(latest.versionName), "Invalid standalone APK name")
        val apk = asset(latest, name, maxApkBytes)
        validate(metadata.integer("size") == apk.size, "APK size differs from GitHub asset size")
        val sha256 = normalizeSha256(metadata.string("sha256"))
        validate(apk.sha256 == null || apk.sha256 == sha256, "APK digest differs from GitHub asset digest")
        return UpdateCandidate(
            latest.releaseId, latest.versionName, versionCode, minSdk.toInt(), abiSet, apk.name, apk.size, sha256,
            apk.url, latest.payload.optionalString("body").orEmpty().take(32_000),
        )
    }

    fun validateDownload(candidate: UpdateCandidate) {
        parseVersion(candidate.versionName)
        validate(candidate.assetName == apkName(candidate.versionName), "Invalid standalone APK name")
        validate(candidate.url == canonicalAssetUrl(candidate.versionName, candidate.assetName), "Invalid APK URL")
        validate(candidate.size in 1..maxApkBytes, "Invalid APK size")
        normalizeSha256(candidate.sha256)
    }

    private fun canonicalAssetUrl(version: String, name: String): String =
        "https://github.com/$repository/releases/download/v$version/$name"

    private fun apkName(version: String): String = "Aurio-$version.apk"

    private fun normalizeSha256(value: String): String {
        validate(sha256Pattern.matches(value), "Invalid SHA-256 digest")
        return value.lowercase()
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    }

    private fun parseVersion(name: String): Version {
        val parts = versionPattern.matchEntire(name)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
        if (parts?.size != 3) throw ReleaseMetadataException("Invalid stable version")
        return Version(parts[0], parts[1], parts[2])
    }

    private fun parseJson(bytes: ByteArray): JsonObject {
        try {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            return JsonReader(text.reader()).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = readJson(reader).objectValue("document")
                validate(reader.peek() == JsonToken.END_DOCUMENT, "Unexpected trailing JSON data")
                value
            }
        } catch (failure: ReleaseMetadataException) {
            throw failure
        } catch (failure: IOException) {
            throw ReleaseMetadataException("Malformed release JSON", failure)
        } catch (failure: RuntimeException) {
            throw ReleaseMetadataException("Malformed release JSON", failure)
        }
    }

    // Keep JSON types intact and reject duplicate keys before a value can overwrite one.
    private fun readJson(reader: JsonReader, depth: Int = 0): JsonElement {
        validate(depth <= 32, "Release JSON is too deeply nested")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    validate(!has(key), "Duplicate release JSON field")
                    add(key, readJson(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(readJson(reader, depth + 1))
                reader.endArray()
            }
            else -> JsonParser.parseReader(reader)
        }
    }

    private fun validate(condition: Boolean, message: String) {
        if (!condition) throw ReleaseMetadataException(message)
    }

    private fun JsonElement?.objectValue(name: String): JsonObject = this as? JsonObject
        ?: throw ReleaseMetadataException("Invalid object field $name")

    private fun JsonElement?.stringValue(name: String): String = this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw ReleaseMetadataException("Invalid string field $name")

    private fun JsonObject.string(name: String): String = get(name).stringValue(name)

    private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.stringValue(name)

    private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray
        ?: throw ReleaseMetadataException("Invalid array field $name")

    private fun JsonObject.boolean(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: throw ReleaseMetadataException("Invalid boolean field $name")

    private fun JsonObject.integer(name: String): Long = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        ?: throw ReleaseMetadataException("Invalid integer field $name")
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toReleaseHex()

internal fun ByteArray.toReleaseHex(): String = joinToString("") { "%02x".format(it) }
