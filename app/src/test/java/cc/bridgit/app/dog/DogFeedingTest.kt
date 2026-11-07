package cc.bridgit.app.dog

import cc.bridgit.app.lancamera.CameraAim

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DogFeedingTest {
    private val now=100_000L
    private fun sound(source:String="cam",kind:String="eating",confidence:Double=.9,at:Long=now)=DogAudioReading(source,at,kind,confidence)
    @Test fun onlyFreshConfidentFoodSoundsFromSavedCameraTrigger() {
        assertTrue(FeedingRules.trigger(sound(),"cam",now,0))
        assertTrue(FeedingRules.trigger(sound(kind="drinking"),"cam",now,0))
        for(s in listOf(sound(source="other"),sound(kind="barking"),sound(kind="growling"),sound(kind="unclear"),sound(confidence=.74),sound(confidence=Double.NaN),sound(at=now-20_001),sound(at=now+1))) assertFalse(FeedingRules.trigger(s,"cam",now,0))
        assertFalse(FeedingRules.trigger(sound(),"cam",now,now-59_999))
        assertTrue(FeedingRules.trigger(sound(),"cam",now,now-60_000))
    }
    private class Fake:CameraAim.Driver {
        val calls=mutableListOf<String>();var allowed=true;var arrived=true;var stopped=true;var revoke=false;var cancel=false;var waitCount=0
        override suspend fun authorize() { calls.add("authorize");check(allowed) }
        override suspend fun move() { calls.add("move") }
        override suspend fun settle() { calls.add("settle");if(revoke)allowed=false;if(cancel)throw CancellationException() }
        override suspend fun arrived():Boolean { calls.add("arrived");if(waitCount>0) { waitCount--;return false };return arrived }
        override suspend fun stop():Boolean { calls.add("stop");return stopped }
    }
    @Test fun cameraMustSettleReauthorizeArriveAndStopBeforeCapture()=runBlocking {
        val f=Fake();assertTrue(CameraAim.run(f));assertEquals(listOf("authorize","move","settle","authorize","arrived","stop"),f.calls)
        for(failure in listOf("arrival","stop")) { val g=Fake();if(failure=="arrival")g.arrived=false else g.stopped=false;assertFalse(CameraAim.run(g)) }
    }
    @Test fun longerMovesWaitForArrivalAndTimeoutStillStops()=runBlocking {
        val f=Fake();f.waitCount=4;assertTrue(CameraAim.run(f));assertEquals(5,f.calls.count { it=="arrived" });assertEquals("stop",f.calls.last())
        val timeout=Fake();timeout.waitCount=20;assertFalse(CameraAim.run(timeout));assertEquals(12,timeout.calls.count { it=="arrived" });assertEquals("stop",timeout.calls.last())
    }
    @Test fun revocationAndCancellationAlwaysStopAndDoNotConfirmView()=runBlocking {
        for(cancel in listOf(false,true)) {
            val f=Fake();f.revoke=!cancel;f.cancel=cancel
            try { CameraAim.run(f);fail("Expected cancellation or revoked access") } catch(_:Exception) {}
            assertEquals("stop",f.calls.last());assertFalse(f.calls.contains("arrived"))
        }
    }
    @Test fun timeoutStopsInsteadOfEndingTheMonitorJob()=runBlocking {
        val driver=object:CameraAim.Driver {
            var stopped=false
            override suspend fun authorize() {}
            override suspend fun move() {}
            override suspend fun settle() { delay(1000) }
            override suspend fun arrived()=true
            override suspend fun stop():Boolean { stopped=true;return true }
        }
        assertFalse(CameraAim.run(driver,5));assertTrue(driver.stopped);assertTrue(currentCoroutineContext().isActive)
    }
    @Test fun changingSavedViewDuringMoveInvalidatesOldCapture()=runBlocking {
        val config=FeedingEpoch();val original=config.current
        val driver=object:CameraAim.Driver {
            var arrived=false;var stopped=false
            override suspend fun authorize() { check(config.isCurrent(original)) }
            override suspend fun move() {}
            override suspend fun settle() { config.invalidate() }
            override suspend fun arrived():Boolean { arrived=true;return true }
            override suspend fun stop():Boolean { stopped=true;return true }
        }
        try { CameraAim.run(driver);fail("Old view must not become a capture target") } catch(_:IllegalStateException) {}
        assertFalse(driver.arrived);assertTrue(driver.stopped);assertFalse(config.isCurrent(original))
    }
    @Test fun selectedAudioCategoriesDoNotClaimIdentity() {
        assertTrue(DogAudio.SOUNDS.containsAll(listOf("eating","drinking")))
        val criteria=org.json.JSONObject(java.io.File("../applets/dog-monitor/editions/1.5.0/applet.json").readText()).getJSONObject("processing").getJSONObject("classifier").getJSONObject("sound").getJSONObject("criteria")
        assertTrue(criteria.getString("eating").contains("do not infer an animal identity"))
    }
}
