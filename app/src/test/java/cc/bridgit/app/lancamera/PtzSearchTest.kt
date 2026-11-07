package cc.bridgit.app.lancamera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class PtzSearchTest {
    private class Fake : PtzSearch.Driver {
        var allowed=true; var stuck=false; var revokeAfterPhoto=false
        var current=PtzPoint(.2,-.3);val moves=mutableListOf<PtzPoint>();val photos=mutableListOf<Pair<String,PtzPoint>>();var stops=0
        override suspend fun authorize(){if(!allowed)throw CancellationException()}
        override suspend fun position()=current
        override suspend fun move(point:PtzPoint){moves.add(point);if(!stuck)current=point}
        override suspend fun settle(ms:Long){}
        override suspend fun capture(view:String){photos.add(view to current);if(revokeAfterPhoto)allowed=false}
        override suspend fun stop(){stops++}
    }
    private val space=PtzSpace(-1.0,1.0,-1.0,1.0)
    @Test fun capturesFiveDistinctVerifiedViewsThenRestoresOriginal()=runBlocking {
        val f=Fake();val original=f.current
        PtzSearch(space,f).run(PtzSpace.VIEWS)
        assertEquals(listOf("center","top","left","right","bottom"),f.photos.map { it.first })
        assertEquals(listOf(PtzPoint(0.0,0.0),PtzPoint(0.0,1.0),PtzPoint(-1.0,0.0),PtzPoint(1.0,0.0),PtzPoint(0.0,-1.0)),f.photos.map { it.second })
        assertEquals(original,f.current);assertEquals(1,f.stops)
    }
    @Test fun neverPhotographsAnUnreachedPose()=runBlocking {
        val f=Fake();f.stuck=true
        try{PtzSearch(space,f).run(PtzSpace.VIEWS);fail("Must time out")}catch(_:PtzSearch.PositionUnavailable){}
        assertTrue(f.photos.isEmpty());assertEquals(1,f.stops)
    }
    @Test fun revokedConsentStopsWithoutMovingOrPhotographingAgain()=runBlocking {
        val f=Fake();f.revokeAfterPhoto=true
        try{PtzSearch(space,f).run(PtzSpace.VIEWS);fail("Must cancel")}catch(_:CancellationException){}
        assertEquals(1,f.photos.size);assertEquals(1,f.moves.size);assertEquals(1,f.stops)
    }
    @Test fun advertisedRangesDetermineCenterAndLimits() {
        val s=PtzSpace(10.0,30.0,-5.0,15.0)
        assertEquals(PtzPoint(20.0,5.0),s.target("center"));assertEquals(PtzPoint(20.0,15.0),s.target("top"))
        assertEquals(PtzPoint(10.0,5.0),s.target("left"));assertEquals(PtzPoint(30.0,5.0),s.target("right"));assertEquals(PtzPoint(20.0,-5.0),s.target("bottom"))
        assertFalse(s.contains(PtzPoint(Double.NaN,0.0)))
    }
    @Test fun relativeOnlyAndMalformedAbsoluteSpacesAreRejected() {
        fun parse(body:String)=CameraControls.parse("p",DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream("<r xmlns:t=\"${Onvif.SCHEMA}\">$body</r>".toByteArray()))).positionSpace
        assertNull(parse("<t:RelativePanTiltTranslationSpace/>"))
        val xml="<t:AbsolutePanTiltPositionSpace><t:URI>${PtzSpace.URI}</t:URI><t:XRange><t:Min>-1</t:Min><t:Max>1</t:Max></t:XRange><t:YRange><t:Min>-1</t:Min><t:Max>1</t:Max></t:YRange></t:AbsolutePanTiltPositionSpace>"
        assertEquals(space,parse(xml));assertNull(parse(xml.replace("<t:Max>1</t:Max>","<t:Max>NaN</t:Max>")))
    }
}
