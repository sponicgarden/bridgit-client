package cc.bridgit.app.applets

import android.graphics.BitmapFactory
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.SafeHttp
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.outbound.Outbound
import org.json.JSONObject
import java.io.File

/** Private per-install bytes. Only the installed package definition chooses which hashes are readable. */
internal class AppletMedia(private val root: File, private val download: (String, Int, File) -> Unit) {
    constructor(app: BridgeApp) : this(File(app.noBackupFilesDir, "applet-package-media"), { url, size, file ->
        val path = java.net.URI(url).rawPath
        val headers = InstallIdentity.headers(app, "GET", path, ByteArray(0)) + ("Accept" to "application/octet-stream")
        val result = Outbound.on(Outbound.Actor.OWNER, Outbound.Hint("Bridgit applet registry", "Download reviewed package image")) {
            SafeHttp.get(url, SafeHttp.Policy.PUBLIC, 30000, size.toLong(), headers = headers, out = file)
        }
        if (result.status != 200) throw AppletFetch.Failed("Package image download returned HTTP ${result.status}.")
    })

    private fun directory(instance: String): File {
        require(instance.matches(Regex("ai[a-f0-9]{14}")))
        return File(root, instance)
    }

    private fun verified(file: File, media: AppletPackage.Media): Boolean {
        if (!file.isFile || file.length() != media.size.toLong()) return false
        val bytes = file.readBytes()
        if (AppletPackage.sha256(bytes) != media.digest || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
            bytes[bytes.lastIndex-1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) return false
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
        return info.outMimeType == "image/jpeg" && info.outWidth in 1..4096 && info.outHeight in 1..4096
    }

    fun prepare(instance: String, definition: JSONObject, source: JSONObject) {
        val media = AppletPackage.detachedMedia(definition)
        if (media.isEmpty()) return
        val dir = directory(instance)
        if (!dir.isDirectory && !dir.mkdirs()) throw AppletFetch.Failed("Cannot store package images.")
        for ((name, descriptor) in media) {
            val file = File(dir, descriptor.digest)
            if (verified(file, descriptor)) continue
            val revision = source.opt("revision")?.toString()?.toIntOrNull()
            val pin = AppletPackage.registryLink(source.optString("template"), revision)
                ?: throw AppletFetch.Failed("Open an exact registry revision to download $name.")
            val tmp = File.createTempFile("download-", ".tmp", dir)
            try {
                download("${pin.url}/assets/$name", descriptor.size, tmp)
                if (!verified(tmp, descriptor)) throw AppletFetch.Failed("Package image $name failed verification.")
                if (!tmp.renameTo(file)) throw AppletFetch.Failed("Could not save package image $name.")
            } finally { tmp.delete() }
        }
    }

    fun read(instance: JSONObject, name: String): File {
        val descriptor = AppletPackage.detachedMedia(instance.getJSONObject("definition"))[name]
            ?: throw AppletFetch.Failed("Unknown image in this package.")
        val file = File(directory(instance.getString("id")), descriptor.digest)
        if (!verified(file, descriptor)) throw AppletFetch.Failed("Installed image failed verification.")
        return file
    }

    fun recover(installed: (String) -> JSONObject?) {
        for (dir in root.listFiles().orEmpty()) {
            if (dir.isDirectory && dir.name.matches(Regex("ai[a-f0-9]{14}"))) prune(dir.name, installed(dir.name))
        }
    }

    fun prune(instance: String, installed: JSONObject?) {
        val dir = directory(instance)
        if (!dir.isDirectory) return
        val keep = installed?.let { AppletPackage.detachedMedia(it).values.map { asset -> asset.digest }.toSet() }.orEmpty()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        if (keep.isEmpty()) dir.delete()
    }
}
