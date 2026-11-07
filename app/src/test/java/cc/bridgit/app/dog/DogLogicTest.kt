package cc.bridgit.app.dog

import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.vision.ChangeGate
import cc.bridgit.app.vision.ChangeSettings
import cc.bridgit.app.vision.FrameDiff
import cc.bridgit.app.vision.NoiseMask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DogLogicTest {
    private val classifier = PetClassifier.parse(JSONObject(java.io.File("../applets/dog-monitor/editions/1.5.0/applet.json").readText())
        .getJSONObject("processing").getJSONObject("classifier"))
    @Test fun legacySavedSleepTalliesAndEpisodesLoadAsResting() {
        val old = DogTracker(frameMs = 10_000)
        old.add(r(0, "sleeping"), utc)
        val saved = old.toJson()
        saved.getJSONObject("views").remove("resting")
        saved.getJSONObject("views").put("sleeping", 2).put("resting", 3)
        saved.getJSONArray("episodes").getJSONObject(0).put("kind", "sleeping")
        saved.getJSONArray("readings").getJSONObject(0).put("activity", "sleeping")
        val restored = DogTracker(frameMs = 10_000)
        restored.load(saved)
        assertEquals(50_000L, restored.day(noon, utc).sleepMs)
        assertEquals("resting", restored.episodes().single().kind)
        assertEquals("resting", restored.latest()!!.activity)
    }
    @Test fun restingIncludesLegacySleepingAndSurvivesSavedHistory() {
        val tracker = DogTracker(frameMs = 10_000)
        tracker.add(r(0, "sleeping"), utc)
        tracker.add(r(10, "resting"), utc)
        val saved = tracker.toJson()
        val restored = DogTracker(frameMs = 10_000)
        restored.load(saved)
        val day = restored.day(noon + 10_000, utc)
        assertEquals(20_000L, day.sleepMs)
        assertEquals(1, day.naps)
        assertEquals(mapOf("resting" to 2), day.views)
        assertEquals("resting", restored.latest()!!.activity)
        assertTrue(DogJev.sameAnswer(r(0, "sleeping"), r(10, "resting")))
        assertFalse(classifier.questions().getJSONObject("activity").getJSONObject("criteria").has("sleeping"))
    }

    private val utc = TimeZone.getTimeZone("UTC")
    private val noon = 1_793_016_000_000L // 2026-10-26 12:00 UTC... any noon works
        .let { it - it % 86_400_000L + 12 * 3_600_000L }
    private fun r(sec: Long, activity: String?, dog: Boolean = true) =
        Reading(noon + sec * 1000, dog, if (dog) 0.95 else 0.05, activity, if (activity != null) 0.9 else 0.0, "relaxed", 0.8)

    private val scene = IntArray(FrameDiff.SIZE * FrameDiff.SIZE) { (it * 7) % 200 }
    private fun flip(g: IntArray, x0: Int, y0: Int, w: Int, h: Int) = g.copyOf().also {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) it[y * FrameDiff.SIZE + x] = 255 - it[y * FrameDiff.SIZE + x]
    }

    @Test fun anUnchangedPictureKeepsItsAnswerForAnHour() {
        val gate = ChangeGate<Reading>(DogMonitor.CHANGE)
        for (r in listOf(r(0, "sleeping"), r(0, null, dog = false))) {
            gate.clear()
            gate.answered(scene, 0, "clef", r, DogJev::sameAnswer)
            assertEquals(r, gate.reusable(scene, 59 * 60_000L, "clef"))
            assertNull(gate.reusable(scene, 60 * 60_000L, "clef"))
        }
    }

    @Test fun theHourlyCheckReportsAChangeTheComparisonMissed() {
        val asleep = r(0, "sleeping"); val awake = r(3600, "moving")
        assertTrue(DogJev.missedChange(true, asleep, awake))
        assertFalse(DogJev.missedChange(true, asleep, r(3600, "sleeping"))) // same answer: nothing missed
        assertFalse(DogJev.missedChange(false, asleep, awake)) // the picture changed: the comparison did its job
        assertFalse(DogJev.missedChange(true, null, awake))
        assertFalse(DogJev.missedChange(true, asleep, awake.copy(model = "clef"))) // a model switch isn't a miss
    }

    @Test fun aSmallMoveAsksTheModel() {
        val gate = ChangeGate<Reading>(DogMonitor.CHANGE)
        gate.answered(scene, 0, "clef", r(0, "sleeping"), DogJev::sameAnswer)
        // 4 x 4 cells (1.6% of the picture): too small for watches' "normal" check, enough here.
        val head = flip(scene, 10, 10, 4, 4)
        assertTrue(FrameDiff.reuse(head, scene, 1000))
        assertNull(gate.reusable(head, 60_000L, "clef"))
        // 2 x 2 cells is still noise.
        assertNotNull(gate.reusable(flip(scene, 10, 10, 2, 2), 60_000L, "clef"))
    }

    @Test fun aChangeTheModelSaysDidntMatterIsIgnoredAfterwards() {
        val gate = ChangeGate<Reading>(DogMonitor.CHANGE)
        gate.answered(scene, 0, "clef", r(0, "sleeping"), DogJev::sameAnswer)
        val plant = flip(scene, 0, 0, 4, 4)
        assertNull(gate.reusable(plant, 10_000L, "clef"))
        gate.answered(plant, 10_000L, "clef", r(10, "sleeping"), DogJev::sameAnswer) // same answer: it didn't matter
        val plantBack = scene
        assertNotNull(gate.reusable(plantBack, 20_000L, "clef"))
    }

    @Test fun questionsAreTheThreeAndUseThePhotoReference() {
        val q = classifier.questions()
        assertEquals(setOf("dog_present", "activity", "mood"), q.keys().asSequence().toSet())
        assertEquals(DogJev.ACTIVITIES.toSet(), q.getJSONObject("activity").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals(DogJev.MOODS.toSet(), q.getJSONObject("mood").getJSONObject("criteria").keys().asSequence().toSet())
        assertTrue(q.getJSONObject("dog_present").getString("instructions").contains("`photo`"))
        assertEquals("dog-abc12345", classifier.state("dog-abc12345").getString("photo"))
    }

    @Test fun photoGoesAsAnAssetReferencedByTheState() {
        val ref = "dog-abc12345"
        val body = JSONObject(String(Jev.requestBody(classifier.state(ref), classifier.questions(), assets = JSONObject().put(ref, Jev.image(byteArrayOf(1, 2, 3))))))
        val a = body.getJSONObject("assets").getJSONObject(ref)
        assertEquals("image", a.getString("type"))
        assertEquals("jpg", a.getString("format"))
        assertEquals("AQID", a.getString("data_base64"))
        assertEquals(ref, body.getJSONObject("state").getString("photo"))
    }

    private fun answer(p: Double, act: String, actConf: Double, mood: String, moodConf: Double) = Jev.parse(200,
        """{"model":"jev-1.14.0","answers":{"dog_present":{"type":"noul","noul":$p},
        "activity":{"type":"choice","choice":"$act","confidence":$actConf},"mood":{"type":"choice","choice":"$mood","confidence":$moodConf}},
        "usage":{"input_tokens":1570}}""")

    @Test fun readingFromJev() {
        val yes = DogJev.reading(5, answer(0.98, "drinking", 1.0, "relaxed", 0.64))
        assertTrue(yes.dog); assertEquals("drinking", yes.activity); assertEquals("relaxed", yes.mood)
        val none = DogJev.reading(5, answer(0.04, "other", 1.0, "unclear", 1.0))
        assertFalse(none.dog); assertNull(none.activity); assertNull(none.mood)
        val unsure = DogJev.reading(5, answer(0.9, "eating", 0.3, "unclear", 0.9))
        assertTrue(unsure.dog); assertNull(unsure.activity); assertNull(unsure.mood)
        val junk = DogJev.reading(5, answer(0.9, "flying", 0.9, "smug", 0.9))
        assertNull(junk.activity); assertNull(junk.mood)
    }

    @Test fun aNapSurvivesOneRestingReadingAndEndsAfterTheGap() {
        val t = DogTracker(frameMs = 10_000)
        listOf(0L, 10, 20).forEach { t.add(r(it, "sleeping"), utc) }
        t.add(r(30, "resting"), utc) // lifted its head once
        listOf(40L, 50).forEach { t.add(r(it, "sleeping"), utc) }
        assertEquals(1, t.episodes().size)
        assertNotNull(t.open("sleeping", noon + 60_000))
        t.add(r(60, "moving"), utc); t.add(r(200, "moving"), utc)
        assertNull(t.open("sleeping", noon + 200_000))
        t.add(r(300, "sleeping"), utc)
        val day = t.day(noon + 300_000, utc)
        assertEquals(2, day.naps)
        assertEquals(70_000L, day.sleepMs) // legacy sleeping and resting are one observable activity
        assertEquals(noon + 300_000, day.asleepSince)
        assertEquals(10_000L, day.asleepForMs)
    }

    @Test fun oneLongDrinkIsOneDrinkAndTwoVisitsAreTwo() {
        val t = DogTracker(frameMs = 10_000)
        listOf(0L, 10, 20).forEach { t.add(r(it, "drinking")) }
        t.add(r(30, null, dog = false))
        t.add(r(40, "drinking")) // back within a minute: same drink
        t.add(r(200, "drinking")) // later: a second drink
        t.add(r(400, "eating")); t.add(r(410, "eating"))
        val day = t.day(noon + 420_000, utc)
        assertEquals(2, day.drinks); assertEquals(noon + 200_000, day.lastDrink)
        assertEquals(1, day.meals); assertEquals(noon + 400_000, day.lastMeal)
        assertEquals(0, day.naps); assertNull(day.asleepSince)
    }

    @Test fun napOverMidnightCountsFromMidnightAndYesterdayIsLeftOut() {
        val t = DogTracker(frameMs = 10_000)
        val midnight = noon + 12 * 3_600_000L
        t.add(Reading(noon, true, 0.9, "eating", 0.9, null, 0.0), utc) // yesterday's meal
        t.add(Reading(midnight - 20_000, true, 0.9, "sleeping", 0.9, null, 0.0), utc)
        t.add(Reading(midnight + 20_000, true, 0.9, "sleeping", 0.9, null, 0.0), utc)
        val day = t.day(midnight + 30_000, utc)
        assertEquals(0, day.meals)
        assertEquals(1, day.naps)
        assertEquals(10_000L, day.sleepMs) // only the sleeping check after midnight counts today
    }

    @Test fun mealAndDrinkTimeAccumulatesAcrossChecksAndSeparateVisits() {
        val t = DogTracker(frameMs = 10_000)
        listOf(0L, 10, 20).forEach { t.add(r(it, "eating"), utc) }
        listOf(100L, 110).forEach { t.add(r(it, "eating"), utc) }
        listOf(200L, 210, 220).forEach { t.add(r(it, "drinking"), utc) }
        val day = t.day(noon + 230_000, utc)
        assertEquals(2, day.meals)
        assertEquals(50_000L, day.eatingMs)
        assertEquals(1, day.drinks)
        assertEquals(30_000L, day.drinkingMs)
        val restored = DogTracker(frameMs = 10_000).apply { load(JSONObject(t.toJson().toString())) }
        assertEquals(day, restored.day(noon + 230_000, utc))
    }

    @Test fun episodeHistoryAndMealTimeAreClippedAtMidnight() {
        val t = DogTracker(frameMs = 10_000)
        val midnight = noon + 12 * 3_600_000L
        t.add(Reading(noon, true, .9, "drinking", .9, null, 0.0), utc)
        t.add(Reading(midnight - 10_000, true, .9, "eating", .9, null, 0.0), utc)
        t.add(Reading(midnight + 10_000, true, .9, "eating", .9, null, 0.0), utc)
        val day = t.day(midnight + 20_000, utc)
        assertEquals(10_000L, day.eatingMs)
        assertEquals(0L, day.drinkingMs)
        assertEquals(midnight, t.episodesToday(midnight + 20_000, utc).single().start)
        assertEquals(20_000L, t.episodesToday(midnight + 20_000, utc).single().durationMs(10_000))
    }

    @Test fun durationKeepsSecondsBeyondOneMinute() {
        assertEquals("10 s", cc.bridgit.app.dog.DogWords.duration(10_000))
        assertEquals("2 min", cc.bridgit.app.dog.DogWords.duration(120_000))
        assertEquals("2 min 10 s", cc.bridgit.app.dog.DogWords.duration(130_000))
    }

    @Test fun groupedSessionsDoNotDoubleCountContradictoryActivities() {
        val t = DogTracker(frameMs = 10_000)
        t.add(r(0, "eating"), utc)
        t.add(r(10, "drinking"), utc)
        t.add(r(20, "eating"), utc)
        val day = t.day(noon + 30_000, utc)
        assertEquals(1, day.meals) // brief gap still belongs to one visit
        assertEquals(20_000L, day.eatingMs)
        assertEquals(10_000L, day.drinkingMs)
        assertEquals(30_000L, day.eatingMs + day.drinkingMs)
        assertEquals(0L, t.day(noon + 86_400_000, utc).eatingMs)
        assertTrue(t.episodesToday(noon + 86_400_000, utc).isEmpty())
    }

    @Test fun savesAndLoads() {
        val t = DogTracker(frameMs = 10_000)
        t.add(r(0, "sleeping")); t.add(r(10, null, dog = false))
        val back = DogTracker(frameMs = 10_000).apply { load(JSONObject(t.toJson().toString())) }
        assertEquals(t.episodes(), back.episodes())
        assertEquals(t.recent(), back.recent())
        assertNull(back.latest()!!.activity)
    }

    @Test fun keepsOnlyRecentReadings() {
        val t = DogTracker()
        repeat(DogTracker.MAX_READINGS + 10) { t.add(r(it * 10L, "moving")) }
        assertEquals(DogTracker.MAX_READINGS, t.recent().size)
    }

    @Test fun talliesCountPhotosByWhatJevSaw() {
        val t = DogTracker(frameMs = 10_000)
        listOf(0L, 10).forEach { t.add(r(it, "sleeping"), utc) }
        t.add(r(20, "eating"), utc); t.add(r(30, null), utc); t.add(r(40, null, dog = false), utc)
        val d = t.day(noon + 50_000, utc)
        assertEquals(mapOf("resting" to 2, "eating" to 1, "unsure" to 1, "no_dog" to 1), d.views)
        assertEquals(5, d.photos)
        assertEquals(50_000L, d.watchedMs)
        val back = DogTracker(frameMs = 10_000).apply { load(JSONObject(t.toJson().toString())) }
        assertEquals(d.views, back.day(noon + 50_000, utc).views)
        // A new day starts the tallies again.
        t.add(r(86_400, "moving"), utc)
        assertEquals(mapOf("moving" to 1), t.day(noon + 86_400_000, utc).views)
    }

    @Test fun medianGapOnlyCountsWatchedGaps() {
        val t = DogTracker(frameMs = 10_000)
        // Watched without a break from 0 to 1,000 s: drinks start at 0, 300 and 500 s.
        var s = 0L
        while (s <= 1000) { t.add(r(s, if (s == 0L || s == 300L || s == 500L) "drinking" else "resting"), utc); s += 10 }
        // Camera off for an hour, then one more drink: that gap must not count.
        t.add(r(4600, "drinking"), utc)
        val g = t.medianGap("drinking", noon + 4_600_000)!!
        assertEquals(2, g.count)
        assertEquals(250_000L, g.medianMs) // gaps 300 s and 200 s
        assertNull(t.medianGap("eating", noon + 4_600_000))
        assertEquals(5L, DogTracker.median(listOf(9, 1, 5)))
        assertEquals(4L, DogTracker.median(listOf(2, 6)))
    }

    @Test fun aClockThatMovesBackIsIgnored() {
        val t = DogTracker(frameMs = 10_000)
        t.add(r(100, "drinking"), utc); t.add(r(110, "drinking"), utc)
        t.add(r(50, "drinking"), utc) // clock corrected backwards
        assertEquals(1, t.episodes().size)
        assertEquals(2, t.day(noon + 120_000, utc).photos)
    }

    @Test fun clefGetsThePhotoAsADataUrlBeforeTheState() {
        val body = JSONObject(String(Jev.requestBody(classifier.state(), classifier.questions("the photo"), images = listOf(Jev.dataUrl(byteArrayOf(1, 2, 3))))))
        assertEquals("data:image/jpeg;base64,AQID", body.getJSONArray("images").getString(0))
        assertFalse(body.has("assets"))
        assertFalse(body.getJSONObject("state").has("photo"))
        val q = body.getJSONObject("questions").getJSONObject("dog_present").getString("instructions")
        assertTrue(q.contains("the photo")); assertFalse(q.contains("`"))
    }

    @Test fun readingsRememberWhichModelAnswered() {
        val t = DogTracker()
        t.add(DogJev.reading(noon, answer(0.9, "sleeping", 0.9, "relaxed", 0.9), "clef"), utc)
        assertEquals("clef", t.latest()!!.model)
        assertEquals("clef", DogTracker().apply { load(JSONObject(t.toJson().toString())) }.latest()!!.model)
        assertEquals("jev", Reading.from(JSONObject("""{"at":1,"dog":false}""")).model) // saved before models were noted
    }

    @Test fun clefQuotaIsNotRetried() {
        val f = runCatching { Jev.parse(429, """{"error":"clef_quota","message":"used up"}""") }.exceptionOrNull() as Jev.Failure
        assertEquals("clef_quota", f.reason); assertFalse(f.retryable)
        val busy = runCatching { Jev.parse(503, """{"error":"clef_busy","message":"busy"}""") }.exceptionOrNull() as Jev.Failure
        assertTrue(busy.retryable)
    }

    private fun grid(f: (Int) -> Int) = IntArray(FrameDiff.SIZE * FrameDiff.SIZE) { f(it).coerceIn(0, 255) }

    @Test fun samePictureIsReusedAndAChangeIsNot() {
        val room = grid { (it * 37) % 200 + 20 }
        assertEquals(0.0, FrameDiff.difference(room, room), 1e-9)
        // Sensor noise of a step or two in brightness: still the same picture.
        val noisy = grid { room[it] + if (it % 3 == 0) 2 else -1 }
        assertTrue(FrameDiff.reuse(noisy, room, 10_000))
        // The camera brightening everything by 30 (exposure) isn't a change either.
        val brighter = grid { room[it] + 30 }
        assertTrue(FrameDiff.difference(brighter, room) < 0.005)
        // A dog walking in: a block of 10 x 10 cells changes a lot.
        val dog = grid { i -> if (i % FrameDiff.SIZE < 10 && i / FrameDiff.SIZE < 10) 250 else room[i] }
        assertFalse(FrameDiff.reuse(dog, room, 10_000))
        // Same picture, but too long since the model last looked: ask again.
        assertFalse(FrameDiff.reuse(room, room, FrameDiff.MAX_REUSE_MS))
        assertFalse(FrameDiff.reuse(room, null, 10_000))
    }

    @Test fun reusedReadingsAreSavedAsReused() {
        val t = DogTracker()
        t.add(r(0, "sleeping"), utc); t.add(r(10, "sleeping").copy(reused = true), utc)
        val back = DogTracker().apply { load(JSONObject(t.toJson().toString())) }
        assertEquals(listOf(false, true), back.recent().map { it.reused })
        assertEquals(1, back.episodes().size)
    }

    @Test fun leavesFlickeringInScatteredSpotsDontCountButADogDoes() {
        val room = grid { (it * 37) % 200 + 20 }
        // Leaves: 40 single cells all over a tree area flip hard, none touching each other.
        val leaves = grid { i -> val x = i % FrameDiff.SIZE; val y = i / FrameDiff.SIZE
            if (x >= 20 && y < 16 && x % 2 == 0 && y % 2 == 0) (room[i] + 120) % 256 else room[i] }
        assertTrue(FrameDiff.difference(leaves, room) > 0.01) // the old average check would have paid for this
        assertTrue(FrameDiff.reuse(leaves, room, 10_000))
        // A small far-away dog: a 6 x 5 patch (about 3% of the picture).
        val smallDog = grid { i -> if (i % FrameDiff.SIZE in 4..9 && i / FrameDiff.SIZE in 20..24) 250 else room[i] }
        assertFalse(FrameDiff.reuse(smallDog, room, 10_000))
        // A twitch: a 3 x 3 patch is below the bar.
        val twitch = grid { i -> if (i % FrameDiff.SIZE in 4..6 && i / FrameDiff.SIZE in 20..22) 250 else room[i] }
        assertTrue(FrameDiff.reuse(twitch, room, 10_000))
    }

    @Test fun aWastedCallTeachesTheMaskToIgnoreThoseSpots() {
        val room = grid { (it * 37) % 200 + 20 }
        // A TV in one corner: a solid 8 x 6 patch keeps changing.
        fun tv(level: Int) = grid { i -> if (i % FrameDiff.SIZE in 24..31 && i / FrameDiff.SIZE in 0..5) level else room[i] }
        val mask = NoiseMask()
        assertFalse(FrameDiff.reuse(tv(240), tv(10), 10_000, mask.muted(0))) // first time: a paid call
        assertTrue(mask.learn(FrameDiff.changedCells(tv(240), tv(10)), 0)) // the answer didn't change
        assertTrue(FrameDiff.reuse(tv(10), tv(240), 10_000, mask.muted(1_000))) // next flicker: free
        // A dog walking in elsewhere still counts while the TV is muted.
        val dogAndTv = grid { i -> if (i % FrameDiff.SIZE in 2..9 && i / FrameDiff.SIZE in 18..25) 250 else tv(10)[i] }
        assertFalse(FrameDiff.reuse(dogAndTv, tv(240), 10_000, mask.muted(1_000)))
        // The mask forgets after its time.
        assertFalse(FrameDiff.reuse(tv(10), tv(240), 10_000, mask.muted(NoiseMask.TTL_MS + 1)))
        // A whole-picture change (lights on) is never learned.
        assertFalse(mask.learn(BooleanArray(FrameDiff.SIZE * FrameDiff.SIZE) { true }, 0))
    }

    @Test fun sameAnswerMeansSameDogActivityAndMood() {
        assertTrue(DogJev.sameAnswer(r(0, "sleeping"), r(10, "sleeping")))
        assertFalse(DogJev.sameAnswer(r(0, "sleeping"), r(10, "eating")))
        assertFalse(DogJev.sameAnswer(r(0, null), r(10, null, dog = false)))
    }
}
