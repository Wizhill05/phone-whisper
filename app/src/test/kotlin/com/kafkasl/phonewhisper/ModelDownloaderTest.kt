package com.kafkasl.phonewhisper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ModelDownloaderTest {

    @Test fun `extracts tar bz2 with nested files`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            val outDir = File(tmp, "out")

            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello\nworld",
                "mymodel/encoder.onnx" to "fake-onnx-data",
            ))

            ModelDownloader.extractTarBz2(archive, outDir)

            assertTrue(File(outDir, "mymodel").isDirectory)
            assertEquals("hello\nworld", File(outDir, "mymodel/tokens.txt").readText())
            assertEquals("fake-onnx-data", File(outDir, "mymodel/encoder.onnx").readText())
        }
    }

    @Test fun `rejects path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `catalog has expected structure`() {
        assertEquals(4, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.archive.startsWith("sherpa-onnx-") })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
    }

    @Test fun `findTokensFile finds standard and model-prefixed tokens`() {
        withTempDir { tmp ->
            val dir1 = File(tmp, "parakeet").apply { mkdirs() }
            File(dir1, "tokens.txt").writeText("tokens")
            assertEquals("tokens.txt", ModelDownloader.findTokensFile(dir1)?.name)

            val dir2 = File(tmp, "whisper").apply { mkdirs() }
            File(dir2, "base.en-tokens.txt").writeText("whisper tokens")
            assertEquals("base.en-tokens.txt", ModelDownloader.findTokensFile(dir2)?.name)

            val dir3 = File(tmp, "empty").apply { mkdirs() }
            assertNull(ModelDownloader.findTokensFile(dir3))
        }
    }

    @Test fun `isInstalledDir requires existing directory and tokens file`() {
        withTempDir { tmp ->
            val missing = File(tmp, "nonexistent")
            assertFalse(ModelDownloader.isInstalledDir(missing))

            val incomplete = File(tmp, "incomplete").apply { mkdirs() }
            File(incomplete, "model.onnx").writeText("data")
            assertFalse(ModelDownloader.isInstalledDir(incomplete))

            File(incomplete, "tokens.txt").writeText("tokens")
            assertTrue(ModelDownloader.isInstalledDir(incomplete))
        }
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun writeTarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
}
