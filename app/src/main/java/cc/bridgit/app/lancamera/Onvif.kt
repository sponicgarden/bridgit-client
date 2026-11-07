package cc.bridgit.app.lancamera

import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** Only fixed ONVIF operations against the owner-enrolled LAN host. No cloud password or redirects. */
class Onvif internal constructor(private val camera: LanCamera, private val sockets:javax.net.SocketFactory, private val motionToken: String? = null, private val authorized: () -> Unit = {}) {
    constructor(camera:LanCamera,context:android.content.Context,motionToken:String?=null,authorized:()->Unit={}) : this(camera,LanNetwork.sockets(context,camera.host),motionToken,authorized)
    private val POSITION = CameraControls.ABSOLUTE
    data class Info(val model: String, val firmware: String, val ptz: Boolean, val profile: String, val inventory: org.json.JSONObject = org.json.JSONObject())
    fun info(): Info {
        val device = call(DEVICE, "GetDeviceInformation")
        val profiles = call(MEDIA, "GetProfiles")
        val profileNodes = profiles.getElementsByTagNameNS(MEDIA, "Profiles")
        if (profileNodes.length == 0) throw CameraFailure("Camera did not return a video profile.")
        val profile = (profileNodes.item(0) as org.w3c.dom.Element).getAttribute("token")
        val nodes = runCatching { call(PTZ, "GetNodes") }.getOrNull()
        return Info(device.text("Model"), device.text("FirmwareVersion"),
            nodes?.getElementsByTagNameNS(SCHEMA, "RelativePanTiltTranslationSpace")?.length?.let { it > 0 } == true, profile, org.json.JSONObject()
                .put("video_profiles", profileNodes.length).put("ptz_query_succeeded",nodes!=null)
                .put("audio_source_advertised", profiles.getElementsByTagNameNS(SCHEMA,"AudioSourceConfiguration").length>0)
                .put("audio_decoder_advertised", profiles.getElementsByTagNameNS(SCHEMA,"AudioDecoderConfiguration").length>0)
                .put("audio_output_advertised", profiles.getElementsByTagNameNS(SCHEMA,"AudioOutputConfiguration").length>0)
                .put("metadata_advertised", profiles.getElementsByTagNameNS(SCHEMA,"MetadataConfiguration").length>0)
                .put("relative_zoom_advertised", nodes?.getElementsByTagNameNS(SCHEMA,"RelativeZoomTranslationSpace")?.length?.let { it>0 } ?: org.json.JSONObject.NULL))
    }
    fun controls(profile: String): CameraControls {
        val profiles = call(MEDIA, "GetProfiles").getElementsByTagNameNS(MEDIA,"Profiles")
        val selected = (0 until profiles.length).mapNotNull { profiles.item(it) as? org.w3c.dom.Element }
            .firstOrNull { it.getAttribute("token") == profile } ?: throw CameraFailure("Selected video profile disappeared.")
        val config = selected.getElementsByTagNameNS(SCHEMA,"PTZConfiguration").item(0) as? org.w3c.dom.Element
            ?: return CameraControls(profile,false,null,null,null)
        val token = config.getAttribute("token").takeIf { it.isNotBlank() } ?: throw CameraFailure("Camera has no PTZ configuration token.")
        return CameraControls.parse(profile,call(PTZ,"GetConfigurationOptions","<t:ConfigurationToken>${xml(token)}</t:ConfigurationToken>"))
    }
    fun move(profile: String, pan: Double, tilt: Double) {
        CameraMotion.command(camera.id, motionToken) {
        require(pan.isFinite() && tilt.isFinite() && pan in -0.1..0.1 && tilt in -0.1..0.1)
        if (!controls(profile).permits(pan,tilt)) throw CameraFailure("This profile has no supported normalized relative pan/tilt space.")
        call(PTZ, "RelativeMove", "<t:ProfileToken>${xml(profile)}</t:ProfileToken><t:Translation><tt:PanTilt space=\"${CameraControls.GENERIC}\" x=\"$pan\" y=\"$tilt\"/></t:Translation>")
    }
    }
    fun searchPosition(profile:String):PtzPoint {
        val p=position(profile) ?: throw CameraFailure("Camera position is unavailable.")
        return PtzPoint(p.pan,p.tilt)
    }
    fun moveTo(profile:String, space:PtzSpace, point:PtzPoint) {
        CameraMotion.command(camera.id,motionToken) {
            require(space.contains(point))
            call(PTZ,"AbsoluteMove","<t:ProfileToken>${xml(profile)}</t:ProfileToken><t:Position><tt:PanTilt space=\"${PtzSpace.URI}\" x=\"${point.x}\" y=\"${point.y}\"/></t:Position>")
        }
    }
    data class Position(val x:Double,val y:Double)
    /** Saved food views accept only the advertised normalized absolute position space. */
    fun feedingPosition(profile:String):Position {
        val controls=controls(profile)
        return parsePosition(call(PTZ,"GetStatus","<t:ProfileToken>${xml(profile)}</t:ProfileToken>"),controls)
    }

    fun aimAt(profile:String,position:Position) {
        CameraMotion.command(camera.id,motionToken) {
            if(!controls(profile).permitsAbsolute(position.x,position.y))throw CameraFailure("Saved food view is outside this profile's supported range.")
            call(PTZ,"AbsoluteMove","<t:ProfileToken>${xml(profile)}</t:ProfileToken><t:Position><tt:PanTilt space=\"${CameraControls.ABSOLUTE}\" x=\"${position.x}\" y=\"${position.y}\"/></t:Position><t:Speed><tt:PanTilt x=\"0.25\" y=\"0.25\"/></t:Speed>")
        }
    }
    /** Measured normalized pose; unknown/custom coordinate spaces are not safe search anchors. */
    fun position(profile: String): cc.bridgit.app.dog.PetCameraSearch.Pose? {
        val nodes=call(PTZ,"GetStatus","<t:ProfileToken>${xml(profile)}</t:ProfileToken>").getElementsByTagNameNS(SCHEMA,"PanTilt")
        val p=nodes.item(0) as? org.w3c.dom.Element ?: return null
        val default=if(p.getAttribute("space").isBlank()) configuration(profile).getElementsByTagNameNS(SCHEMA,"DefaultAbsolutePantTiltPositionSpace").item(0)?.textContent else null
        val parsed=OnvifPosition.parse(p.getAttribute("x"),p.getAttribute("y"),p.getAttribute("space"),default)
        if(parsed!=null)return parsed
        // Some Tapo firmware omits both space and default. Accept only one advertised normalized space.
        if(p.getAttribute("space").isBlank() && default.isNullOrBlank()) {
            return try { feedingPosition(profile).let { cc.bridgit.app.dog.PetCameraSearch.Pose(it.x,it.y) } } catch(_:CameraFailure) { null }
        }
        return null
    }
    fun positionSupported(profile: String): Boolean {
        val spaces=call(PTZ,"GetConfigurationOptions",configurationToken(profile)).getElementsByTagNameNS(SCHEMA,"AbsolutePanTiltPositionSpace")
        return (0 until spaces.length).any { i ->
            val space=spaces.item(i) as? org.w3c.dom.Element ?: return@any false
            fun bound(axis: String,key: String)=space.getElementsByTagNameNS(SCHEMA,axis).item(0)?.let { it as? org.w3c.dom.Element }
                ?.getElementsByTagNameNS(SCHEMA,key)?.item(0)?.textContent?.toDoubleOrNull()
            space.getElementsByTagNameNS(SCHEMA,"URI").item(0)?.textContent==POSITION &&
                bound("XRange","Min")==-1.0 && bound("XRange","Max")==1.0 && bound("YRange","Min")==-1.0 && bound("YRange","Max")==1.0
        }
    }
    private fun configuration(profile: String): org.w3c.dom.Element {
        val profiles=call(MEDIA,"GetProfiles").getElementsByTagNameNS(MEDIA,"Profiles")
        val selected=(0 until profiles.length).mapNotNull { profiles.item(it) as? org.w3c.dom.Element }.firstOrNull { it.getAttribute("token")==profile }
            ?: throw CameraFailure("Video profile disappeared.")
        val config=selected.getElementsByTagNameNS(SCHEMA,"PTZConfiguration").item(0) as? org.w3c.dom.Element ?: throw CameraFailure("No position controls.")
        return config
    }
    private fun configurationToken(profile: String) = "<t:ConfigurationToken>${xml(configuration(profile).getAttribute("token"))}</t:ConfigurationToken>"
    fun goTo(profile: String, pose: cc.bridgit.app.dog.PetCameraSearch.Pose) {
        CameraMotion.command(camera.id,motionToken) {
            call(PTZ,"AbsoluteMove","<t:ProfileToken>${xml(profile)}</t:ProfileToken><t:Position><tt:PanTilt space=\"$POSITION\" x=\"${pose.pan}\" y=\"${pose.tilt}\"/></t:Position>")
        }
    }
    fun stop(profile: String) { if(motionToken==null) CameraMotion.cancel(camera.id); call(PTZ, "Stop", "<t:ProfileToken>${xml(profile)}</t:ProfileToken><t:PanTilt>true</t:PanTilt>") }
    fun call(namespace: String, operation: String, body: String = ""): Document {
        authorized()
        val created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val nonce = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(nonce + created.toByteArray() + camera.password.toByteArray()))
        val token = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0"
        val soap = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:t="$namespace" xmlns:tt="$SCHEMA"><s:Header><wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"><wsse:UsernameToken><wsse:Username>${xml(camera.username)}</wsse:Username><wsse:Password Type="$token#PasswordDigest">$digest</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${Base64.getEncoder().encodeToString(nonce)}</wsse:Nonce><wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security></s:Header><s:Body><t:$operation>$body</t:$operation></s:Body></s:Envelope>"""
        try {
            val response = cc.bridgit.app.core.SafeHttp.cameraSoap(camera.host,
                "application/soap+xml; charset=utf-8; action=\"$namespace/$operation\"", soap.toByteArray(), sockets)
            if (response.status != 200) throw CameraFailure("ONVIF request failed. Check the local Camera Account and camera clock.")
            val bytes = response.bytes ?: throw CameraFailure("Empty camera response.")
            // Bounded UTF-8 XML only; reject declarations before DOM can process entities.
            val xmlText = bytes.toString(Charsets.UTF_8)
            if (bytes.any { it == 0.toByte() } || "<!DOCTYPE" in xmlText || "<!ENTITY" in xmlText)
                throw CameraFailure("Camera returned an unsafe XML document.")
            val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            val builder = f.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are disabled") }
            }
            val doc = builder.parse(ByteArrayInputStream(bytes))
            if (doc.getElementsByTagNameNS("http://www.w3.org/2003/05/soap-envelope", "Fault").length > 0)
                throw CameraFailure("This camera firmware does not support that ONVIF operation.")
            return doc
        } catch (e: CameraFailure) { throw e }
        catch (e: Exception) {
            if (cc.bridgit.app.BuildConfig.DEBUG) android.util.Log.d("LanCameraTest", "ONVIF: ${e.javaClass.simpleName}: ${e.message.orEmpty().replace(camera.password, "[redacted]").replace(camera.username, "[redacted]")}")
            throw CameraFailure("Cannot reach the camera. Check Wi-Fi, its address and local Camera Account.") }

    }
    companion object {
        internal fun parsePosition(doc:Document,controls:CameraControls):Position {
            val node=doc.getElementsByTagNameNS(SCHEMA,"PanTilt").item(0) as? org.w3c.dom.Element
                ?: throw CameraFailure("Camera did not report its position. Food view was not saved.")
            val space=node.getAttribute("space")
            if(space.isNotBlank() && space!=CameraControls.ABSOLUTE || space.isBlank() && !controls.absoluteSpaceUnambiguous)
                throw CameraFailure("Camera position space is not supported for a saved food view.")
            val x=node.getAttribute("x").toDoubleOrNull();val y=node.getAttribute("y").toDoubleOrNull()
            if(x==null || y==null || !controls.permitsAbsolute(x,y))throw CameraFailure("Camera returned an invalid absolute position.")
            return Position(x,y)
        }
        const val DEVICE = "http://www.onvif.org/ver10/device/wsdl"
        const val MEDIA = "http://www.onvif.org/ver10/media/wsdl"
        const val PTZ = "http://www.onvif.org/ver20/ptz/wsdl"
        const val SCHEMA = "http://www.onvif.org/ver10/schema"
        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        private fun Document.text(name: String) = getElementsByTagNameNS(DEVICE, name).item(0)?.textContent.orEmpty().take(120)
    }
}
