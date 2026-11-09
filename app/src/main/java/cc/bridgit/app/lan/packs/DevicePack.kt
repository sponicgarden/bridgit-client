package cc.bridgit.app.lan.packs

import cc.bridgit.app.vision.SoftwareManifest
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Device packs (docs/device-packs.md): device-family support that Bridgit downloads when it needs it instead of
 * shipping it in the app. A pack is data only (requests, fields to read, typed parameters) run by the app's pinned
 * [PackEngine]; it can never bring code. The signed index lists every pack with its version, the engine version
 * it needs, and its file's size and SHA-256; pack files are pinned by that hash, so only the index is signed.
 *
 * Trust fails closed, like the software manifest: Ed25519 signature by the pinned key, the app's channel, a
 * serial that never goes down, versions that never go down, files only on Bridgit's download host.
 *
 * Pure: no Android. Unit-tested in DevicePackTest.
 */
object DevicePack {
    const val ENVELOPE_FORMAT = "bridgit-device-packs-envelope/1"
    const val INDEX_FORMAT = "bridgit-device-packs/1"
    const val PACK_FORMAT = "bridgit-device-pack/1"
    /** The same Bridgit signing key as the software manifest; the formats keep the two kinds of file apart. */
    const val KEY_ID = SoftwareManifest.KEY_ID
    const val PUBLIC_KEY_B64 = SoftwareManifest.PUBLIC_KEY_B64
    const val HOST = "pub-3a79691572bc4abc93fce31910fe320b.r2.dev"
    const val BASE = "https://$HOST/bridget/device-packs"
    const val INDEX_URL = "$BASE/index.json"
    const val CHANNEL = "production"
    const val MAX_INDEX_BYTES = 128L * 1024
    const val MAX_PACK_BYTES = 128L * 1024
    const val MAX_PACKS = 64

    class Bad(message: String) : Exception(message)

    data class Entry(
        val id: String, val version: String, val engine: Int, val minAppVersionCode: Int, val title: String,
        val label: String, val path: String, val size: Long, val sha256: String, val enabled: Boolean,
        val servicesAny: List<String>,
    ) {
        val url get() = "$BASE/$path"
        fun matches(services: List<String>) = servicesAny.any { m -> services.any { it.contains(m, ignoreCase = true) } }
    }

    data class Index(val channel: String, val serial: Long, val publishedAt: String, val packs: List<Entry>) {
        fun entry(id: String) = packs.firstOrNull { it.id == id }
    }

    data class Param(val name: String, val type: String, val required: Boolean, val max: Int, val min: Long, val maxValue: Long)
    /** A value sent to the device: a template, escaped once for SOAP ([document] false) or as a nested XML document. */
    data class Arg(val name: String, val template: String, val document: Boolean)
    data class Out(val name: String, val from: String, val type: String)
    data class Step(val service: String, val action: String, val args: List<Arg>, val outs: List<Out>, val optional: Boolean)
    data class Action(val name: String, val params: List<Param>, val steps: List<Step>) {
        /** Each parameter's name, type, required flag and bounds, as GET /v1/lan/devices and /v1/lan/packs document them. */
        fun paramSchema() = JSONArray(params.map { p ->
            JSONObject().put("name", p.name).put("type", p.type).put("required", p.required).apply {
                if (p.type == "integer") put("min", p.min).put("max", p.maxValue) else if (p.type != "boolean") put("max_length", p.max)
            } })
        /** A ready-to-edit value for each required parameter (A0212), for the advertised request body. */
        fun exampleParams() = JSONObject().apply {
            params.filter { it.required }.forEach { p -> put(p.name, when (p.type) {
                "integer" -> (p.min + (p.maxValue - p.min) / 4).coerceIn(p.min, p.maxValue)
                "boolean" -> true
                "url" -> "https://example.com/audio.mp3"
                else -> "text"
            }) }
        }
    }
    data class Describe(val deviceType: String, val services: Map<String, String>)
    data class Pack(
        val id: String, val version: String, val engine: Int, val title: String, val limitations: String,
        val locateSt: String, val describe: Describe, val actions: List<Action>,
    ) {
        fun action(name: String) = actions.firstOrNull { it.name == name }
        fun schema() = JSONObject().put("id", id).put("version", version).put("title", title).put("limitations", limitations)
            .put("actions", JSONArray(actions.map { a -> JSONObject().put("action", a.name).put("params", a.paramSchema()) }))
    }

    private val ID = Regex("^[a-z][a-z0-9_]{2,40}$")
    private val VERSION = Regex("^[0-9]{1,4}(\\.[0-9]{1,4}){0,2}$")
    private val PATH = Regex("^[a-z0-9_]+/[0-9.]+\\.json$")
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val UPNP_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
    private val URN = Regex("^urn:[A-Za-z0-9.-]+:(service|device):[A-Za-z0-9_-]+:[0-9]+$")
    val PARAM_TYPES = setOf("url", "text", "integer", "boolean")
    val OUT_TYPES = setOf("text", "integer", "boolean")

    /** -1, 0 or 1. Versions are 1-3 dot-separated numbers; a missing part is 0. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toInt() }; val y = b.split('.').map { it.toInt() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The checked index in [text]. [minSerial] is the serial the phone already has; a lower one is a replay. */
    fun readIndex(text: String, minSerial: Long = 0, channel: String = CHANNEL, publicKeyB64: String = PUBLIC_KEY_B64): Index {
        if (text.toByteArray().size > MAX_INDEX_BYTES) throw Bad("the pack index is larger than ${MAX_INDEX_BYTES / 1024} KB")
        val env = runCatching { JSONObject(text) }.getOrElse { throw Bad("the pack index isn't JSON") }
        if (env.optString("format") != ENVELOPE_FORMAT) throw Bad("unknown envelope format")
        if (env.optString("key_id") != KEY_ID) throw Bad("signed with an unknown key")
        val payload = runCatching { Base64.getDecoder().decode(env.getString("payload")) }.getOrElse { throw Bad("payload isn't base64") }
        val sig = runCatching { Base64.getDecoder().decode(env.getString("signature")) }.getOrElse { throw Bad("signature isn't base64") }
        if (!SoftwareManifest.verify(payload, sig, publicKeyB64)) throw Bad("the signature doesn't match")
        val o = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrElse { throw Bad("the payload isn't JSON") }
        if (o.optString("format") != INDEX_FORMAT) throw Bad("unknown index format")
        val serial = o.optLong("serial", -1); if (serial < 1) throw Bad("serial must be a positive number")
        if (o.optString("channel") != channel) throw Bad("the pack index is for channel ${o.optString("channel")}, not $channel")
        if (serial < minSerial) throw Bad("the pack index is older (serial $serial) than the one the phone has ($minSerial)")
        val a = o.optJSONArray("packs") ?: throw Bad("packs is required")
        if (a.length() > MAX_PACKS) throw Bad("at most $MAX_PACKS packs")
        val list = (0 until a.length()).map { entry(a.optJSONObject(it) ?: throw Bad("packs[$it] must be an object"), "packs[$it]") }
        if (list.map { it.id }.toSet().size != list.size) throw Bad("pack ids must be unique")
        return Index(channel, serial, o.optString("published_at").take(40), list)
    }

    private fun str(o: JSONObject, k: String, at: String, max: Int = 200): String =
        o.optString(k).takeIf { it.isNotBlank() && it.length <= max } ?: throw Bad("$at.$k is required (at most $max characters)")

    private fun strings(a: JSONArray?, at: String, max: Int = 16): List<String> {
        val list = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()
        if (list.size > max || list.any { it.isBlank() || it.length > 120 }) throw Bad("$at must list at most $max short strings")
        return list
    }

    private fun entry(o: JSONObject, at: String): Entry {
        val id = str(o, "id", at); if (!ID.matches(id)) throw Bad("$at.id must be lowercase letters, digits or _")
        val version = str(o, "version", at); if (!VERSION.matches(version)) throw Bad("$at.version is not a version")
        val path = str(o, "path", at); if (!PATH.matches(path) || path != "$id/$version.json") throw Bad("$at.path must be $id/$version.json")
        val size = o.optLong("size", -1); if (size !in 1..MAX_PACK_BYTES) throw Bad("$at.size must be 1 byte to ${MAX_PACK_BYTES / 1024} KB")
        val sha = o.optString("sha256"); if (!SHA.matches(sha)) throw Bad("$at.sha256 must be 64 lowercase hex characters")
        val engine = o.optInt("engine", -1); if (engine < 1) throw Bad("$at.engine must be a positive number")
        val match = o.optJSONObject("match") ?: throw Bad("$at.match is required")
        val services = strings(match.optJSONArray("services_any"), "$at.match.services_any")
        if (services.isEmpty()) throw Bad("$at.match.services_any must list at least one service")
        return Entry(id, version, engine, o.optInt("min_app_version_code", 0), str(o, "title", at, 60), str(o, "label", at, 60),
            path, size, sha, o.optBoolean("enabled", true), services)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The pack in [bytes], checked against its signed index [entry]. Anything odd refuses the whole pack. */
    fun readPack(bytes: ByteArray, entry: Entry): Pack {
        if (bytes.size.toLong() != entry.size) throw Bad("pack ${entry.id} is ${bytes.size} bytes; the index says ${entry.size}")
        if (sha256(bytes) != entry.sha256) throw Bad("pack ${entry.id} doesn't match the index's SHA-256")
        val p = parsePack(String(bytes, Charsets.UTF_8))
        if (p.id != entry.id || p.version != entry.version || p.engine != entry.engine) throw Bad("pack ${entry.id} doesn't match its index entry")
        return p
    }

    /** The pack's content, every field checked. Public for tests and the publishing tool's own check. */
    fun parsePack(text: String): Pack {
        val o = runCatching { JSONObject(text) }.getOrElse { throw Bad("the pack isn't JSON") }
        if (o.optString("format") != PACK_FORMAT) throw Bad("unknown pack format")
        val id = str(o, "id", "pack"); if (!ID.matches(id)) throw Bad("pack.id must be lowercase letters, digits or _")
        val version = str(o, "version", "pack"); if (!VERSION.matches(version)) throw Bad("pack.version is not a version")
        val engine = o.optInt("engine", -1); if (engine < 1) throw Bad("pack.engine must be a positive number")
        if (o.optString("transport") != "upnp") throw Bad("pack.transport must be upnp")
        val st = str(o.optJSONObject("locate") ?: JSONObject(), "ssdp_st", "pack.locate")
        if (!URN.matches(st)) throw Bad("pack.locate.ssdp_st must be a UPnP urn")
        val d = o.optJSONObject("describe") ?: throw Bad("pack.describe is required")
        val deviceType = str(d, "device_type", "pack.describe"); if (!URN.matches(deviceType)) throw Bad("pack.describe.device_type must be a UPnP urn")
        val so = d.optJSONObject("services") ?: throw Bad("pack.describe.services is required")
        val services = so.keys().asSequence().toList().associateWith { k ->
            if (!NAME.matches(k)) throw Bad("pack.describe.services key $k must be a short lowercase name")
            so.optString(k).takeIf(URN::matches) ?: throw Bad("pack.describe.services.$k must be a UPnP urn")
        }
        if (services.isEmpty() || services.size > 8) throw Bad("pack.describe.services must name 1-8 services")
        val ao = o.optJSONObject("actions") ?: throw Bad("pack.actions is required")
        val actions = ao.keys().asSequence().toList().sorted().map { name ->
            if (!NAME.matches(name)) throw Bad("action $name must be a short lowercase name")
            action(name, ao.optJSONObject(name) ?: throw Bad("action $name must be an object"), services.keys)
        }
        if (actions.size !in 1..16) throw Bad("a pack has 1-16 actions")
        if (actions.none { it.name == "status" }) throw Bad("a pack needs a status action, used to verify enrollment")
        if (actions.first { it.name == "status" }.params.any { it.required }) throw Bad("status can't need parameters")
        return Pack(id, version, engine, str(o, "title", "pack", 60), o.optString("limitations").take(400), st, Describe(deviceType, services), actions)
    }

    private fun action(name: String, o: JSONObject, services: Set<String>): Action {
        val at = "action $name"
        val pa = o.optJSONArray("params") ?: JSONArray()
        if (pa.length() > 8) throw Bad("$at has at most 8 params")
        val params = (0 until pa.length()).map { i ->
            val p = pa.optJSONObject(i) ?: throw Bad("$at.params[$i] must be an object")
            val pn = str(p, "name", "$at.params[$i]", 32); if (!NAME.matches(pn)) throw Bad("$at.params[$i].name must be a short lowercase name")
            val type = p.optString("type"); if (type !in PARAM_TYPES) throw Bad("$at.params.$pn.type must be one of $PARAM_TYPES")
            val max = p.optInt("max_length", if (type == "url") 2048 else 120); if (max !in 1..2048) throw Bad("$at.params.$pn.max_length must be 1-2048")
            val min = p.optLong("min", 0); val maxValue = p.optLong("max", 100)
            if (type == "integer" && (min > maxValue || min < -1_000_000 || maxValue > 1_000_000)) throw Bad("$at.params.$pn has a bad range")
            Param(pn, type, p.optBoolean("required", false), max, min, maxValue)
        }
        if (params.map { it.name }.toSet().size != params.size) throw Bad("$at has duplicate params")
        val sa = o.optJSONArray("steps") ?: throw Bad("$at.steps is required")
        if (sa.length() !in 1..6) throw Bad("$at has 1-6 steps")
        val known = params.map { it.name }.toSet()
        val steps = (0 until sa.length()).map { i ->
            val s = sa.optJSONObject(i) ?: throw Bad("$at.steps[$i] must be an object")
            val service = s.optString("service"); if (service !in services) throw Bad("$at.steps[$i].service must be one of $services")
            val upnp = str(s, "action", "$at.steps[$i]", 64); if (!UPNP_NAME.matches(upnp)) throw Bad("$at.steps[$i].action isn't a UPnP action name")
            val args = s.optJSONArray("args") ?: JSONArray()
            if (args.length() > 8) throw Bad("$at.steps[$i] has at most 8 args")
            val argList = (0 until args.length()).map { j ->
                val a = args.optJSONArray(j)?.takeIf { it.length() == 2 } ?: throw Bad("$at.steps[$i].args[$j] must be [name, value]")
                val an = a.optString(0); if (!UPNP_NAME.matches(an)) throw Bad("$at.steps[$i].args[$j] has a bad name")
                val v = a.opt(1)
                val (template, document) = when (v) {
                    is String -> v to false
                    is JSONObject -> (v.optString("document").takeIf { it.isNotEmpty() } ?: throw Bad("$at.steps[$i].args[$j] needs document")) to true
                    else -> throw Bad("$at.steps[$i].args[$j] value must be text or {document}")
                }
                if (template.length > 2000) throw Bad("$at.steps[$i].args[$j] is too long")
                PackEngine.placeholders(template).forEach { if (it !in known) throw Bad("$at.steps[$i].args[$j] uses unknown param {$it}") }
                Arg(an, template, document)
            }
            val oa = s.optJSONArray("out") ?: JSONArray()
            if (oa.length() > 8) throw Bad("$at.steps[$i] has at most 8 outputs")
            val outs = (0 until oa.length()).map { j ->
                val x = oa.optJSONObject(j) ?: throw Bad("$at.steps[$i].out[$j] must be an object")
                val on = x.optString("name"); if (!NAME.matches(on)) throw Bad("$at.steps[$i].out[$j].name must be a short lowercase name")
                val from = x.optString("from"); if (!UPNP_NAME.matches(from)) throw Bad("$at.steps[$i].out[$j].from isn't a UPnP field")
                val type = x.optString("type", "text"); if (type !in OUT_TYPES) throw Bad("$at.steps[$i].out[$j].type must be one of $OUT_TYPES")
                Out(on, from, type)
            }
            Step(service, upnp, argList, outs, s.optBoolean("optional", false))
        }
        return Action(name, params, steps)
    }

    /** Why this app can't use [entry] (plain words), or null when it can. */
    fun unsupported(entry: Entry, appVersionCode: Int, engine: Int = PackEngine.VERSION): String? = when {
        !entry.enabled -> "Bridgit withdrew this device pack"
        entry.engine > engine -> "this pack needs a newer Bridgit (pack engine ${entry.engine}; this app has $engine)"
        entry.minAppVersionCode > appVersionCode -> "this pack needs Bridgit build ${entry.minAppVersionCode} or later"
        else -> null
    }
}
