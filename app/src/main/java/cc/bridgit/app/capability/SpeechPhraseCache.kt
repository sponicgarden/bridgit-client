package cc.bridgit.app.capability

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private, disposable audio cache. Queue items always own a copy, never the cached file. */
internal class SpeechPhraseCache(private val root: File, private val maxEntries: Int = 32, private val maxBytes: Long = 8L * 1024 * 1024) {
    private fun file(voice: String, text: String): File {
        val bytes = (voice + "\u0000" + text).toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return File(root, "$key.wav")
    }
    @Synchronized fun restore(voice: String, text: String, out: File): Boolean = runCatching {
        val cached = file(voice, text)
        if (!cached.isFile || cached.length() == 0L) return false
        cached.copyTo(out, overwrite = true)
        cached.setLastModified(System.currentTimeMillis())
        true
    }.getOrDefault(false)
    @Synchronized fun remember(voice: String, text: String, source: File) {
        // A failed cache write must never turn successful synthesis into failed speech.
        runCatching {
            if (!source.isFile || source.length() !in 1..maxBytes) return
            root.mkdirs()
            val target = file(voice, text)
            val temp = File(root, ".${UUID.randomUUID()}.tmp")
            try { source.copyTo(temp, overwrite = true); check(temp.renameTo(target)) }
            finally { temp.delete() }
            val entries = root.listFiles().orEmpty().filter { it.isFile && it.extension == "wav" }.sortedByDescending { it.lastModified() }
            var bytes = 0L
            entries.forEachIndexed { index, entry ->
                bytes += entry.length()
                if (index >= maxEntries || bytes > maxBytes) entry.delete()
            }
        }
    }
}
