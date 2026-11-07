package cc.bridgit.app.dog

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PetCameraSearchTest {
    private class Fake(val seenAt: Int?=null): PetCameraSearch.Driver {
        val moves=mutableListOf<PetCameraSearch.Pose>();var checks=0;var stops=0;var cancel=false
        override suspend fun authorize() { if(cancel)throw CancellationException() }
        override suspend fun move(pose: PetCameraSearch.Pose) { moves.add(pose) }
        override suspend fun observe(): Boolean { checks++;return checks==seenAt }
        override suspend fun stop() { stops++ }
    }
    private val center=PetCameraSearch.Pose(.2,.1)
    private val original=PetCameraSearch.Pose(.15,.1)
    @Test fun searchesStartAtLastSeenPoseAndStopImmediatelyOnDetection()=runBlocking {
        val f=Fake(2);assertTrue(PetCameraSearch.run(f,center,original));assertEquals(center,f.moves.first());assertEquals(2,f.moves.size);assertEquals(1,f.stops)
    }
    @Test fun failedSearchRestoresStartingPoseAndAlwaysStops()=runBlocking {
        val f=Fake();assertFalse(PetCameraSearch.run(f,center,original));assertEquals(original,f.moves.last());assertEquals(7,f.checks);assertEquals(1,f.stops)
    }
    @Test fun cancellationNeverRestoresOrContinuesMoving()=runBlocking {
        val f=Fake();f.cancel=true
        try { PetCameraSearch.run(f,center,original);fail() } catch(_:CancellationException) {}
        assertTrue(f.moves.isEmpty());assertEquals(1,f.stops)
    }
    @Test fun recentSightingsCooldownAndStaleAnchorsPreventSearch() {
        val now=10_000_000L
        assertTrue(PetCameraSearch.due(now,now-61000,null,false))
        assertFalse(PetCameraSearch.due(now,now-59000,null,false))
        assertFalse(PetCameraSearch.due(now,now-61000,now-299000,false))
        assertFalse(PetCameraSearch.due(now,now-61000,null,true))
        assertFalse(PetCameraSearch.due(now,null,null,false))
        assertFalse(PetCameraSearch.due(now,now-31*60000,null,false))
    }
    @Test fun nearbyPosesStayBoundedAndNeverUseInvalidCoordinates() {
        for(c in listOf(center,PetCameraSearch.Pose(1.0,-1.0))) {
            val steps=PetCameraSearch.poses(c);assertEquals(steps.distinct(),steps);assertTrue(steps.size<=7)
            assertTrue(steps.all { kotlin.math.abs(it.pan-c.pan)<=.07001 && kotlin.math.abs(it.tilt-c.tilt)<=.03501 })
        }
        try { PetCameraSearch.Pose(Double.NaN,0.0);fail() } catch(_:IllegalArgumentException) {}
    }
    @Test fun externallyMovedCameraDoesNotJumpBackToAnOldView()=runBlocking {
        val f=Fake();assertFalse(PetCameraSearch.run(f,center,PetCameraSearch.Pose(-.8,-.8)));assertTrue(f.moves.isEmpty());assertEquals(1,f.stops)
    }
    @Test fun stopHookCancelsOnlyTheCurrentMotionOwner() {
        val camera="search-test";var cancelled=false
        assertTrue(cc.bridgit.app.lancamera.CameraMotion.acquire(listOf(camera),"search-token") {cancelled=true})
        assertFalse(cc.bridgit.app.lancamera.CameraMotion.acquire(listOf(camera),"other"))
        cc.bridgit.app.lancamera.CameraMotion.cancel(camera);assertTrue(cancelled)
        cc.bridgit.app.lancamera.CameraMotion.release("search-token")
        assertTrue(cc.bridgit.app.lancamera.CameraMotion.acquire(listOf(camera),"other"))
        cc.bridgit.app.lancamera.CameraMotion.release("other")
    }
}
