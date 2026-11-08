package cc.bridgit.app.link

import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.satellite.SatellitePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Linked Bridgits (docs/spec-linked-bridgits.md): the pure parts. A link joins two Bridgits and has two directions,
 * "in" (the other device uses this one; this device is the target) and "out" (this device uses the other one).
 * Each direction is on only while both devices opted in: the target with a service list, the user with the agents
 * that may reach it (LNK-03, LNK-04). Unit-tested.
 */
object LinkModel {
    enum class State(val wire: String) {
        OFF("off"), WAITING_FOR_TARGET("waiting_for_target"), WAITING_FOR_USER("waiting_for_user"), ON("on"), PAUSED("paused");
    }

    /** One device's half of a direction: whether it opted in, whether it is paused. */
    data class Half(val optedIn: Boolean, val paused: Boolean = false)

    /** LNK-03/04: a direction is on only while both halves exist and neither is paused. Null = not known (treated as absent). */
    fun state(target: Half?, user: Half?): State {
        val t = target?.optedIn == true; val u = user?.optedIn == true
        return when {
            t && u && (target!!.paused || user!!.paused) -> State.PAUSED
            t && u -> State.ON
            u -> State.WAITING_FOR_TARGET
            t -> State.WAITING_FOR_USER
            else -> State.OFF
        }
    }

    /** The capabilities that may pass a link (LNK-13), plus view (Home, applets, photo submission). */
    const val VIEW = "view"
    val SERVICES: Set<String> = SatellitePolicy.PHASE1_CAPS + VIEW
    /** LNK-14: what the UI and the operation suggest for a new direction. */
    val SUGGESTED = listOf("camera", "mic", "speaker", "interactions", VIEW)

    /** A service list as the link agent's grants: view is the portal grant plus the return paths. */
    fun capsFor(services: Set<String>): Set<String> = services.filter { it in SERVICES }.map { if (it == VIEW) "portal" else it }.toSet()

    /** The reverse of [capsFor]. A link agent always has explicit grants; null (legacy "all") reads as the phase-1 list. */
    fun servicesOf(caps: Set<String>?): Set<String> =
        (caps ?: SatellitePolicy.PHASE1_CAPS).map { if (it == "portal") VIEW else it }.filter { it in SERVICES }.toSet()

    /** Parses a services array; throws IllegalArgumentException naming the first unknown value. Empty is allowed. */
    fun parseServices(a: JSONArray?): Set<String> {
        if (a == null) return emptySet()
        require(a.length() <= 20) { "services has too many values" }
        return (0 until a.length()).map { i ->
            val s = a.opt(i) as? String ?: throw IllegalArgumentException("services must be strings")
            require(s in SERVICES) { "Unknown service $s. Use: ${SERVICES.sorted().joinToString()}" }
            s
        }.toSet()
    }

    /** Sorted, so two lists compare and log the same way. */
    fun json(services: Set<String>) = JSONArray(services.sorted())

    /** "camera, mic" for a log line. */
    fun words(services: Set<String>) = if (services.isEmpty()) "nothing" else services.sorted().joinToString(", ")
}

/** Who made a change (LNK-06): a person on a device, an agent on a device, an automatic rule, or the migration. */
data class Granter(val kind: String, val device: String?, val agentId: String? = null, val agentName: String? = null,
                   val rule: String? = null, val at: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("device", device ?: JSONObject.NULL)
        .put("agent_id", agentId ?: JSONObject.NULL).put("agent_name", agentName ?: JSONObject.NULL)
        .put("rule", rule ?: JSONObject.NULL).put("at", at)

    /** "Muse on kitchen-tab", "the owner on porch-cam", "migrated". */
    fun label(): String = when (kind) {
        AGENT -> "${agentName ?: agentId ?: "an agent"}${device?.let { " on $it" } ?: ""}"
        PERSON -> "the owner${device?.let { " on $it" } ?: ""}"
        RULE -> "rule ${rule ?: ""}".trim()
        else -> kind
    }

    companion object {
        const val PERSON = "person"; const val AGENT = "agent"; const val RULE = "rule"; const val MIGRATED = "migrated"
        fun person(device: String?) = Granter(PERSON, device)
        fun agent(id: String, name: String, device: String?) = Granter(AGENT, device, id, name)
        fun migrated(at: Long, device: String?) = Granter(MIGRATED, device, at = at)

        private fun clean(s: String?, max: Int) = s?.filterNot { it.isISOControl() }?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

        /** Parses one sent by the other device; never trusts more than the shape. Null when malformed. */
        fun fromJson(o: JSONObject?): Granter? {
            o ?: return null
            val kind = o.optString("kind").takeIf { it in setOf(PERSON, AGENT, RULE, MIGRATED) } ?: return null
            fun s(k: String, max: Int) = if (o.isNull(k)) null else clean(o.optString(k), max)
            return Granter(kind, s("device", 60), s("agent_id", 64), s("agent_name", 60), s("rule", 60), o.optLong("at").takeIf { it > 0 } ?: System.currentTimeMillis())
        }
    }
}

/**
 * Link codes (LNK-16): 8 Crockford base32 characters shown as K7QX-3MPA (40 bits), one use, 10 minutes, 5 wrong
 * tries. Only a hash is kept. Typed codes forgive case, spaces, dashes and the look-alikes I/L (1) and O (0).
 */
object LinkCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8
    const val VALID_MS = 10 * 60_000L
    const val MAX_TRIES = 5

    fun generate(rnd: SecureRandom = SecureRandom()): String = (1..LENGTH).map { ALPHABET[rnd.nextInt(32)] }.joinToString("")

    fun format(code: String): String = code.take(4) + "-" + code.drop(4)

    /** The canonical 8 characters, or null when it can't be a code. */
    fun normalise(input: String?): String? {
        val t = input?.uppercase()?.filterNot { it == '-' || it == ' ' } ?: return null
        val mapped = t.map { when (it) { 'I', 'L' -> '1'; 'O' -> '0'; else -> it } }.joinToString("")
        return mapped.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    /**
     * The code keys both devices compute from a canonical [code] and the target's registered [name]
     * (docs/spec-linked-bridgits.md §4.1): dk = PBKDF2-HMAC-SHA256(code, "bridgit-link-v1\n" + name, 100000, 64 bytes).
     * The backend sees only [Keys.codeHash]; [Keys.claimToken] is the bearer for POST /v1/link/claim.
     */
    class Keys(val dk: ByteArray) {
        val codeHash: String get() = hex(dk.copyOfRange(0, 32))
        val claimToken: String get() = "rly_" + hex(dk.copyOfRange(32, 64))
        /** Bound to the claiming install, so a proof seen in transit is useless to any other install. */
        fun proof(name: String, claimingInstall: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(dk.copyOfRange(0, 32), "HmacSHA256"))
            return hex(mac.doFinal("bridgit-link-resolve-v1\n$name\n$claimingInstall".toByteArray(Charsets.UTF_8)))
        }
    }

    fun keys(code: String, name: String): Keys {
        val spec = javax.crypto.spec.PBEKeySpec(code.toCharArray(), ("bridgit-link-v1\n" + name).toByteArray(Charsets.UTF_8), 100_000, 512)
        return Keys(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    /** SHA-256 hex, as the relay's token_hash and the target's stored claim-token hash. */
    fun sha256(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    val CLAIM_TOKEN_RE = Regex("rly_[0-9a-f]{64}")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

object LinkIds {
    val LINK_RE = Regex("lnk_[a-z2-7]{20}")
    val CODE_RE = Regex("lcd_[a-z2-7]{16}")
    private const val B32 = "abcdefghijklmnopqrstuvwxyz234567"
    private fun random(n: Int, rnd: SecureRandom = SecureRandom()) = (1..n).map { B32[rnd.nextInt(32)] }.joinToString("")
    fun newLink() = "lnk_" + random(20)
    fun newCode() = "lcd_" + random(16)

    /** A stable id for a link this device knew before the other device told it the shared one (migration). */
    fun provisional(seed: String): String = "lnk_" + MessageDigest.getInstance("SHA-256").digest(("lnkprov:" + seed).toByteArray())
        .take(20).joinToString("") { B32[(it.toInt() and 0xff) % 32].toString() }
}

/**
 * What a link agent may call on the device that issued it (LNK-11, LNK-12). Its grants are its direction's service
 * list, so the normal grant check already does the intersection; this adds the shape of a link: only calls that
 * pass a link, the return paths when view is on, and the phone-to-phone calls (each of which checks the role).
 */
object LinkPolicy {
    /** The phone-to-phone calls (LNK-47 and the kept /v1/satellite/… ones). They work while the link exists, whatever its directions. */
    val PLUMBING = setOf("/satellite/manifest", "/satellite/hub-info", "/satellite/unlink", "/satellite/hub/unlink",
        "/link/events", "/link/peers", "/link/disconnect")

    fun isPlumbing(spec: EndpointSpec) = spec.path in PLUMBING

    fun mayCall(spec: EndpointSpec, services: Set<String>): Boolean = isPlumbing(spec) ||
        (LinkModel.VIEW in services && spec.path in SatellitePolicy.RETURN_PATHS) ||
        (SatellitePolicy.forwardable(spec) && (spec.capability == "status" || spec.capability in services))
}
