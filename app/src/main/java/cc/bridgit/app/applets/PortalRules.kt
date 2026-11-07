package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/**
 * Applet portals (docs/spec-portals-and-cards.md): the pure checks on what a portal page tells Bridgit
 * (functions it exposes, the state it publishes, its answers to calls) and on what agents send to it. No
 * Android types, so every rule is unit-tested.
 */
object PortalRules {
    const val MAX_FUNCTIONS = 32
    const val MAX_DESCRIPTION = 200
    /** params, state, args and results: 16 KB of UTF-8 each. */
    const val MAX_JSON_BYTES = 16 * 1024
    const val MAX_TEXT_CHARS = 8 * 1024
    const val MAX_IN_FLIGHT = 5
    const val DEFAULT_TIMEOUT_S = 10
    const val MAX_TIMEOUT_S = 30
    const val DEFAULT_OPEN_WAIT_S = 5
    const val MAX_OPEN_WAIT_S = 15
    /** portal.state events: at most one a second per portal. */
    const val STATE_EVENT_MS = 1000L
    private val NAME = Regex("[a-z][a-z0-9_]{0,39}")

    class Bad(message: String) : Exception(message)

    data class Function(val name: String, val description: String, val params: JSONObject?) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description).put("params", params ?: JSONObject.NULL)
    }

    fun validName(n: String) = NAME.matches(n)

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** Parses one Bridgit.portal.expose(name, spec) call from the page; [spec] is the page's JSON (or null). */
    fun function(name: String?, spec: String?): Function {
        val n = name?.trim().orEmpty()
        if (!validName(n)) throw Bad("A portal function name is 1-40 lowercase letters, digits or _, starting with a letter.")
        val o = spec?.takeIf { it.isNotBlank() && it != "undefined" && it != "null" }?.let {
            if (bytes(it) > MAX_JSON_BYTES) throw Bad("$n: the description and params are larger than 16 KB.")
            runCatching { JSONObject(it) }.getOrElse { throw Bad("$n: the spec must be an object {description, params}.") }
        } ?: JSONObject()
        val params = o.opt("params")?.let { it as? JSONObject ?: throw Bad("$n: params must be an object.") }
        return Function(n, o.optString("description").take(MAX_DESCRIPTION), params)
    }

    /** Adds or replaces [f] in [current], keeping order; refuses the 33rd. */
    fun withFunction(current: List<Function>, f: Function): List<Function> {
        val i = current.indexOfFirst { it.name == f.name }
        if (i >= 0) return current.toMutableList().also { it[i] = f }
        if (current.size >= MAX_FUNCTIONS) throw Bad("A portal exposes at most $MAX_FUNCTIONS functions.")
        return current + f
    }

    fun functionsJson(fs: List<Function>) = JSONArray(fs.map { it.toJson() })

    /** The page's Bridgit.portal.setState(json): an object or array of at most 16 KB, else null (refused). */
    fun state(json: String?): Any? {
        if (json == null || bytes(json) > MAX_JSON_BYTES) return null
        val t = json.trim()
        return when {
            t.startsWith("{") -> runCatching { JSONObject(t) }.getOrNull()
            t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull()
            else -> null
        }
    }

    /** An agent's call body → (function, args, timeout ms). */
    fun call(body: JSONObject, exposed: List<Function>): Triple<String, Any, Long> {
        val fn = body.optString("function").trim()
        if (fn.isEmpty()) throw Bad("function is required: the name of one of the portal's functions (GET …/portal lists them).")
        if (exposed.none { it.name == fn }) throw NotExposed(fn, exposed.map { it.name })
        val args = body.opt("args") ?: JSONObject()
        if (args !is JSONObject && args !is JSONArray) throw Bad("args must be an object (or an array).")
        if (bytes(args.toString()) > MAX_JSON_BYTES) throw Bad("args are larger than 16 KB.")
        val t = body.opt("timeout_s")?.let { (it as? Number)?.toInt() ?: throw Bad("timeout_s must be a number of seconds.") } ?: DEFAULT_TIMEOUT_S
        if (t !in 1..MAX_TIMEOUT_S) throw Bad("timeout_s must be 1-$MAX_TIMEOUT_S.")
        return Triple(fn, args, t * 1000L)
    }

    class NotExposed(val function: String, val known: List<String>) : Exception(
        if (known.isEmpty()) "The portal hasn't exposed any functions (yet)." else "The portal has no function $function. It has: ${known.joinToString()}.")

    /** The page's Bridgit.portal reply: {ok:true, result} or {ok:false, error}. Null when too big or not JSON. */
    fun reply(json: String?): JSONObject? {
        if (json == null || bytes(json) > MAX_JSON_BYTES + 256) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return if (o.optBoolean("ok")) JSONObject().put("ok", true).put("result", o.opt("result") ?: JSONObject.NULL)
        else JSONObject().put("ok", false).put("error", o.optString("error").ifEmpty { "The portal's function failed." }.take(500))
    }

    fun openWaitMs(body: JSONObject): Long {
        val w = body.opt("wait_s")?.let { (it as? Number)?.toInt() ?: throw Bad("wait_s must be a number of seconds.") } ?: DEFAULT_OPEN_WAIT_S
        if (w !in 0..MAX_OPEN_WAIT_S) throw Bad("wait_s must be 0-$MAX_OPEN_WAIT_S.")
        return w * 1000L
    }

    /**
     * Runs after Bridgit's card shims inside a portal: adds Bridgit.portal (expose, setState, on) over the native
     * BridgitPortalNative interface. Agent calls arrive through window.__bridgitPortalCall(id, name, argsJson);
     * the answer goes back with BridgitPortalNative.reply(id, json). Outside a portal [STUB] runs instead.
     */
    const val SHIM = "(function(){var w=window,n=w.BridgitPortalNative,B=w.Bridgit;if(!n||!B)return;var h=null," +
        "s=function(v){if(v===undefined)return null;try{return JSON.stringify(v)}catch(e){return null}}," +
        "back=function(id,o){var t=s(o);n.reply(id,t||JSON.stringify({ok:false,error:'The result could not be sent (not JSON).'}))};" +
        "try{Object.defineProperty(w,'__bridgitPortalCall',{value:function(id,name,a){var args;try{args=JSON.parse(a)}catch(e){args={}}" +
        "if(!h){back(id,{ok:false,error:'The portal page has no call handler (Bridgit.portal.on(\"call\", …)).'});return}" +
        "try{Promise.resolve(h(name,args)).then(function(r){back(id,{ok:true,result:r===undefined?null:r})}," +
        "function(e){back(id,{ok:false,error:String(e&&e.message||e)})})}catch(e){back(id,{ok:false,error:String(e&&e.message||e)})}}," +
        "writable:false,configurable:false})}catch(e){}" +
        "var p={available:true,expose:function(name,spec){return n.expose(String(name),s(spec===undefined?{}:spec)||'{}')}," +
        "setState:function(v){var t=s(v);return t?n.setState(t):false}," +
        "on:function(ev,f){if(ev==='call'&&typeof f==='function')h=f}};" +
        "try{Object.defineProperty(B,'portal',{value:p,writable:false,configurable:false})}catch(e){}})();"

    /** Inbox cards: Bridgit.portal exists but does nothing, so one page works in both places. */
    const val STUB = "(function(){var B=window.Bridgit;if(!B||B.portal)return;try{Object.defineProperty(B,'portal',{value:" +
        "{available:false,expose:function(){return false},setState:function(){return false},on:function(){}},writable:false,configurable:false})}catch(e){}})();"

    /** Reads the page's visible words for GET …/portal (capped by the caller). */
    const val TEXT_SCRIPT = "(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()"
}
