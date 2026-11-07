package cc.bridgit.app.listen

import cc.bridgit.app.api.Demos
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WakeRulesTest {
    private val agents = setOf("a1", "a2")
    private fun problem(existing: List<WakeWord>, phrase: String, agent: String = "a1", known: ((String) -> Boolean)? = null) =
        WakeRules.problem(existing, WakeWord(phrase, agent), { it in agents }, known)

    @Test fun normalizes() {
        assertEquals("hey bridget", WakeRules.normalize("  Hey, Bridget!  "))
        assertEquals("ok grok's turn", WakeRules.normalize("OK   Grok's turn"))
    }

    @Test fun validatesPhrases() {
        assertNull(problem(emptyList(), "Hey Bridget"))
        assertNotNull(problem(emptyList(), "  "))
        assertTrue(problem(emptyList(), "bridget")!!.contains("two words"))
        assertNotNull(problem(emptyList(), "one two three four five"))
        val one = listOf(WakeWord("hey bridget", "a1"))
        assertTrue(problem(one, "HEY BRIDGET")!!.contains("already"))
        assertTrue(problem(one, "hey bridget note")!!.contains("overlaps"))
        assertNull(problem(one, "hey bridge"))
        assertEquals("Pick an agent.", problem(emptyList(), "hey bridget", agent = "gone"))
        val ten = (1..10).map { WakeWord("word$it please", "a1") }
        assertTrue(problem(ten, "hey bridget")!!.contains("up to 10"))
        assertTrue(problem(emptyList(), "hey bridgit") { it != "bridgit" }!!.contains("\"bridgit\""))
        assertNull(problem(emptyList(), "hey bridget") { true })
    }

    @Test fun grammarHasOnlyPhrasesAndUnk() {
        val g = JSONArray(WakeRules.grammar(listOf(WakeWord("Hey Bridget", "a1"), WakeWord("ok grok", "a2"))))
        assertEquals(listOf("hey bridget", "ok grok", "[unk]"), (0 until g.length()).map { g.getString(it) })
    }

    @Test fun matchesResultsAndPartials() {
        val words = listOf(WakeWord("hey bridget", "a1"), WakeWord("ok grok", "a2", sendClip = true))
        val full = """{"result":[{"word":"[unk]","conf":0.4,"start":0.1,"end":0.4},{"word":"hey","conf":0.98,"start":0.5,"end":0.7},{"word":"bridget","conf":0.91,"start":0.7,"end":1.2}],"text":"[unk] hey bridget"}"""
        val hit = WakeRules.match(full, words)!!
        assertEquals("a1", hit.wake.agentId)
        assertEquals(1.2, hit.endS!!, 1e-9)
        // A low-confidence word vetoes the hit.
        assertNull(WakeRules.match(full.replace("0.91", "0.40"), words))
        // Partials without confidences still match, with the end time when given.
        assertEquals("a2", WakeRules.match("""{"partial":"ok grok"}""", words)!!.wake.agentId)
        assertNull(WakeRules.match("""{"partial":"ok grok"}""", words)!!.endS)
        assertNull(WakeRules.match("""{"partial":"hey"}""", words))
        assertNull(WakeRules.match("""{"text":""}""", words))
        assertNull(WakeRules.match("not json", words))
    }

    @Test fun pauseDetectorEnds() {
        // Quiet room, speech for 2 s, then 1.5 s of quiet: a pause.
        val d = WakeRules.PauseDetector(100)
        repeat(5) { assertNull(d.feed(100.0)) }
        repeat(20) { assertNull(d.feed(3000.0)) }
        repeat(14) { assertNull(d.feed(100.0)) }
        assertEquals(WakeRules.PauseDetector.End.PAUSE, d.feed(100.0))
        assertTrue(d.heardSpeech)
        // Nobody speaks: ends after 6 s.
        val q = WakeRules.PauseDetector(100)
        var end: WakeRules.PauseDetector.End? = null; var n = 0
        while (end == null) { end = q.feed(150.0); n++ }
        assertEquals(WakeRules.PauseDetector.End.NO_SPEECH, end); assertEquals(60, n)
        // Speech that never stops: the 60 s cap.
        val l = WakeRules.PauseDetector(100)
        l.feed(100.0); end = null; n = 1
        while (end == null) { end = l.feed(4000.0); n++ }
        assertEquals(WakeRules.PauseDetector.End.MAX, end); assertEquals(600, n)
        // A steady fan well above the absolute floor isn't speech once the floor has adapted.
        val fan = WakeRules.PauseDetector(100)
        end = null; n = 0
        while (end == null) { end = fan.feed(800.0); n++ }
        assertEquals(WakeRules.PauseDetector.End.NO_SPEECH, end)
    }

    @Test fun soundGateSkipsSilence() {
        val g = WakeRules.SoundGate(hangoverFrames = 3)
        repeat(20) { assertFalse(g.feed(80.0)) }      // quiet room: nothing reaches the spotter
        assertTrue(g.feed(2000.0))                    // speech opens it
        repeat(3) { assertTrue(g.feed(80.0)) }        // hangover keeps word endings
        assertFalse(g.feed(80.0))
        val fan = WakeRules.SoundGate()
        var open = 0
        repeat(100) { if (fan.feed(600.0)) open++ }   // a steady fan from the start never opens it
        assertEquals(0, open)
    }

    @Test fun soundDetectorStartsEndsAndCoolsDown() {
        val d = WakeRules.SoundDetector("normal", cooldownMs = 5_000)
        repeat(20) { assertNull(d.feed(100.0)) }                       // quiet room
        assertNull(d.feed(5000.0))                                       // one loud frame isn't enough
        assertEquals(WakeRules.SoundDetector.Ev.START, d.feed(6000.0)) // 200 ms of it is
        repeat(5) { assertNull(d.feed(4000.0)) }
        repeat(9) { assertNull(d.feed(100.0)) }
        assertEquals(WakeRules.SoundDetector.Ev.END, d.feed(100.0))    // after 1 s of quiet
        assertEquals(6000.0, d.peakRms, 1e-9)
        assertEquals(1700L, d.durationMs)
        repeat(2) { assertNull(d.feed(6000.0)) }                         // cooling down: no new sound
        repeat(60) { d.feed(100.0) }
        d.feed(6000.0); assertEquals(WakeRules.SoundDetector.Ev.START, d.feed(6000.0))
        // A sound that never stops is cut at 10 s.
        val l = WakeRules.SoundDetector("normal")
        l.feed(100.0); l.feed(6000.0); l.feed(6000.0)
        var n = 0; while (l.feed(6000.0) == null) n++
        assertEquals(97, n) // 10 s from the first loud frame
        // Only loud: a voice at 1500 doesn't count, a bang at 9000 does.
        val loud = WakeRules.SoundDetector("loud")
        repeat(10) { loud.feed(100.0) }
        assertNull(loud.feed(1500.0)); assertNull(loud.feed(1500.0))
        loud.feed(9000.0); assertEquals(WakeRules.SoundDetector.Ev.START, loud.feed(9000.0))
    }

    @Test fun soundEventAndConfig() {
        val e = WakeRules.soundEvent("t", 1.74, 16384.0, 3276.8, "normal", "c9")
        assertEquals(-6.0, e.getDouble("peak_dbfs"), 0.05)
        assertEquals(-20.0, e.getDouble("mean_dbfs"), 0.05)
        assertEquals(1.7, e.getDouble("duration_seconds"), 1e-9)
        assertEquals("/v1/sounds/clip/c9", e.getString("clip_url"))
        assertEquals(SoundConfig("a1", "loud", false), SoundConfig.fromJson(SoundConfig("a1", "loud", false).toJson().toString()))
        assertEquals("normal", SoundConfig.fromJson("""{"agent_id":"a1","sensitivity":"deafening"}""")!!.sensitivity)
        assertNull(SoundConfig.fromJson("")); assertNull(SoundConfig.fromJson("{oops"))
    }

    @Test fun rmsOfPcm() {
        val pcm = ByteArray(4); pcm[0] = 0xE8.toByte(); pcm[1] = 0x03; pcm[2] = 0x18; pcm[3] = 0xFC.toByte() // +1000, -1000
        assertEquals(1000.0, WakeRules.rms(pcm, 4), 1e-9)
        assertEquals(0.0, WakeRules.rms(pcm, 0), 1e-9)
    }

    @Test fun heardEventCarriesOnlyWhatFollows() {
        val e = WakeRules.heardEvent(WakeWord("Hey Bridget", "a1", true), "water the plants", "2026-10-30T10:00:00+01:00", "pause", 2.345, "vosk", "c1")
        assertEquals("hey bridget", e.getString("wake_word"))
        assertEquals("water the plants", e.getString("transcript"))
        assertEquals(2.3, e.getDouble("duration_seconds"), 1e-9)
        assertEquals("/v1/listen/clip/c1", e.getString("clip_url"))
        val noClip = WakeRules.heardEvent(WakeWord("hey bridget", "a1"), "", "t", "no_speech", 6.0, "vosk", null)
        assertTrue(noClip.isNull("clip_id")); assertFalse(noClip.has("clip_url"))
    }

    @Test fun logKeepsAtMost200Chars() {
        assertEquals("short", WakeRules.forLog("short"))
        assertEquals(200, WakeRules.forLog("x".repeat(500)).length)
    }

    @Test fun storageRoundTripsAndToleratesJunk() {
        val w = listOf(WakeWord("hey bridget", "a1", true), WakeWord("ok grok", "a2"))
        assertEquals(w, WakeRules.fromJson(WakeRules.toJson(w)))
        assertEquals(emptyList<WakeWord>(), WakeRules.fromJson("{oops"))
        assertEquals(emptyList<WakeWord>(), WakeRules.fromJson(null))
    }

    @Test fun wavHeader() {
        val wav = WakeRules.wav(ByteArray(3200))
        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals("data", String(wav, 36, 4))
        assertEquals(16000, (wav[24].toInt() and 0xff) or ((wav[25].toInt() and 0xff) shl 8) or ((wav[26].toInt() and 0xff) shl 16))
    }

    @Test fun demoListsTheWakeWord() {
        val d = Demos.all.single { it.title == "Say the wake word" }
        assertEquals("wakewords", d.capability)
    }
}
