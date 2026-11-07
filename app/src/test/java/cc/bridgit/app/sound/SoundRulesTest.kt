package cc.bridgit.app.sound

import cc.bridgit.app.sound.SoundRules.Decision
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoundRulesTest {
    private val min = 60_000L
    private val hour = 3_600_000L
    // Day = whole days since 0, so tests control midnight.
    private fun episodes(cap: Int = 6) = SoundEpisodes(setOf("barking", "growling"), { (it / (24 * hour)).toString() }, maxPerHour = cap)

    /** 16 kHz mono PCM: a sum of tones, [seconds] long, at [gain]; [noise] adds a little seeded hiss. */
    private fun pcm(freqs: List<Double>, seconds: Double, gain: Double = 8000.0, noise: Double = 300.0, seed: Long = 1): ByteArray {
        val r = java.util.Random(seed)
        val n = (16_000 * seconds).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = freqs.sumOf { sin(2 * PI * it * i / 16_000) } / freqs.size * gain + r.nextGaussian() * noise
            val s = v.toInt().coerceIn(-32768, 32767)
            out[2 * i] = (s and 0xff).toByte(); out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }
    // "Bark": strong low harmonics. "Chime": two high tones.
    private val bark1 = SoundPrint.of(pcm(listOf(400.0, 800.0, 1200.0, 1600.0), 1.3, seed = 1))!!
    private val bark2 = SoundPrint.of(pcm(listOf(400.0, 800.0, 1200.0, 1600.0), 1.0, gain = 3000.0, seed = 2))!!
    private val chime = SoundPrint.of(pcm(listOf(2600.0, 3300.0), 1.8, seed = 3))!!

    @Test fun printsTellSoundsApart() {
        assertTrue(bark1.cosine(bark2) >= SoundPrint.SIMILAR) // same kind, quieter and shorter
        assertTrue(bark1.similar(bark2))
        assertTrue(bark1.cosine(chime) < SoundPrint.SIMILAR - 0.15)
        assertFalse(bark1.similar(chime)); assertFalse(bark1.similar(null))
        assertNull(SoundPrint.of(ByteArray(16_000))) // silence: no print
        assertNull(SoundPrint.of(ByteArray(100)))
        // Same sound, far longer: not the same sound.
        assertFalse(bark1.similar(SoundPrint.of(pcm(listOf(400.0, 800.0, 1200.0, 1600.0), 8.0))!!))
    }

    @Test fun decideOrder() {
        // A recent, similar answer wins even over the cap and a pause: the same episode costs nothing.
        assertEquals(Decision.COUNT, SoundRules.decide(10 * min, 6 * min, 5 * min, true, 6, 6, 20 * min))
        // Not similar: never reused.
        assertEquals(Decision.ASK, SoundRules.decide(10 * min, 9 * min, 5 * min, false, 0, 6, 0))
        assertEquals(Decision.SKIP, SoundRules.decide(10 * min, 0, 5 * min, true, 6, 6, 0))
        assertEquals(Decision.SKIP, SoundRules.decide(10 * min, 0, 5 * min, true, 0, 6, 11 * min))
        assertEquals(Decision.ASK, SoundRules.decide(10 * min, 0, 5 * min, true, 5, 6, 0))
        assertEquals(Decision.ASK, SoundRules.decide(20 * min, 10 * min, 5 * min, true, 0, 6, 0))
        assertEquals(Decision.ASK, SoundRules.decide(10 * min, 9 * min, 0, true, 0, 6, 0))
        // An answer for a slightly later sound still covers this one (answers can arrive out of order).
        assertEquals(Decision.COUNT, SoundRules.decide(10 * min, 11 * min, 5 * min, true, 6, 6, 0))
    }

    @Test fun barkingEpisodeCountsRepeatsWithoutChecks() {
        val e = episodes()
        val t = 10 * hour
        assertEquals(Decision.ASK, e.onLoud(t, 3_000, bark1).first)
        assertTrue(e.onAnswer(t, 3_000, "barking", bark1))          // a new episode: one alert
        assertEquals(Decision.COUNT to "barking", e.onLoud(t + 2 * min, 4_000, bark2))
        assertEquals(Decision.COUNT, e.onLoud(t + 6 * min, 2_000, bark1).first) // 4 min after the last one: still going
        val d = e.day(t + 6 * min)
        assertEquals(1, d.episodes); assertEquals(9_000, d.hitMs); assertEquals(1, d.checks); assertTrue(d.now)
        assertEquals(t + 6 * min, d.lastHit)
        assertFalse(e.day(t + 12 * min).now)
        // After the gap a loud sound is checked again, and barking is a second episode.
        assertEquals(Decision.ASK, e.onLoud(t + 12 * min, 1_000, bark1).first)
        assertTrue(e.onAnswer(t + 12 * min, 1_000, "growling", bark1))
        assertEquals(2, e.day(t + 12 * min).episodes)
    }

    @Test fun aDifferentSoundAfterBarkingIsChecked() {
        val e = episodes()
        e.onLoud(hour, 2_000, bark1); e.onAnswer(hour, 2_000, "barking", bark1)
        // A doorbell a minute later doesn't count as barking: it's asked about.
        assertEquals(Decision.ASK, e.onLoud(hour + min, 1_800, chime).first)
        assertFalse(e.onAnswer(hour + min, 1_800, "other", chime))
        // The doorbell again: the same kind of sound as the last check, reused for free, still not barking.
        assertEquals(Decision.COUNT to "other", e.onLoud(hour + 2 * min, 1_800, chime))
        assertEquals(2_000, e.day(hour + 2 * min).hitMs)
        assertEquals(2, e.day(hour + 2 * min).checks)
    }

    @Test fun lateAnswerDoesNotStartASecondEpisode() {
        val e = episodes()
        val t = 5 * hour
        e.onLoud(t, 5_000, null); e.onLoud(t + 2_000, 2_000, bark1)
        assertTrue(e.onAnswer(t + 2_000, 2_000, "barking", bark1))
        assertFalse(e.onAnswer(t, 5_000, "barking"))   // the earlier sample's answer arrives last
        assertEquals(1, e.day(t + 3_000).episodes)
    }

    @Test fun hourlyCapAndBudgetPause() {
        val e = episodes(cap = 2)
        assertEquals(Decision.ASK, e.onLoud(hour, 1_000, bark1).first); e.onAnswer(hour, 1_000, "other", bark1)
        assertEquals(Decision.ASK, e.onLoud(hour + min, 1_000, chime).first); e.onAnswer(hour + min, 1_000, "other", chime)
        assertEquals(Decision.SKIP, e.onLoud(hour + 2 * min, 1_000, bark1).first)
        assertEquals(1, e.day(hour + 2 * min).skipped)
        assertEquals(Decision.ASK, e.onLoud(2 * hour + 1, 1_000, bark1).first) // the first check is over an hour old
        e.pause(2 * hour + 2)
        assertEquals(Decision.SKIP, e.onLoud(2 * hour + 10 * min, 1_000, chime).first)
        assertTrue(e.day(2 * hour + 10 * min).pausedUntil > 0)
        assertFalse(e.reserve(2 * hour + 10 * min, exempt = true)) // a pause stops even exempt samples
        assertTrue(e.reserve(3 * hour + 10 * min))
    }

    @Test fun restartKeepsCapPauseAndEpisode() {
        val e = episodes(cap = 1)
        e.onLoud(hour, 1_000, bark1); e.onAnswer(hour, 1_000, "barking", bark1); e.pause(hour)
        val back = episodes(cap = 1).apply { load(JSONObject(e.toJson().toString())) }
        assertTrue(back.day(hour + min).pausedUntil > 0)
        assertTrue(back.day(hour + min).now)
        assertFalse(back.reserve(hour + min))
        // Barking after the restart (checked again: the fingerprint isn't kept) continues the same episode.
        val fresh = episodes().apply { load(JSONObject(e.toJson().toString()).put("paused_until", 0).put("paid", org.json.JSONArray())) }
        assertEquals(Decision.ASK, fresh.onLoud(hour + 2 * min, 1_000, bark1).first)
        assertFalse(fresh.onAnswer(hour + 2 * min, 1_000, "barking", bark1))
        assertEquals(1, fresh.day(hour + 2 * min).episodes)
    }

    @Test fun dayRollsAndCarriesAnEpisodeOverMidnight() {
        val e = episodes()
        e.onLoud(23 * hour, 2_000, bark1); e.onAnswer(23 * hour, 2_000, "barking", bark1)
        val back = episodes().apply { load(JSONObject(e.toJson().toString())) }
        assertEquals(1, back.day(23 * hour).episodes); assertEquals(2_000, back.day(23 * hour).hitMs)
        val next = back.day(26 * hour)
        assertEquals(0, next.episodes); assertNull(next.lastHit)
        // Barking at 23:58 and 00:02: the new day counts that episode once.
        val m = episodes()
        val mid = 24 * hour
        m.onLoud(mid - 2 * min, 1_000, bark1); m.onAnswer(mid - 2 * min, 1_000, "barking", bark1)
        assertEquals(Decision.COUNT, m.onLoud(mid + 2 * min, 1_000, bark2).first)
        val day2 = m.day(mid + 2 * min)
        assertEquals(1, day2.episodes); assertTrue(day2.now); assertNotNull(day2.lastHit)
    }

    @Test fun loudSoundsSubscribers() {
        var changes = 0
        val loud = LoudSounds { changes++ }
        loud.subscribe(LoudSounds.Subscriber("watch:b", "quiet") { _, _, _, _ -> })
        loud.subscribe(LoudSounds.Subscriber("pet_monitor", "normal") { _, _, _, _ -> })
        loud.subscribe(LoudSounds.Subscriber("pet_monitor", "normal") { _, _, _, _ -> }) // same: no change
        assertEquals(2, changes); assertEquals(2, loud.version)
        assertEquals(listOf("pet_monitor" to "normal", "watch:b" to "quiet"), loud.active())
        loud.unsubscribe("watch:b"); loud.unsubscribe("watch:b")
        assertEquals(3, changes); assertEquals(3, loud.version)
        assertEquals(listOf("pet_monitor" to "normal"), loud.active())
    }

    @Test fun loudSoundsSourcesKeepCameraOnlySubscribersOffThePhoneMicrophone() {
        var changes = 0
        val loud = LoudSounds { changes++ }
        loud.subscribe(LoudSounds.Subscriber("pet_monitor", "normal", setOf("lan:dog")) { _, _, _, _ -> })
        assertEquals(emptyList<Pair<String, String>>(), loud.active()) // the phone's listener has nobody
        assertEquals(listOf("pet_monitor" to "normal"), loud.active("lan:dog"))
        assertEquals(setOf("lan:dog"), loud.cameraSources())
        loud.subscribe(LoudSounds.Subscriber("pet_monitor", "normal", setOf("lan:dog")) { _, _, _, _ -> }) // same: no change
        assertEquals(1, changes)
        loud.subscribe(LoudSounds.Subscriber("pet_monitor", "normal", setOf(LoudSounds.BUILTIN, "lan:dog", "lan:cat")) { _, _, _, _ -> })
        assertEquals(2, changes)
        assertEquals(listOf("pet_monitor" to "normal"), loud.active())
        assertEquals(setOf("lan:cat", "lan:dog"), loud.cameraSources())
        loud.subscribe(LoudSounds.Subscriber("watch:a", "quiet") { _, _, _, _ -> })
        assertEquals(setOf("lan:cat", "lan:dog"), loud.cameraSources())
        assertEquals(listOf("pet_monitor" to "normal"), loud.active("lan:cat"))
        assertThrows(IllegalArgumentException::class.java) { loud.subscribe(LoudSounds.Subscriber("x", "normal", setOf("http://evil")) { _, _, _, _ -> }) }
        assertThrows(IllegalArgumentException::class.java) { loud.subscribe(LoudSounds.Subscriber("x", "normal", emptySet()) { _, _, _, _ -> }) }
    }
}
