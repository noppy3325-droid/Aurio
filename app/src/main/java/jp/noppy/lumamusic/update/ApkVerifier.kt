package jp.noppy.lumamusic.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object ApkVerifier {
    enum class Reason(val description: String) {
        UNREADABLE("The downloaded APK could not be read"),
        SIZE("The downloaded APK size does not match the release"),
        HASH("The downloaded APK SHA-256 does not match the release"),
        ARCHIVE("The download is not a readable standalone APK"),
        PACKAGE("The download has a different application ID"),
        VERSION("The APK version does not match the release or is not newer"),
        SDK("The APK minimum Android version does not match the release or device"),
        ABI("The APK native libraries do not match the release or device"),
        DEBUG("The download is a debug APK"),
        SPLIT("The download requires split APKs"),
        SIGNATURE("The APK signing certificates differ from the installed app"),
    }

    class Failure(val reason: Reason, cause: Throwable? = null) : IOException(reason.description, cause)

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, candidate: UpdateCandidate) {
        val abis = verifyFile(file, candidate.size, candidate.sha256)
        val declaredAbis = candidate.abis.takeUnless { it == setOf("universal") }.orEmpty()
        if (abis != declaredAbis || (abis.isNotEmpty() && Build.SUPPORTED_ABIS.none(abis::contains))) {
            throw Failure(Reason.ABI)
        }

        val packageManager = context.packageManager
        val archive = readArchive {
            // Android 10 requires GET_SIGNATURES to collect certs for an archive.
            // We still read only signingInfo.apkContentsSigners to get the current certs.
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA,
            ) ?: throw Failure(Reason.ARCHIVE)
        }
        if (archive.packageName != context.packageName) throw Failure(Reason.PACKAGE)
        val info = archive.applicationInfo ?: throw Failure(Reason.ARCHIVE)
        if ((info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) throw Failure(Reason.DEBUG)
        if (info.minSdkVersion != candidate.minSdk || info.minSdkVersion > Build.VERSION.SDK_INT) {
            throw Failure(Reason.SDK)
        }
        val installed = try {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (failure: PackageManager.NameNotFoundException) {
            throw Failure(Reason.SIGNATURE, failure)
        } catch (failure: RuntimeException) {
            throw Failure(Reason.SIGNATURE, failure)
        }
        if (archive.versionName != candidate.versionName || archive.longVersionCode != candidate.versionCode ||
            archive.longVersionCode <= installed.longVersionCode
        ) {
            throw Failure(Reason.VERSION)
        }

        // Compare current signers only. A certificate from the signing history is not sufficient.
        val installedSigners = installed.currentSigners()
        if (installedSigners.isEmpty() || installedSigners != archive.currentSigners()) {
            throw Failure(Reason.SIGNATURE)
        }
        if (!archive.splitNames.isNullOrEmpty() || !info.splitNames.isNullOrEmpty()) throw Failure(Reason.SPLIT)
        readArchive { verifyStandaloneManifest(packageManager, info, file) }
    }

    internal fun verifyFile(file: File, size: Long, sha256: String): Set<String> {
        if (!file.isFile) throw Failure(Reason.UNREADABLE)
        if (size <= 0 || file.length() != size) throw Failure(Reason.SIZE)
        try {
            file.inputStream().use { verifyBytes(it, size, sha256) }
        } catch (failure: Failure) {
            throw failure
        } catch (failure: IOException) {
            throw Failure(Reason.UNREADABLE, failure)
        }

        return readArchive {
            ZipFile(file).use { zip ->
                if ((zip.getEntry("AndroidManifest.xml")?.size ?: 0L) <= 0 ||
                    (zip.getEntry("classes.dex")?.size ?: 0L) <= 0
                ) {
                    throw Failure(Reason.ARCHIVE)
                }
                val names = mutableSetOf<String>()
                val abis = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val segments = entry.name.removeSuffix("/").split('/')
                    if (!names.add(entry.name) || entry.name.contains('\\') ||
                        segments.any { it.isEmpty() || it == "." || it == ".." }
                    ) {
                        throw Failure(Reason.ARCHIVE)
                    }
                    if (!entry.isDirectory && segments.size == 3 && segments[0] == "lib" &&
                        segments[2].endsWith(".so")
                    ) {
                        val elfHeader = ByteArray(ELF_MAGIC.size)
                        DataInputStream(zip.getInputStream(entry)).use { it.readFully(elfHeader) }
                        if (!elfHeader.contentEquals(ELF_MAGIC)) throw Failure(Reason.ARCHIVE)
                        abis.add(segments[1])
                    }
                }
                abis
            }
        }
    }

    // Also used while copying the bytes into the system installer session.
    internal fun verifyBytes(
        input: InputStream,
        size: Long,
        sha256: String,
        output: OutputStream? = null,
        checkActive: () -> Unit = {},
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            read += count
            if (read > size) throw Failure(Reason.SIZE)
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        if (read != size) throw Failure(Reason.SIZE)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256, ignoreCase = true)) throw Failure(Reason.HASH)
    }

    private fun PackageInfo.currentSigners() = signingInfo?.apkContentsSigners.orEmpty().toSet()

    private fun verifyStandaloneManifest(packageManager: PackageManager, app: ApplicationInfo, file: File) {
        val metaData = app.metaData
        if (metaData != null && metaData.containsKey(PLAY_SPLITS_REQUIRED) &&
            metaData.getBoolean(PLAY_SPLITS_REQUIRED, true)
        ) {
            throw Failure(Reason.SPLIT)
        }
        // Read the manifest from the archive itself because PackageInfo only reports installed splits.
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        val resources = packageManager.getResourcesForApplication(app)
        resources.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            while (manifest.next() != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType != XmlPullParser.START_TAG) continue
                if (manifest.depth == 1) {
                    if (manifest.name != "manifest") throw Failure(Reason.ARCHIVE)
                    if (!manifest.getAttributeValue(null, "split").isNullOrEmpty() ||
                        manifest.getAttributeValue(null, "configForSplit") != null ||
                        !manifest.getAttributeValue(ANDROID_XML, "requiredSplitTypes").isNullOrEmpty() ||
                        manifest.getAttributeBooleanValue(ANDROID_XML, "isFeatureSplit", false) ||
                        manifest.getAttributeBooleanValue(ANDROID_XML, "isSplitRequired", false)
                    ) {
                        throw Failure(Reason.SPLIT)
                    }
                } else if (manifest.depth == 2 && manifest.name == "uses-split") {
                    throw Failure(Reason.SPLIT)
                }
            }
        }
    }

    private inline fun <T> readArchive(read: () -> T): T = try {
        read()
    } catch (failure: Failure) {
        throw failure
    } catch (failure: IOException) {
        throw Failure(Reason.ARCHIVE, failure)
    } catch (failure: PackageManager.NameNotFoundException) {
        throw Failure(Reason.ARCHIVE, failure)
    } catch (failure: XmlPullParserException) {
        throw Failure(Reason.ARCHIVE, failure)
    } catch (failure: RuntimeException) {
        throw Failure(Reason.ARCHIVE, failure)
    }

    private const val ANDROID_XML = "http://schemas.android.com/apk/res/android"
    private const val PLAY_SPLITS_REQUIRED = "com.android.vending.splits.required"
    private val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
}
