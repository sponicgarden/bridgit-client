package cc.bridgit.app.vision

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The software manifest (docs/software-manifest.md): the signed list of optional on-phone software items (models
 * for the opt-in vision engines) that Bridgit can download. Published at [URL]; a copy ships in the app
 * (assets/software/manifest.json) so the list is known offline. Checked once a day by [SoftwareCatalog].
 *
 * Trust (fails closed, like the APK updater): the envelope's payload must carry a valid Ed25519 signature by
 * [PUBLIC_KEY_B64]; its channel must be the one the app reads; its serial may never go down; every file is on
 * Bridgit's download host and pinned by size and SHA-256. Only models are listed: the native runtime that runs
 * them is pinned in the app ([OnDeviceEngines.RUNTIMES]), so a manifest can never bring new native code.
 * An item the app can't run (unknown pipeline or runtime, app too old) is kept and shown as unsupported.
 *
 * Pure: no Android. Unit-tested in SoftwareManifestTest.
 */
object SoftwareManifest {
    const val ENVELOPE_FORMAT = "bridgit-software-envelope/1"
    const val FORMAT = "bridgit-software/1"
    const val KEY_ID = "bridgit-software-1"
    /** Ed25519 public key of the Bridgit team's manifest signing key (private half: see docs/software-manifest.md). */
    const val PUBLIC_KEY_B64 = "lL+Dv7VCZI+ttU2HidhsKVB20V7bq1IMIjdZhXwmD+c="
    const val BASE = "https://pub-3a79691572bc4abc93fce31910fe320b.r2.dev/bridget/models"
    const val URL = "$BASE/manifest.json"
    const val CHANNEL = "production"
    const val MAX_BYTES = 256L * 1024
    const val MAX_ITEMS = 64

    /** What this app can run: its compiled pipelines and runtime. */
    const val RUNTIME = "onnxruntime-1.22"
    val PIPELINES = mapOf("florence2-ovd/1" to "detector", "sam-box/1" to "outline")

    class Bad(message: String) : Exception(message)

    data class FileRef(val path: String, val size: Long, val sha256: String) {
        val name: String get() = path.substringAfterLast('/')
        val url: String get() = "$BASE/$path"
    }

    data class Item(
        val id: String, val engine: String, val kind: String, val pipeline: String, val runtime: String, val version: String,
        val minAppVersionCode: Int, val licence: String, val goodAt: String, val notGoodAt: String, val speed: String,
        val memory: String, val serves: List<String>, val enabled: Boolean, val replaces: List<String>, val files: List<FileRef>,
    ) {
        val folder: String get() = "$id-$version"
        val sizeBytes: Long get() = files.sumOf { it.size }
        val sizeMb: Int get() = Math.round(sizeBytes / 1e6).toInt()
    }

    data class Manifest(val channel: String, val serial: Long, val publishedAt: String, val items: List<Item>)

    private val ID = Regex("^[a-z][a-z0-9.-]{1,63}$")
    private val ENGINE = Regex("^[a-z][a-z0-9_]{2,40}$")
    private val VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")
    private val PATH = Regex("^[a-z0-9.-]+(/[A-Za-z0-9._-]+)+$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    /** The engine-name format an applet package may name (runtime.engines). Pure. */
    fun isEngineName(s: String) = ENGINE.matches(s)

    /**
     * The checked manifest in [text] (an envelope). [publicKeyB64] and [channel] are the app's; [minSerial] is the
     * serial it already has (a lower one is a replay and refused).
     */
    fun read(text: String, minSerial: Long = 0, channel: String = CHANNEL, publicKeyB64: String = PUBLIC_KEY_B64): Manifest {
        if (text.toByteArray().size > MAX_BYTES) throw Bad("the manifest is larger than ${MAX_BYTES / 1024} KB")
        val env = runCatching { JSONObject(text) }.getOrElse { throw Bad("the manifest isn't JSON") }
        if (env.optString("format") != ENVELOPE_FORMAT) throw Bad("unknown envelope format")
        if (env.optString("key_id") != KEY_ID) throw Bad("signed with an unknown key")
        val payload = runCatching { Base64.getDecoder().decode(env.getString("payload")) }.getOrElse { throw Bad("payload isn't base64") }
        val sig = runCatching { Base64.getDecoder().decode(env.getString("signature")) }.getOrElse { throw Bad("signature isn't base64") }
        if (!verify(payload, sig, publicKeyB64)) throw Bad("the signature doesn't match")
        val m = parse(String(payload, Charsets.UTF_8))
        if (m.channel != channel) throw Bad("the manifest is for channel ${m.channel}, not $channel")
        if (m.serial < minSerial) throw Bad("the manifest is older (serial ${m.serial}) than the one the phone has ($minSerial)")
        return m
    }

    fun verify(payload: ByteArray, signature: ByteArray, publicKeyB64: String): Boolean = runCatching {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(Base64.getDecoder().decode(publicKeyB64), 0))
        s.update(payload, 0, payload.size)
        s.verifySignature(signature)
    }.getOrDefault(false)

    /** The payload (already verified). Every field is checked; anything odd refuses the whole manifest. */
    fun parse(payload: String): Manifest {
        val o = runCatching { JSONObject(payload) }.getOrElse { throw Bad("the payload isn't JSON") }
        if (o.optString("format") != FORMAT) throw Bad("unknown payload format")
        val serial = o.optLong("serial", -1); if (serial < 1) throw Bad("serial must be a positive number")
        val items = o.optJSONArray("items") ?: throw Bad("items is required")
        if (items.length() > MAX_ITEMS) throw Bad("at most $MAX_ITEMS items")
        val list = (0 until items.length()).map { item(items.optJSONObject(it) ?: throw Bad("items[$it] must be an object"), it) }
        if (list.map { it.id }.toSet().size != list.size) throw Bad("item ids must be unique")
        return Manifest(o.optString("channel"), serial, o.optString("published_at"), list)
    }

    private fun str(o: JSONObject, k: String, at: String, max: Int = 500): String =
        o.optString(k).takeIf { it.isNotBlank() && it.length <= max } ?: throw Bad("$at.$k is required (at most $max characters)")

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()

    private fun item(o: JSONObject, i: Int): Item {
        val at = "items[$i]"
        val id = str(o, "id", at); if (!ID.matches(id)) throw Bad("$at.id must be 2-64 lowercase letters, digits, . or -")
        val engine = str(o, "engine", at); if (!ENGINE.matches(engine)) throw Bad("$at.engine must be lowercase letters, digits or _")
        val version = str(o, "version", at); if (!VERSION.matches(version)) throw Bad("$at.version is not a version")
        val files = o.optJSONArray("files") ?: throw Bad("$at.files is required")
        if (files.length() !in 1..16) throw Bad("$at.files must list 1-16 files")
        val refs = (0 until files.length()).map { j ->
            val f = files.optJSONObject(j) ?: throw Bad("$at.files[$j] must be an object")
            val path = f.optString("path")
            if (!PATH.matches(path) || path.split('/').any { it == "." || it == ".." }) throw Bad("$at.files[$j].path must be folder/name under the models folder")
            val size = f.optLong("size", -1); if (size !in 1..2_000_000_000L) throw Bad("$at.files[$j].size must be 1 byte to 2 GB")
            val sha = f.optString("sha256"); if (!SHA.matches(sha)) throw Bad("$at.files[$j].sha256 must be 64 lowercase hex characters")
            FileRef(path, size, sha)
        }
        if (refs.map { it.name }.toSet().size != refs.size) throw Bad("$at.files must have distinct names")
        return Item(id, engine, str(o, "kind", at, 32), str(o, "pipeline", at, 64), str(o, "runtime", at, 64), version,
            o.optInt("min_app_version_code", 0), str(o, "licence", at), str(o, "good_at", at), o.optString("not_good_at").take(500),
            o.optString("speed").take(300), o.optString("memory").take(300), strings(o.optJSONArray("serves")).take(16),
            o.optBoolean("enabled", true), strings(o.optJSONArray("replaces")).take(16), refs)
    }

    /** Why this app can't run [item] (plain words), or null when it can. Pure. */
    fun unsupported(item: Item, appVersionCode: Int, pipelines: Map<String, String> = PIPELINES, runtime: String = RUNTIME): String? = when {
        item.pipeline !in pipelines -> "this Bridgit version can't run ${item.pipeline} models; it needs an app update"
        pipelines[item.pipeline] != item.kind -> "its kind ${item.kind} doesn't match its pipeline"
        item.runtime != runtime -> "it needs the ${item.runtime} runtime; this Bridgit has $runtime"
        item.minAppVersionCode > appVersionCode -> "it needs Bridgit build ${item.minAppVersionCode} or later"
        else -> null
    }

    /**
     * The item that answers for [engine] on this app: enabled and runnable, and not replaced by another such item.
     * Null when there is none. Pure.
     */
    fun active(m: Manifest, engine: String, appVersionCode: Int, pipelines: Map<String, String> = PIPELINES, runtime: String = RUNTIME): Item? {
        val ok = m.items.filter { it.engine == engine && it.enabled && unsupported(it, appVersionCode, pipelines, runtime) == null }
        val replaced = ok.flatMap { it.replaces }.toSet()
        return ok.firstOrNull { it.id !in replaced }
    }

    /** Every engine name the manifest lists, in order. Pure. */
    fun engines(m: Manifest): List<String> = m.items.map { it.engine }.distinct()
}
