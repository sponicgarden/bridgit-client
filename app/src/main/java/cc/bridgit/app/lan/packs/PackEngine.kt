package cc.bridgit.app.lan.packs

import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Runs a [DevicePack.Pack] against one enrolled device. The engine is the only code: a pack can only name UPnP
 * services the device itself describes, call SOAP actions on them with typed, validated parameters, and read
 * named fields back. Every request goes to the enrolled device's own literal address (its SSDP LOCATION and
 * control URLs must stay on that host), plain http on port 80 or 1024-65535; no other host, no redirects.
 *
 * Pure apart from [Net]. Unit-tested in PackEngineTest.
 */
object PackEngine {
    /** Raise when the engine learns something a pack must be able to rely on; packs name the version they need. */
    const val VERSION = 1
    const val MAX_STEPS_RUN = 6

    /** A refusal with a message safe to show the agent: [device] true when the device said no, false when the request was bad. */
    class PackError(message: String, val device: Boolean) : Exception(message)

    class Reply(val status: Int, val bytes: ByteArray)
    interface Net {
        /** The SSDP LOCATION [host] gives for search target [st], or null when it doesn't answer. */
        fun locate(host: String, st: String): String?
        /** One request; GET when [body] is null, else POST. Throws on network failure. */
        fun http(url: String, headers: Map<String, String>, body: ByteArray?): Reply
    }

    /** What enrollment learned about the device; stored with the enrollment and refreshed if the device moves port. */
    data class Bound(val location: String, val controls: Map<String, String>, val identity: String, val model: String, val name: String) {
        fun json() = JSONObject().put("location", location).put("controls", JSONObject(controls)).put("identity", identity)
            .put("model", model).put("name", name)
        companion object {
            fun read(o: JSONObject?): Bound? = o?.let { j ->
                val c = j.optJSONObject("controls") ?: return null
                Bound(j.optString("location"), c.keys().asSequence().associateWith { c.optString(it) }, j.optString("identity"),
                    j.optString("model"), j.optString("name"))
            }
        }
    }

    private val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]{0,31})\\}")
    fun placeholders(template: String) = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    fun xmlEscape(s: String) = buildString(s.length) {
        for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c) }
    }

    /** [url] if it is plain http to exactly [host] on port 80 or 1024-65535, else a [PackError]. */
    fun deviceUrl(url: String, host: String): String {
        val u = runCatching { URI(url) }.getOrNull() ?: throw PackError("The device gave an unusable address.", true)
        val port = if (u.port == -1) 80 else u.port
        if (u.scheme != "http" || u.host != host || u.rawUserInfo != null || u.rawFragment != null || !(port == 80 || port in 1024..65535) ||
            u.rawPath.isNullOrEmpty() || !u.rawPath.startsWith("/") || url.length > 512)
            throw PackError("The device pointed somewhere other than itself; Bridgit only talks to the enrolled device.", true)
        return u.toString()
    }

    /** Finds [host] by SSDP, reads its description and resolves the pack's services. */
    fun bind(pack: DevicePack.Pack, host: String, net: Net): Bound {
        val location = deviceUrl(net.locate(host, pack.locateSt) ?: net.locate(host, pack.locateSt)
            ?: throw PackError("The device didn't answer as a ${pack.title.lowercase()} (no UPnP reply from $host).", true), host)
        val r = net.http(location, emptyMap(), null)
        if (r.status != 200) throw PackError("The device's description couldn't be read (HTTP ${r.status}).", true)
        return describe(pack, host, location, r.bytes)
    }

    fun describe(pack: DevicePack.Pack, host: String, location: String, xml: ByteArray): Bound {
        val root = parse(xml)
        val device = elements(root, "device").firstOrNull { childText(it, "deviceType") == pack.describe.deviceType }
            ?: throw PackError("This device isn't a ${pack.describe.deviceType.substringAfter("device:").substringBefore(':')}.", true)
        val services = child(device, "serviceList")?.let { list -> directChildren(list, "service") } ?: emptyList()
        val controls = pack.describe.services.mapValues { (key, type) ->
            val s = services.firstOrNull { childText(it, "serviceType") == type }
                ?: throw PackError("The device doesn't offer the $key service this pack needs.", true)
            val control = childText(s, "controlURL").takeIf { it.isNotBlank() } ?: throw PackError("The device's $key service has no control address.", true)
            deviceUrl(runCatching { URI(location).resolve(control.trim()).toString() }.getOrElse { throw PackError("The device's $key address is unusable.", true) }, host)
        }
        val identity = childText(device, "UDN").trim().take(120)
        if (identity.isBlank()) throw PackError("The device has no UPnP identity (UDN).", true)
        return Bound(location, controls, identity, clean(childText(device, "modelName")), clean(childText(device, "friendlyName")))
    }

    /** The action's parameters as text for the templates, checked against the pack's types. */
    fun params(action: DevicePack.Action, given: JSONObject): Map<String, String> {
        val unknown = given.keys().asSequence().filter { k -> action.params.none { it.name == k } }.toList()
        if (unknown.isNotEmpty()) throw PackError("${action.name} doesn't take ${unknown.joinToString()}; it takes ${action.params.joinToString { it.name }.ifEmpty { "no parameters" }}.", false)
        return action.params.associate { p ->
            if (!given.has(p.name) || given.isNull(p.name)) {
                if (p.required) throw PackError("${action.name} needs ${p.name}.", false)
                return@associate p.name to ""
            }
            val v = given.get(p.name)
            p.name to when (p.type) {
                "integer" -> {
                    val n = (v as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) }?.toLong()
                        ?: throw PackError("${p.name} must be a whole number.", false)
                    if (n !in p.min..p.maxValue) throw PackError("${p.name} must be ${p.min} to ${p.maxValue}.", false)
                    n.toString()
                }
                "boolean" -> if (v is Boolean) (if (v) "1" else "0") else throw PackError("${p.name} must be true or false.", false)
                "url" -> {
                    val s = v as? String ?: throw PackError("${p.name} must be a URL.", false)
                    val u = runCatching { URI(s) }.getOrNull()
                    if (s.length > p.max || u == null || u.scheme?.lowercase() !in listOf("http", "https") || u.host.isNullOrEmpty() ||
                        u.rawUserInfo != null || s.any { it <= ' ' || it == '\u007f' })
                        throw PackError("${p.name} must be an http or https URL of at most ${p.max} characters.", false)
                    s
                }
                else -> {
                    val s = v as? String ?: throw PackError("${p.name} must be text.", false)
                    if (s.length > p.max) throw PackError("${p.name} is at most ${p.max} characters.", false)
                    s.filter { it >= ' ' }
                }
            }
        }
    }

    fun fill(template: String, values: Map<String, String>, escape: Boolean) =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]].orEmpty().let { if (escape) xmlEscape(it) else it } }

    fun soap(serviceType: String, action: String, args: List<Pair<String, String>>): ByteArray =
        ("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$serviceType\">" +
            args.joinToString("") { (k, v) -> "<$k>${xmlEscape(v)}</$k>" } + "</u:$action></s:Body></s:Envelope>").toByteArray(Charsets.UTF_8)

    /** Runs [actionName] with [given] parameters. Returns the action's named outputs. */
    fun run(pack: DevicePack.Pack, bound: Bound, actionName: String, given: JSONObject, net: Net): JSONObject {
        val action = pack.action(actionName) ?: throw PackError("$actionName isn't an action of the ${pack.id} pack; use ${pack.actions.joinToString { it.name }}.", false)
        val values = params(action, given)
        val out = JSONObject()
        for (step in action.steps.take(MAX_STEPS_RUN)) {
            val type = pack.describe.services.getValue(step.service)
            val url = bound.controls[step.service] ?: throw PackError("The enrollment is missing the ${step.service} service; enroll the device again.", true)
            val args = step.args.map { a -> a.name to fill(a.template, values, a.document) }
            val r = try {
                net.http(url, mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"$type#${step.action}\""), soap(type, step.action, args))
            } catch (e: Exception) { if (step.optional) continue else throw e }
            if (r.status != 200) {
                if (step.optional) continue
                val fault = runCatching { parse(r.bytes) }.getOrNull()
                val code = fault?.let { firstText(it, "errorCode") }.orEmpty()
                val desc = fault?.let { firstText(it, "errorDescription") }.orEmpty().let(::clean)
                throw PackError("The device refused ${step.action}" + (if (code.isNotBlank()) " (UPnP error $code${if (desc.isNotBlank()) ": $desc" else ""})" else " (HTTP ${r.status})") + ".", true)
            }
            val doc = parse(r.bytes)
            val response = elements(doc, "${step.action}Response").firstOrNull()
                ?: if (step.optional) continue else throw PackError("The device's reply to ${step.action} wasn't understood.", true)
            for (o in step.outs) {
                val text = child(response, o.from)?.textContent?.trim()
                out.put(o.name, when {
                    text == null -> org.json.JSONObject.NULL
                    o.type == "integer" -> text.toLongOrNull() ?: org.json.JSONObject.NULL
                    o.type == "boolean" -> text == "1" || text.equals("true", true)
                    else -> clean(text, 300)
                })
            }
        }
        return out
    }

    private fun clean(s: String, max: Int = 120) = s.filter { it >= ' ' }.trim().take(max)

    fun parse(bytes: ByteArray): Element {
        if (bytes.size > 256 * 1024) throw PackError("The device's reply is too large.", true)
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) throw PackError("The device's reply uses XML features Bridgit doesn't accept.", true)
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true; f.isExpandEntityReferences = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return runCatching { f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement }.getOrNull()
            ?: throw PackError("The device's reply isn't XML.", true)
    }

    private fun local(n: Node) = n.localName ?: n.nodeName.substringAfter(':')
    private fun elements(root: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(n: Node) { if (n is Element) { if (local(n) == name) out += n; var c = n.firstChild; while (c != null) { walk(c); c = c.nextSibling } } }
        walk(root); return out
    }
    private fun directChildren(e: Element, name: String): List<Element> {
        val out = mutableListOf<Element>(); var c = e.firstChild
        while (c != null) { if (c is Element && local(c) == name) out += c; c = c.nextSibling }
        return out
    }
    private fun child(e: Element, name: String): Element? = directChildren(e, name).firstOrNull()
    private fun childText(e: Element, name: String) = child(e, name)?.textContent?.trim().orEmpty()
    private fun firstText(e: Element, name: String) = elements(e, name).firstOrNull()?.textContent?.trim()
}
