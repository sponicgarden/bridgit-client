package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/**
 * A camera applet's privacy contract (A0451 slice 1.1, docs/plans/a0451-camera-applet-privacy.md): the package's
 * `privacy.camera` block says why it keeps camera material, which table rows justify keeping a photo, and for how
 * long. Pure: parsing, checks and the effective retention window. Nothing here stores or deletes yet.
 *
 * ```json
 * "privacy": { "camera": { "purpose": "mess notices", "keep_when": [{"table": "notices"}],
 *   "retention_days": 7, "never_kept": "Frames with no mess notice are dropped on the phone." } }
 * ```
 */
data class CameraPrivacy(val purpose: String, val keepWhenTables: Set<String>, val retentionDays: Int, val neverKept: String) {
    companion object {
        /** The longest any camera material may be kept (D3), whatever the package or a table says. */
        const val MAX_DAYS = 90
        const val MIN_DAYS = 1
        private val KEYS = setOf("purpose", "keep_when", "retention_days", "never_kept")
        private const val MAX_KEEP_WHEN = 16

        /**
         * The definition's `privacy.camera`, or null when it has none. Throws [AppletPackage.Bad] when the block is
         * malformed (unknown keys, wrong types); range and table checks are in [problems].
         */
        fun parse(definition: JSONObject): CameraPrivacy? {
            val privacy = definition.opt("privacy") ?: return null
            if (privacy !is JSONObject) bad("privacy must be an object.")
            privacy.keys().forEach { if (it != "camera") bad("privacy.$it isn't a privacy section (use camera).") }
            val c = privacy.opt("camera") ?: return null
            if (c !is JSONObject) bad("privacy.camera must be {purpose, keep_when, retention_days, never_kept}.")
            c.keys().forEach { if (it !in KEYS) bad("privacy.camera.$it isn't part of privacy.camera (use ${KEYS.joinToString()}).") }
            val purpose = text(c, "purpose", 200)
            val neverKept = text(c, "never_kept", 500)
            val kw = c.opt("keep_when") as? JSONArray ?: bad("privacy.camera.keep_when must be a list of {table}.")
            if (kw.length() > MAX_KEEP_WHEN) bad("privacy.camera.keep_when lists at most $MAX_KEEP_WHEN tables.")
            val tables = LinkedHashSet<String>()
            for (i in 0 until kw.length()) {
                val e = kw.opt(i) as? JSONObject
                val t = e?.opt("table") as? String
                if (e == null || e.length() != 1 || t.isNullOrBlank()) bad("privacy.camera.keep_when[$i] must be {\"table\": \"<id in processing.tables>\"}.")
                tables += t
            }
            val raw = c.opt("retention_days")
            val days = when (raw) {
                is Int -> raw
                is Long -> raw.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt() else bad("privacy.camera.retention_days must be a whole number of days.")
                else -> bad("privacy.camera.retention_days must be a whole number of days.")
            }
            return CameraPrivacy(purpose, tables, days, neverKept)
        }

        /** Whether the plan uses a camera: a flow `photo` step, a camera button, or a watch that reads a camera. */
        fun isCameraApplet(plan: AppletPackage.Plan): Boolean =
            plan.flows.any { (_, f) -> steps(f).any { it.optString("type") == "photo" } } ||
                plan.card?.usesCamera == true || cameraButtonTables(plan).isNotEmpty() ||
                plan.watches.any { (_, w) -> cameraWatch(w) }

        /**
         * What is wrong with the plan's camera privacy, in words an author can act on; empty when nothing is.
         * A camera applet without the block; `keep_when` naming a table the package doesn't declare; a kept photo or
         * camera button writing to a table outside `keep_when`; `retention_days` outside 1-[MAX_DAYS].
         */
        fun problems(plan: AppletPackage.Plan, privacy: CameraPrivacy?): List<String> {
            if (privacy == null) return if (isCameraApplet(plan)) listOf("The applet uses a camera, so it needs privacy.camera {purpose, keep_when, retention_days, never_kept}.") else emptyList()
            val out = mutableListOf<String>()
            if (privacy.retentionDays !in MIN_DAYS..MAX_DAYS) out += "privacy.camera.retention_days must be $MIN_DAYS-$MAX_DAYS."
            val declared = plan.tables.map { it.id }.toSet()
            for (t in privacy.keepWhenTables) if (t !in declared) out += "privacy.camera.keep_when: table $t isn't in processing.tables."
            for (t in keptPhotoTables(plan)) if (t !in privacy.keepWhenTables) out += "A kept photo is written to table $t, which isn't in privacy.camera.keep_when."
            for (t in cameraButtonTables(plan)) if (t !in privacy.keepWhenTables) out += "A camera button writes to table $t, which isn't in privacy.camera.keep_when."
            return out.distinct()
        }

        /**
         * How many days [table]'s camera material is kept: the smallest of the package's window, the backend's
         * override and the table's keep_days, never more than [MAX_DAYS]. The backend can only shorten it (D3).
         */
        fun effectiveDays(privacy: CameraPrivacy, table: AppletTables.Table?, backendOverride: Int?): Int =
            minOf(privacy.retentionDays.coerceIn(MIN_DAYS, MAX_DAYS), backendOverride?.coerceAtLeast(MIN_DAYS) ?: MAX_DAYS, table?.keepDays ?: MAX_DAYS)

        /** Tables that `record`/`update` steps write a kept photo (`{"$step": "<keep photo id>.kept"}`) to. */
        fun keptPhotoTables(plan: AppletPackage.Plan): Set<String> {
            val out = LinkedHashSet<String>()
            for ((_, f) in plan.flows) {
                val all = steps(f)
                val kept = all.filter { it.optString("type") == "photo" && it.optBoolean("keep") }.mapNotNull { it.optString("id").takeIf(String::isNotEmpty) }.toSet()
                if (kept.isEmpty()) continue
                for (s in all) {
                    val type = s.optString("type")
                    if (type != "record" && type != "update") continue
                    if (stepRefs(s.opt(if (type == "update") "set" else "row")).any { r -> r.substringBefore('.') in kept }) s.optString("table").takeIf(String::isNotEmpty)?.let { out += it }
                }
            }
            return out
        }

        /** Tables a card's camera buttons (`{"do": {"camera": {"table": …}}}`) write their photos to. */
        fun cameraButtonTables(plan: AppletPackage.Plan): Set<String> {
            val out = LinkedHashSet<String>()
            fun walk(v: Any?) {
                when (v) {
                    is JSONObject -> {
                        (v.opt("do") as? JSONObject)?.optJSONObject("camera")?.optString("table")?.takeIf(String::isNotEmpty)?.let { out += it }
                        v.keys().forEach { walk(v.opt(it)) }
                    }
                    is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                }
            }
            walk(plan.card?.piecesRaw)
            return out
        }

        private fun cameraWatch(w: JSONObject): Boolean {
            val data = w.optJSONArray("data")
            return (data != null && (0 until data.length()).any { data.opt(it) == "camera" }) || (w.optJSONArray("additional_cameras")?.length() ?: 0) > 0
        }

        /** Every step of a flow, including steps nested in branches. */
        private fun steps(flow: JSONObject): List<JSONObject> {
            val out = mutableListOf<JSONObject>()
            fun walk(v: Any?) {
                when (v) {
                    is JSONObject -> { if (v.has("type") && v.opt("type") is String) out += v; v.keys().forEach { if (it != "trigger") walk(v.opt(it)) } }
                    is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                }
            }
            walk(flow.opt("steps"))
            return out
        }

        private fun stepRefs(v: Any?): List<String> = when (v) {
            is JSONObject -> if (v.opt("\$step") is String) listOf(v.getString("\$step")) else v.keys().asSequence().flatMap { stepRefs(v.opt(it)) }.toList()
            is JSONArray -> (0 until v.length()).flatMap { stepRefs(v.opt(it)) }
            else -> emptyList()
        }

        private fun text(o: JSONObject, key: String, max: Int): String {
            val v = o.opt(key) as? String
            if (v.isNullOrBlank() || v.length > max) bad("privacy.camera.$key is required (at most $max characters).")
            return v
        }

        private fun bad(message: String): Nothing = throw AppletPackage.Bad(message)
    }
}
