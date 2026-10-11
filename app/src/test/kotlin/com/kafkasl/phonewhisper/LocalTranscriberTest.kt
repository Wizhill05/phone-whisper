package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalTranscriberTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("transcriber-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    @Test
    fun `detects Parakeet CTC model config`() {
        withTempDir { dir ->
            File(dir, "model.int8.onnx").writeText("fake-model")
            File(dir, "tokens.txt").writeText("fake-tokens")

            val config = LocalTranscriber.detectModelConfig(dir)
            assertNotNull(config)
            assertEquals("fake-tokens", File(config!!.modelConfig.tokens).readText())
            assertEquals("fake-model", File(config.modelConfig.nemo.model).readText())
        }
    }

    @Test
    fun `detects Whisper model config with prefixed filenames`() {
        withTempDir { dir ->
            File(dir, "base.en-encoder.int8.onnx").writeText("fake-encoder")
            File(dir, "base.en-decoder.int8.onnx").writeText("fake-decoder")
            File(dir, "base.en-tokens.txt").writeText("fake-tokens")

            val config = LocalTranscriber.detectModelConfig(dir)
            assertNotNull(config)
            assertEquals("whisper", config!!.modelConfig.modelType)
            assertEquals("fake-tokens", File(config.modelConfig.tokens).readText())
            assertEquals("fake-encoder", File(config.modelConfig.whisper.encoder).readText())
            assertEquals("fake-decoder", File(config.modelConfig.whisper.decoder).readText())
        }
    }

    @Test
    fun `detects Parakeet Transducer TDT model config`() {
        withTempDir { dir ->
            File(dir, "encoder.int8.onnx").writeText("fake-encoder")
            File(dir, "decoder.int8.onnx").writeText("fake-decoder")
            File(dir, "joiner.int8.onnx").writeText("fake-joiner")
            File(dir, "tokens.txt").writeText("fake-tokens")

            val config = LocalTranscriber.detectModelConfig(dir)
            assertNotNull(config)
            assertEquals("nemo_transducer", config!!.modelConfig.modelType)
            assertEquals("fake-tokens", File(config.modelConfig.tokens).readText())
            assertEquals("fake-encoder", File(config.modelConfig.transducer.encoder).readText())
            assertEquals("fake-decoder", File(config.modelConfig.transducer.decoder).readText())
            assertEquals("fake-joiner", File(config.modelConfig.transducer.joiner).readText())
        }
    }

    @Test
    fun `detects Moonshine model config`() {
        withTempDir { dir ->
            File(dir, "preprocess.onnx").writeText("fake-preprocess")
            File(dir, "encode.int8.onnx").writeText("fake-encode")
            File(dir, "uncached_decode.int8.onnx").writeText("fake-uncached")
            File(dir, "cached_decode.int8.onnx").writeText("fake-cached")
            File(dir, "tokens.txt").writeText("fake-tokens")

            val config = LocalTranscriber.detectModelConfig(dir)
            assertNotNull(config)
            assertEquals("fake-tokens", File(config!!.modelConfig.tokens).readText())
            assertEquals("fake-preprocess", File(config.modelConfig.moonshine.preprocessor).readText())
            assertEquals("fake-encode", File(config.modelConfig.moonshine.encoder).readText())
            assertEquals("fake-uncached", File(config.modelConfig.moonshine.uncachedDecoder).readText())
            assertEquals("fake-cached", File(config.modelConfig.moonshine.cachedDecoder).readText())
        }
    }

    @Test
    fun `returns null when tokens or model files are missing`() {
        withTempDir { dir ->
            // Empty dir
            assertNull(LocalTranscriber.detectModelConfig(dir))

            // Only tokens, no models
            File(dir, "tokens.txt").writeText("tokens")
            assertNull(LocalTranscriber.detectModelConfig(dir))
        }
    }
}
