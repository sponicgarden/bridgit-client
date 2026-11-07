package cc.bridgit.app.lancamera
import org.junit.Assert.*
import org.junit.Test
class OnvifPositionTest {
    @Test fun tapoStatusWithoutSpaceUsesVerifiedProfileDefault() {
        val p=OnvifPosition.parse("-0.067946","-0.691478","",OnvifPosition.GENERIC)!!
        assertEquals(-.067946,p.pan,0.000001);assertEquals(-.691478,p.tilt,0.000001)
    }
    @Test fun unknownSpacesMissingDefaultsAndInvalidPositionsAreNotSearchAnchors() {
        assertNull(OnvifPosition.parse("0","0","",null))
        assertNull(OnvifPosition.parse("0","0","custom",OnvifPosition.GENERIC))
        for(x in listOf("NaN","Infinity","2","invalid"))assertNull(OnvifPosition.parse(x,"0",OnvifPosition.GENERIC,null))
    }
}
