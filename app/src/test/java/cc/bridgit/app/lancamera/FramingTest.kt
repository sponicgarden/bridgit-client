package cc.bridgit.app.lancamera

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class FramingTest {
    private fun o(x: String="center",y: String="center",confidence: Double=.95,visibility: String="whole",size: String="suitable") =
        FramingRules.Observation(.9,.53,visibility to .95,x to confidence,y to confidence,size to .95)
    private class Fake(val answers: MutableList<FramingRules.Observation>,val shifts: MutableList<Int?> = mutableListOf(-1,-1,-1)) : FramingEngine.Driver {
        val moves=mutableListOf<Pair<Double,Double>>()
        val snapshots=mutableListOf<String>()
        var stops=0; var assessments=0; var stopResult=true; var now=0L; var allowed=true; var cancelOnMove=false
        var revokeAfter=Int.MAX_VALUE; var supports=true; var last=FramingRules.Observation(null,null,null,null,null,null)
        override suspend fun authorize() { if(!allowed) throw CancellationException() }
        override suspend fun snapshot(camera:String):ByteArray { snapshots.add(camera); if(snapshots.size>=revokeAfter) allowed=false; return byteArrayOf(snapshots.size.toByte()) }
        override suspend fun assess(frame:ByteArray):FramingRules.Observation { assessments++; if(answers.isNotEmpty()) last=answers.removeAt(0); return last }
        override suspend fun displacement(before:ByteArray,after:ByteArray,axis:String):Int? = if(shifts.isEmpty()) null else shifts.removeAt(0)
        override suspend fun controls(camera:String)=CameraControls("p",supports,true,false,false,-1.0,1.0,-1.0,1.0)
        override suspend fun move(camera:String,pan:Double,tilt:Double) { moves.add(pan to tilt); if(cancelOnMove) throw CancellationException() }
        override suspend fun settle(ms:Long) { now+=ms*1_000_000 }
        override suspend fun stop(camera:String):Boolean { stops++;return stopResult }
    }
    private fun run(f:Fake,l:FramingRules.Limits=FramingRules.Limits(),calibrate:Boolean=true,cameras:List<String> = listOf("a")) = runBlocking {
        FramingEngine(f,l,cameras,calibrate,clock={f.now}).run()
    }
    @Test fun wholeCenteredTargetHoldsWithoutMotionOrPatrol() {
        val f=Fake(mutableListOf(o(),o()));val s=run(f)
        assertEquals("held",s.state);assertFalse(s.framingLimited);assertEquals(2,f.assessments);assertTrue(f.moves.isEmpty());assertEquals(0,f.stops)
        assertEquals(.53,s.observation!!.identity!!,0.0) // appearance does not prove identity
    }
    @Test fun liveFinalConfidenceCannotBecomeConfirmedHeld() {
        val f=Fake(mutableListOf(o(confidence=.54)));val s=run(f)
        assertEquals("position_confidence",s.reason);assertTrue(f.moves.isEmpty());assertEquals(3,f.assessments)
    }
    @Test fun centeredButTooSmallHasExplicitFramingLimitWithoutFakeZoom() {
        val s=run(Fake(mutableListOf(o(size="small"),o(size="small"))))
        assertEquals("held",s.state);assertTrue(s.framingLimited)
    }
    @Test fun probesDiscoverReversedPanSignBeforeCorrection() {
        val f=Fake(mutableListOf(o("right"),o("right"),o(),o()),mutableListOf(1,-1,-1))
        val s=run(f)
        assertEquals("held",s.state);assertEquals(-1,s.panSign);assertEquals(1,s.tiltSign)
        assertEquals(-.01,f.moves[2].first,0.0);assertEquals(1,f.stops);assertEquals(true,s.stopConfirmed)
    }
    @Test fun unexpectedSceneMotionStopsInsteadOfRepeatingWrongDirection() {
        val f=Fake(mutableListOf(o("right"),o("right")),mutableListOf(-1,-1,1));val s=run(f)
        assertEquals("motion_unverified",s.reason);assertEquals(3,f.moves.size);assertEquals(1,f.stops)
    }
    @Test fun moveLimitIncludesCalibrationAndReservesFailedCommands() {
        val f=Fake(mutableListOf(o("right")));val s=run(f,FramingRules.Limits(moves=1))
        assertEquals("move_limit",s.reason);assertEquals(1,s.moves);assertEquals(1,f.moves.size);assertEquals(1,f.stops)
    }
    @Test fun noMoveOnceModelBudgetCannotVerifyIt() {
        val f=Fake(mutableListOf(o("right")));val s=run(f,FramingRules.Limits(checks=2))
        assertEquals("check_limit",s.reason);assertEquals(1,f.moves.size)
    }
    @Test fun cancelDuringMoveStillRequestsStop() {
        val f=Fake(mutableListOf(o("right")));f.cancelOnMove=true
        try { run(f);fail() } catch(_:CancellationException) { }
        assertEquals(1,f.stops);assertEquals(1,f.moves.size)
    }
    @Test fun stopFailureCannotReportSuccessfulHold() {
        val f=Fake(mutableListOf(o("right"),o(),o()));f.stopResult=false
        val s=run(f);assertEquals("failed",s.state);assertEquals("stop_unconfirmed",s.reason);assertEquals(false,s.stopConfirmed)
    }
    @Test fun absentAndAmbiguousViewsDoNotBlockOtherSelectedCameras() {
        val f=Fake(mutableListOf(o(visibility="occluded"),o(),o()))
        val s=run(f,cameras=listOf("a","b"));assertEquals("held",s.state);assertEquals("b",s.camera);assertEquals(listOf("a","b","b"),f.snapshots)
    }
    @Test fun unknownCalibrationAndOcclusionDoNotCauseBlindMoves() {
        val f=Fake(mutableListOf(o("right")));assertEquals("calibration_required",run(f,calibrate=false).reason);assertTrue(f.moves.isEmpty())
        val g=Fake(mutableListOf(o(visibility="occluded")));assertEquals("target_unclear",run(g).reason);assertTrue(g.moves.isEmpty())
    }
    @Test fun rejectsInvalidNumericConfigurationAndMalformedObservations() {
        for(body in listOf("{\"max_moves\":1.5}","{\"max_moves\":25}","{\"min_confidence\":0.2}","{\"max_step\":1}")) {
            try { FramingRules.Limits.parse(JSONObject(body)); fail(body) } catch(_:IllegalArgumentException) { }
        }
        assertNull(FramingRules.direction("left" to Double.NaN,"left","right",.8))
        assertNull(FramingRules.direction("invented" to .99,"left","right",.8))
        val answer=cc.bridgit.app.judgment.Jev.parse(200,"{\"answers\":{\"similarity\":{\"type\":\"noul\",\"noul\":2}}}")
        assertFalse(FramingRules.similar(FramingRules.observation(answer),FramingRules.Limits()))
    }
    @Test fun relativeControlsRequireFiniteNormalizedProfileSpace() {
        fun parse(range:String,uri:String=CameraControls.GENERIC):CameraControls {
            val xml="<r xmlns:t='${Onvif.SCHEMA}'><t:RelativePanTiltTranslationSpace><t:URI>$uri</t:URI><t:XRange><t:Min>$range</t:Min><t:Max>1</t:Max></t:XRange><t:YRange><t:Min>-1</t:Min><t:Max>1</t:Max></t:YRange></t:RelativePanTiltTranslationSpace></r>"
            val f=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            return CameraControls.parse("p",f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())))
        }
        assertTrue(parse("-1").permits(.01,-.01));assertFalse(parse("NaN").relative);assertFalse(parse("-1","unknown:space").relative)
        assertFalse(parse("-1").permits(2.0,0.0));assertFalse(parse("-1").relativeZoom!!)
    }
    @Test fun savedFoodViewsRequireNormalizedFiniteAbsoluteBounds() {
        fun parse(uri:String=CameraControls.ABSOLUTE,minimum:String="-1"):CameraControls {
            val xml="<r xmlns:t='${Onvif.SCHEMA}'><t:AbsolutePanTiltPositionSpace><t:URI>$uri</t:URI><t:XRange><t:Min>$minimum</t:Min><t:Max>1</t:Max></t:XRange><t:YRange><t:Min>-1</t:Min><t:Max>1</t:Max></t:YRange></t:AbsolutePanTiltPositionSpace></r>"
            return CameraControls.parse("p",DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())))
        }
        assertTrue(parse().absoluteSpaceUnambiguous);assertTrue(parse().permitsAbsolute(.7,-.4));assertFalse(parse().permitsAbsolute(Double.NaN,0.0));assertFalse(parse().permitsAbsolute(1.1,0.0))
        assertFalse(parse("urn:vendor:degrees").permitsAbsolute(0.0,0.0));assertFalse(parse(minimum="NaN").permitsAbsolute(0.0,0.0));assertFalse(parse(minimum="-360").permitsAbsolute(0.0,0.0))
    }
    @Test fun positionAcceptsActualTapoMissingSpaceOnlyWhenUnambiguous() {
        fun doc(attrs:String)=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }.newDocumentBuilder().parse(ByteArrayInputStream("<r xmlns:t='${Onvif.SCHEMA}'><t:PanTilt $attrs/></r>".toByteArray()))
        val c=CameraControls("p",true,true,false,false,absoluteBounds=listOf(-1.0,1.0,-1.0,1.0),absoluteSpaceUnambiguous=true)
        assertEquals(Onvif.Position(-.3,-.1),Onvif.parsePosition(doc("x='-.3' y='-.1'"),c))
        for(attrs in listOf("x='NaN' y='0'","x='2' y='0'","space='urn:degrees' x='0' y='0'")) {
            try { Onvif.parsePosition(doc(attrs),c);fail(attrs) } catch(_:CameraFailure) {}
        }
        try { Onvif.parsePosition(doc("x='0' y='0'"),c.copy(absoluteSpaceUnambiguous=false));fail("Ambiguous missing space") } catch(_:CameraFailure) {}
    }
    @Test fun confidenceGapBreaksConsecutiveCenteredConfirmation() {
        val f=Fake(mutableListOf(o(),o(confidence=.54),o(),o()))
        assertEquals("held",run(f).state);assertEquals(4,f.assessments)
    }
    @Test fun verifiedProgressInsideOneCoarseBinDoesNotAbort() {
        val f=Fake(mutableListOf(o("right"),o("right"),o("right"),o("right"),o(),o()),mutableListOf(-1,-1,-1,-1,-1))
        assertEquals("held",run(f,FramingRules.Limits(moves=6,checks=20)).state)
        assertEquals(5,f.moves.size)
    }
    @Test fun revokedAccessAfterCapturePreventsModelCallAndMovement() {
        val f=Fake(mutableListOf(o("right")));f.revokeAfter=1
        try { run(f);fail() } catch(_:CancellationException) { }
        assertEquals(0,f.assessments);assertTrue(f.moves.isEmpty())
    }
    @Test fun wallTimeBudgetStopsBeforeAnotherCapture() {
        val f=Fake(mutableListOf(o("right")));f.now=0
        val engine=FramingEngine(f,FramingRules.Limits(timeoutS=15),listOf("a"),true,clock={f.now})
        f.now=16_000_000_000
        val s=runBlocking { engine.run() }
        assertEquals("time_limit",s.reason);assertTrue(f.snapshots.isEmpty())
    }
    @Test fun reversalConfirmationCannotMoveAfterTargetLoss() {
        val f=Fake(mutableListOf(o("right"),o("right"),o("left"),o("left",visibility="absent")),mutableListOf(-1,-1,-1))
        val s=run(f,FramingRules.Limits(checks=20))
        assertEquals("target_lost_or_unclear",s.reason);assertEquals(3,f.moves.size)
    }
    @Test fun cameraLeaseSerializesOtherMotorClientsAndReleasesByOwner() {
        assertTrue(CameraMotion.acquire(listOf("unit-camera"),"operation"))
        try { assertFalse(CameraMotion.acquire(listOf("unit-camera"),"other"));CameraMotion.check("unit-camera","operation")
            try { CameraMotion.check("unit-camera",null);fail() } catch(_:CameraFailure) { }
            CameraMotion.release("other");try { CameraMotion.check("unit-camera",null);fail() } catch(_:CameraFailure) { }
        } finally { CameraMotion.release("operation") }
        CameraMotion.check("unit-camera",null)
    }
}
