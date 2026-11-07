package cc.bridgit.app.lan

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32

/** Read-only TP-Link discovery, interoperable with python-kasa's TDP v2 probe.
 * https://github.com/python-kasa/python-kasa/blob/master/kasa/discover.py
 * Only public model/type hints are used; encrypted account/device payloads are ignored.
 */
object TpLinkDiscovery {
    data class Hint(val name: String, val service: String)
    fun query(): ByteArray {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(publicKey) + "\n-----END PUBLIC KEY-----\n"
        val payload = JSONObject().put("params", JSONObject().put("rsa_key", pem)).toString().toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(16 + payload.size).put(2.toByte()).put(0.toByte())
            .putShort(1.toShort()).putShort(payload.size.toShort()).put(17.toByte()).put(0.toByte())
            .putInt(SecureRandom().nextInt()).putInt(0x5A6B7C8D).put(payload).array()
        ByteBuffer.wrap(bytes).putInt(12, CRC32().apply { update(bytes) }.value.toInt())
        return bytes
    }
    fun parse(bytes: ByteArray): Hint? = runCatching {
        require(bytes.size in 17..8192 && bytes[0] == 2.toByte())
        val json = JSONObject(String(bytes, 16, bytes.size - 16, Charsets.UTF_8))
        require(json.optInt("error_code", -1) == 0)
        val result = json.getJSONObject("result")
        val model = NetworkDevices.cleanName(result.optString("device_model")).take(60)
        when (result.optString("device_type")) {
            "SMART.IPCAMERA", "IOT.IPCAMERA" -> Hint("Tapo $model".trim(), "tapo_camera")
            "SMART.TAPOPLUG", "SMART.TAPOBULB", "SMART.TAPOHUB" -> Hint("Tapo $model".trim(), "tplink_smart_device")
            "HOMEWIFISYSTEM" -> Hint("TP-Link Deco $model".trim(), "tplink_router")
            else -> Hint("TP-Link $model".trim(), "tplink_device")
        }
    }.getOrNull()
}
