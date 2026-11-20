package cc.bridgit.app.capability

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeechPhraseCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun preloadedPhraseSurvivesQueueCleanupAndIsBoundToActualVoiceAndParameters() {
        val root = temp.newFolder("cache")
        val original = temp.newFile("generated.wav").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        val cache = SpeechPhraseCache(root)
        cache.remember("engine-v1:SLT:en-US:1:1", "Let me check", original)
        original.delete() // Speaker queue deletes its owned audio after playback.
        val out = File(temp.root,"play.wav")
        assertTrue(cache.restore("engine-v1:SLT:en-US:1:1", "Let me check", out))
        assertArrayEquals(byteArrayOf(1,2,3,4),out.readBytes())
        out.delete()
        assertTrue(SpeechPhraseCache(root).restore("engine-v1:SLT:en-US:1:1", "Let me check", out))
        assertFalse(cache.restore("engine-v2:SLT:en-US:1:1", "Let me check", out))
        assertFalse(cache.restore("engine-v1:Other:en-US:1:1", "Let me check", out))
        assertFalse(cache.restore("engine-v1:SLT:en-US:2:1", "Let me check", out))
        assertFalse(cache.restore("engine-v1:SLT:en-US:1:1", "Different text", out))
        assertFalse(root.listFiles()!!.any { it.name.contains("Let me") })
    }
    @Test fun storageIsBoundedAndMissingOrOversizedFilesDoNotBreakSpeech() {
        val root = temp.newFolder("bounded")
        val cache = SpeechPhraseCache(root,maxEntries=2,maxBytes=6)
        val source = temp.newFile("audio.wav").apply { writeBytes(byteArrayOf(1,2,3)) }
        repeat(5) { cache.remember("voice","phrase $it",source) }
        assertTrue(root.listFiles()!!.size <= 2)
        assertTrue(root.listFiles()!!.sumOf { it.length() } <= 6)
        source.writeBytes(ByteArray(7))
        cache.remember("voice","too big",source)
        assertFalse(cache.restore("voice","too big",File(temp.root,"out.wav")))
        assertFalse(cache.restore("voice","missing",File(temp.root,"out.wav")))
    }
}
