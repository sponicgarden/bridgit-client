package cc.bridgit.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android 10 and 11 can't read an arc whose flags are packed together (`a1 1 0 00-1 1`), and the
 * app crashes when it draws that icon. Bridgit supports Android 10+ (minSdk 29), so every arc in
 * every drawable must spell its two flags out on their own (`a1 1 0 0 0 -1 1`).
 */
class VectorPathCompatTest {
    private val num = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    /** Number of arc arguments read the way Android 11 does: every value, flags included, is a full number. */
    private fun oldParserCount(args: String) = num.findAll(args).count()

    @Test fun `arc flags are written out in every vector drawable`() {
        val bad = mutableListOf<String>()
        File("src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { f ->
            Regex("""pathData="([^"]*)"""").findAll(f.readText()).forEach { p ->
                Regex("""[aA]([^a-zA-Z]*)""").findAll(p.groupValues[1]).forEach { a ->
                    if (oldParserCount(a.groupValues[1]) % 7 != 0) bad += "${f.path}: a${a.groupValues[1].take(40)}"
                }
            }
        }
        assertTrue("Packed arc flags crash Android 10/11:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
