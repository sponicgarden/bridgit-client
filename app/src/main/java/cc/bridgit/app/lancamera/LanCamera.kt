package cc.bridgit.app.lancamera

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Owner-enrolled local cameras. The entire record is encrypted, excluded from Android backup. */
class LanCameras(context: Context) {
    private val prefs = context.getSharedPreferences("lan_cameras", Context.MODE_PRIVATE)
    @Synchronized fun all(): List<LanCamera> {
        val data = prefs.getString("data", null) ?: return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        val array = JSONArray(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))))
        return (0 until array.length()).map { LanCamera.fromJson(array.getJSONObject(it)) }
    }
    @Synchronized fun save(camera: LanCamera) { write(all().filter { it.id != camera.id } + camera) }
    @Synchronized fun replace(expected: LanCamera, updated: LanCamera): Boolean {
        val current=all(); if(current.firstOrNull { it.id==expected.id }?.json()?.toString()!=expected.json().toString())return false
        write(current.map { if(it.id==expected.id)updated else it });return true
    }
    @Synchronized fun remove(id: String) { write(all().filter { it.id != id }) }
    fun get(id: String) = all().firstOrNull { it.id == id }
        ?: throw CameraFailure("Camera is no longer configured. Choose a camera in Pet monitor.")
    private fun write(cameras: List<LanCamera>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(cipher.doFinal(JSONArray(cameras.map { it.json() }).toString().toByteArray()), Base64.NO_WRAP)).commit())
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bridgit_lan_cameras", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bridgit_lan_cameras", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

/** Deliberately no data-class toString: a camera contains credentials. */
class LanCamera(val id: String, val name: String, val host: String, val username: String, val password: String,
    val model: String? = null, val firmware: String? = null, val relativePtz: Boolean? = null, val verifiedAt: Long? = null, val capabilityReport: JSONObject? = null, val talkPassword: String = "") {
    init {
        require(id.matches(Regex("lan:[a-zA-Z0-9-]{1,60}")))
        require(name.isNotBlank() && name.length <= 60)
        require(localHost(host)) { "Enter a private local IPv4 address, such as 192.168.1.85." }
        require(talkPassword.length <= 256)
        require(username.isNotBlank() && username.length <= 64 && password.isNotEmpty() && password.length <= 128)
    }
    fun rtsp(main: Boolean = false): Uri = Uri.parse("rtsp://${Uri.encode(username)}:${Uri.encode(password)}@$host:554/stream${if (main) 1 else 2}")
    internal fun json() = publicJson().put("username", username).put("password", password).put("talk_password", talkPassword)
    fun verified(info: Onvif.Info) = LanCamera(id, name, host, username, password, info.model, info.firmware, if(info.inventory.opt("ptz_query_succeeded")==false)null else info.ptz, System.currentTimeMillis(), capabilityReport, talkPassword)
    fun assessed(report: JSONObject, cloudPassword: String = talkPassword) = LanCamera(id,name,host,username,password,model,firmware,relativePtz,verifiedAt,report,cloudPassword)
    fun publicJson() = JSONObject().put("credentials_configured",username.isNotBlank() && password.isNotEmpty()).put("id", id).put("name", name).put("host", host)
        .put("model", model ?: JSONObject.NULL).put("firmware", firmware ?: JSONObject.NULL)
        .put("capabilities", capabilityReport ?: JSONObject().put("state","not_checked").put("probe_path","/v1/lan-cameras/probe"))
        .put("talk_credentials_configured",talkPassword.isNotEmpty())
        .put("relative_ptz", relativePtz ?: JSONObject.NULL).put("verified_at", verifiedAt ?: JSONObject.NULL)
    override fun toString() = "LanCamera($id)"
    companion object {
        fun localHost(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
            val p = parts.map { it.toInt() }
            return p.all { it in 0..255 } && p[3] in 1..254 &&
                (p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168))
        }
        fun fromJson(o: JSONObject) = LanCamera(o.getString("id"), o.getString("name"), o.getString("host"), o.getString("username"), o.getString("password"),
            o.optString("model").takeIf { !o.isNull("model") && it.isNotEmpty() },
            o.optString("firmware").takeIf { !o.isNull("firmware") && it.isNotEmpty() },
            if (o.isNull("relative_ptz")) null else o.optBoolean("relative_ptz"),
            if (o.isNull("verified_at")) null else o.optLong("verified_at"), o.optJSONObject("capabilities"), o.optString("talk_password"))
    }
}
class CameraFailure(message: String) : Exception(message)
