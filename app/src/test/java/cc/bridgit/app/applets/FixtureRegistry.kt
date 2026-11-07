package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.outbound.Outbound
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Registry HTTP response bodies. A catalog key deliberately differs from its package id. */
@Suppress("UNUSED_PARAMETER")
internal fun fixtureRegistry(app: BridgeApp, link: AppletPackage.Link, actor: Outbound.Actor): AppletFetch.Download {
    val path = URI(link.url).path.removePrefix("/v1/templates/")
    if (path == "catalog") return AppletFetch.Download("""{"format":"bridgit-applet-catalog/1","applets":[{"key":"tpl_chef","id":"chef-helper","revision":2,"name":"Chef’s Helper","summary":"Kitchen assistant"}],"next":null}""".toByteArray())
    val (file, revision) = when (path) {
        "tpl_chef/2" -> "chef-helper-1.0.1" to 2
        "insurance-inventory/latest" -> "insurance-inventory-3.0.0" to 3
        else -> throw AppletFetch.Failed("Missing fixture release: $path")
    }
    val definition = JSONObject(File("src/test/resources/applets/$file.json").readText())
    return AppletFetch.Download(JSONObject().put("key", path.substringBefore('/')).put("state","published")
        .put("revision",revision).put("definition",definition).toString().toByteArray())
}
