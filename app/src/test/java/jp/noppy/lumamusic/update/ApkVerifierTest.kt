package jp.noppy.lumamusic.update

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkVerifierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reportsTheActualNativeAbisInTheArchive() {
        val file = zip("AndroidManifest.xml", "classes.dex", "lib/arm64-v8a/libtest.so", "lib/x86_64/libtest.so")

        assertEquals(setOf("arm64-v8a", "x86_64"), ApkVerifier.verifyFile(file, file.length(), sha256(file)))
    }

    @Test fun acceptsAnArchiveWithoutNativeLibraries() {
        val file = zip("AndroidManifest.xml", "classes.dex", "assets/arm64-v8a/readme.txt")

        assertEquals(emptySet<String>(), ApkVerifier.verifyFile(file, file.length(), sha256(file)))
    }

    @Test fun rejectsAnOnDiskSizeMismatch() {
        val file = zip("AndroidManifest.xml", "classes.dex")

        assertFailure(ApkVerifier.Reason.SIZE, file, file.length() + 1, sha256(file))
    }

    @Test fun rejectsAnOnDiskDigestMismatch() {
        val expected = zip("AndroidManifest.xml", "classes.dex")
        val actual = zip("AndroidManifest.xml", "classeS.dex")
        assertEquals(expected.length(), actual.length())

        assertFailure(ApkVerifier.Reason.HASH, actual, expected.length(), sha256(expected))
    }

    @Test fun rejectsAnAppBundleEvenWithAMatchingDigest() {
        val file = zip("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/dex/classes.dex")

        assertFailure(ApkVerifier.Reason.ARCHIVE, file, file.length(), sha256(file))
    }

    @Test fun rejectsATraversalEntryEvenWithAMatchingDigest() {
        val file = zip("AndroidManifest.xml", "classes.dex", "lib/../libtest.so")

        assertFailure(ApkVerifier.Reason.ARCHIVE, file, file.length(), sha256(file))
    }

    @Test fun rejectsANativeLibraryWithoutAnElfHeader() {
        val file = zip("AndroidManifest.xml", "classes.dex", "lib/x86/libtest.so", useElfMagic = false)

        assertFailure(ApkVerifier.Reason.ARCHIVE, file, file.length(), sha256(file))
    }

    private fun assertFailure(reason: ApkVerifier.Reason, file: File, size: Long, hash: String) {
        val failure = assertThrows(ApkVerifier.Failure::class.java) { ApkVerifier.verifyFile(file, size, hash) }
        assertEquals(reason, failure.reason)
    }

    private fun zip(vararg names: String, useElfMagic: Boolean = true): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                val content = if (useElfMagic && name.endsWith(".so")) {
                    byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
                } else byteArrayOf(1, 2, 3)
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
