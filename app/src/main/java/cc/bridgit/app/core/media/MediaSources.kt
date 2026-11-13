package cc.bridgit.app.core.media

import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.capture.CaptureRules
import cc.bridgit.app.core.contract.ContractVocab
import org.json.JSONObject

/**
 * core.media provenance and arrival (architecture 6.2-6.3, A0270): where a library item came from and whether it has
 * fully arrived. The rule is contracts/client-core/vocab/media-sources.json, read from the generated
 * [ContractVocab] table; the pack's cases run against this in MediaSourcesTest. Pure Kotlin.
 */
object MediaSources {
    data class Source(val kind: String, val app: String?, val folder: String?) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind).apply { app?.let { put("app", it) }; folder?.let { put("folder", it) } }
    }
    data class Arrival(val source: Source, val complete: Boolean)

    /** The sync stability window (limits.json media_sync_stable_ms, architecture 6.3). */
    const val SYNC_STABLE_MS = 10_000L
    const val SYNC = "sync"

    /**
     * [ownerPackage] and [relativePath] are MediaStore's owner_package_name and relative_path; null or empty is unknown.
     * [modifiedSeconds] is date_modified (epoch seconds, null when the device didn't return it) and [nowSeconds] the
     * current time; complete follows [arrived] without the file checks.
     */
    fun classify(ownerPackage: String?, relativePath: String?, pending: Boolean?, modifiedSeconds: Long?, nowSeconds: Long): Arrival {
        val pkg = ownerPackage?.takeIf { it.isNotEmpty() }
        val folder = relativePath?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val p = folder?.let { "/${it.lowercase()}/" }
        val hit = ContractVocab.MEDIA_SOURCE_RULES.firstOrNull { r ->
            (pkg != null && pkg in r.packages) ||
                (p != null && (r.pathPrefixes.any { p.startsWith(it) } || r.pathContains.any { p.contains(it) }))
        }
        val kind = hit?.kind ?: if (pkg != null || folder != null) ContractVocab.MEDIA_SOURCE_NO_MATCH else ContractVocab.MEDIA_SOURCE_NOTHING_KNOWN
        return Arrival(Source(kind, pkg, folder), arrived(pending, null, null, false, kind, modifiedSeconds, nowSeconds) != false)
    }

    /**
     * The one completeness rule the lists and the extraction probe share (6.3). False while MediaStore marks the item
     * pending; with [checkFile] (videos), while its name is empty or its size is 0 or unknown; and, for a [kind] "sync"
     * item MediaStore does not mark pending (sync tools often write in place without is_pending), while its
     * date_modified [modifiedSeconds] is less than [SYNC_STABLE_MS] from [nowSeconds] either side ([settling]). Null
     * when the device gave no is_pending and nothing else shows it incomplete: a list shows that as complete,
     * extraction goes ahead. Pure: the caller passes the clock.
     */
    fun arrived(pending: Boolean?, name: String?, sizeBytes: Long?, checkFile: Boolean, kind: String?, modifiedSeconds: Long?, nowSeconds: Long): Boolean? = when {
        pending == true -> false
        checkFile && (name.isNullOrEmpty() || (sizeBytes ?: 0L) <= 0L) -> false
        settling(kind, modifiedSeconds, nowSeconds) -> false
        pending == null -> null
        else -> true
    }

    /**
     * A sync item modified within the stability window of now. Either side, so a small clock skew still counts as
     * recent while a modified time far in the future (a sync tool keeping a remote time) never holds the item back. A
     * null or non-positive [modifiedSeconds] (date_modified not returned) never settles: the rule is not applied.
     */
    fun settling(kind: String?, modifiedSeconds: Long?, nowSeconds: Long): Boolean =
        kind == SYNC && modifiedSeconds != null && modifiedSeconds > 0 && Math.abs(nowSeconds - modifiedSeconds) * 1000L < SYNC_STABLE_MS
}

/**
 * The two media scopes (architecture 6.1). library: the owner's photos and videos, read through the gallery grants.
 * captures: what an agent or applet captured, fetched through the capability that recorded it ([CaptureRules.canFetch]).
 * No capability belongs to both, so a grant on one never reaches the other.
 */
object MediaScopes {
    const val LIBRARY = "library"
    const val CAPTURES = "captures"
    val LIBRARY_GRANTS = setOf("gallery", "gallery_video")
    /** Provenance shows which apps the owner uses, so it goes only to agents with the library (gallery) grant (6.2). */
    const val PROVENANCE_GRANT = "gallery"

    /** The captures store's own capability (list, fetch, storage choice) plus the kinds that record into it. */
    val CAPTURE_GRANTS: Set<String> = CaptureRules.KINDS.toSet() + "captures"

    fun scopeOf(capability: String): String? = when (capability) {
        in LIBRARY_GRANTS -> LIBRARY
        in CAPTURE_GRANTS -> CAPTURES
        else -> null
    }

    /** Whether a list item carries `source`: the owner's gallery switch is on and the caller holds the grant. */
    fun showsSource(gallerySwitchOn: Boolean, allows: (String) -> Boolean): Boolean = gallerySwitchOn && allows(PROVENANCE_GRANT)

    /** Extraction (a frame, OCR input) on an item still arriving answers 409 media_incomplete (6.3). */
    fun requireComplete(complete: Boolean?) {
        if (complete == false) throw ErrorCatalog.error("media_incomplete",
            "This item is still arriving. Wait a few seconds and retry; include_pending=true on the list shows complete:false until it is done.")
    }
}
