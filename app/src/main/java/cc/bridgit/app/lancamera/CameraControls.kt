package cc.bridgit.app.lancamera

import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Per-profile evidence, not a claim that advertised controls have been tested. */
data class CameraControls(val profile: String, val relative: Boolean, val absolute: Boolean?, val relativeZoom: Boolean?,
    val absoluteZoom: Boolean?, val xMin: Double = 0.0, val xMax: Double = 0.0, val yMin: Double = 0.0, val yMax: Double = 0.0, val relativeAdvertised: Boolean = relative, val absoluteBounds: List<Double>? = null, val absoluteSpaceUnambiguous:Boolean=false) {
    val positionSpace:PtzSpace? get()=absoluteBounds?.let { PtzSpace(it[0],it[1],it[2],it[3]) }
    fun permits(pan: Double, tilt: Double) = relative && pan.isFinite() && tilt.isFinite() && pan in xMin..xMax && tilt in yMin..yMax
    fun permitsAbsolute(x:Double,y:Double) = absoluteBounds?.let { x.isFinite() && y.isFinite() && x in it[0]..it[1] && y in it[2]..it[3] } == true
    fun json() = JSONObject().put("profile",profile).put("checked_at",java.time.Instant.now().toString())
        .put("relative_pan_tilt",JSONObject().put("advertised",relativeAdvertised).put("implemented",relative).put("space",GENERIC)
            .put("x",org.json.JSONArray(listOf(xMin,xMax))).put("y",org.json.JSONArray(listOf(yMin,yMax))))
        .put("absolute_pan_tilt",JSONObject().put("advertised",absolute ?: JSONObject.NULL).put("implemented",absoluteBounds!=null).put("scope","saved food-bowl view"))
        .put("relative_zoom",JSONObject().put("advertised",relativeZoom ?: JSONObject.NULL).put("implemented",false))
        .put("absolute_zoom",JSONObject().put("advertised",absoluteZoom ?: JSONObject.NULL).put("implemented",false))
        .put("optical_zoom",JSONObject().put("verified",false).put("implemented",false))
        .put("digital_crop",JSONObject().put("implemented",false)).put("stop_verified",false)
    companion object {
        const val ABSOLUTE = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace"
        const val GENERIC = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationGenericSpace"
        fun parse(profile: String, doc: Document): CameraControls {
            fun has(name: String) = doc.getElementsByTagNameNS(Onvif.SCHEMA,name).length > 0
            val spaces = doc.getElementsByTagNameNS(Onvif.SCHEMA,"RelativePanTiltTranslationSpace")
            val space = (0 until spaces.length).mapNotNull { spaces.item(it) as? Element }.firstOrNull {
                it.getElementsByTagNameNS(Onvif.SCHEMA,"URI").item(0)?.textContent == GENERIC
            }
            fun range(axis: String, bound: String): Double? = (space?.getElementsByTagNameNS(Onvif.SCHEMA,axis)?.item(0) as? Element)
                ?.getElementsByTagNameNS(Onvif.SCHEMA,bound)?.item(0)?.textContent?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val a=range("XRange","Min"); val b=range("XRange","Max"); val c=range("YRange","Min"); val d=range("YRange","Max")
            val usable = a != null && b != null && c != null && d != null && a < 0 && b > 0 && c < 0 && d > 0
            val abs=doc.getElementsByTagNameNS(Onvif.SCHEMA,"AbsolutePanTiltPositionSpace")
            val normalized=(0 until abs.length).mapNotNull { abs.item(it) as? Element }.firstOrNull { it.getElementsByTagNameNS(Onvif.SCHEMA,"URI").item(0)?.textContent==ABSOLUTE }
            fun bound(axis:String,key:String)=(normalized?.getElementsByTagNameNS(Onvif.SCHEMA,axis)?.item(0) as? Element)?.getElementsByTagNameNS(Onvif.SCHEMA,key)?.item(0)?.textContent?.toDoubleOrNull()
            val bounds=listOf(bound("XRange","Min"),bound("XRange","Max"),bound("YRange","Min"),bound("YRange","Max"))
            val absoluteBounds=if(bounds.all { it!=null && it.isFinite() && it in -1.0..1.0 } && bounds[0]!!<bounds[1]!! && bounds[2]!!<bounds[3]!!) bounds.map { it!! } else null
            return CameraControls(profile,usable,has("AbsolutePanTiltPositionSpace"),has("RelativeZoomTranslationSpace"),has("AbsoluteZoomPositionSpace"),a?:0.0,b?:0.0,c?:0.0,d?:0.0,has("RelativePanTiltTranslationSpace"),absoluteBounds,abs.length==1 && normalized!=null)
        }
    }
}
