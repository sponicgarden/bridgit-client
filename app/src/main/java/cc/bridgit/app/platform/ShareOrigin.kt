package cc.bridgit.app.platform

import android.content.Context
import android.net.Uri

/** Refuses shared content:// URIs that point into Bridgit's own storage (its fileprovider and files providers). */
object ShareOrigin {
    /** True when [authority] belongs to this app: the package name itself or any "<package>.<name>" provider. */
    fun isOwnAuthority(authority: String?, packageName: String): Boolean {
        // Android may prefix the authority with a user id ("10@authority").
        val a = authority?.substringAfterLast('@')?.lowercase() ?: return false
        val p = packageName.lowercase()
        return a == p || a.startsWith("$p.")
    }

    /** True when [uri] is not a content URI, has no authority, or is served by a provider of this app. */
    fun isRefused(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        val authority = uri.authority ?: return true
        if (isOwnAuthority(authority, context.packageName)) return true
        return runCatching { context.packageManager.resolveContentProvider(authority.substringAfterLast('@'), 0)?.packageName == context.packageName }.getOrDefault(false)
    }
}
