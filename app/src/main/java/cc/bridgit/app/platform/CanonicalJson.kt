package cc.bridgit.app.platform

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object CanonicalJson {
    fun digest(value: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray()).joinToString("") { "%02x".format(it) }
    fun canonical(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
