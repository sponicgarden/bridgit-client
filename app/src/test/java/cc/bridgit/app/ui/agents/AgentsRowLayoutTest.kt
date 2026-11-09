package cc.bridgit.app.ui.agents

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import cc.bridgit.app.R
import cc.bridgit.app.databinding.AgentsRowBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A0140: the Agents list is the authorization audit screen, so an agent's full name, connection and
 * status must stay readable. Names were cut to "Mu…" because the powers badge sat beside the name and
 * every line was limited to one ellipsized line.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp")
class AgentsRowLayoutTest {
    private fun TextView.ellipsized(): Boolean {
        val l = layout ?: return false
        return (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
    }

    @Test fun fullNameConnectionAndStatusStayReadableBesideBadgeAndPauseButton() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Bridgit)
        val density = context.resources.displayMetrics.density
        for (cardDp in listOf(328, 240)) for (scale in listOf(1f, 1.5f)) {
            val row = AgentsRowBinding.inflate(LayoutInflater.from(context))
            row.name.text = "Claude Code on the BlackbookPro16 issue worker"
            row.developer.text = context.getString(R.string.agents_badge_both)
            row.developer.visibility = View.VISIBLE
            row.connection.text = "claude-fable-5-1 · Claude Code · Connector: the relay can read what this agent sends"
            row.line2.text = "Tailscale · seen 1h ago · 42 today"
            for (v in listOf(row.name, row.developer, row.connection, row.line2)) v.textSize = v.textSize * scale
            val width = (cardDp * density).toInt()
            row.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.root.layout(0, 0, width, row.root.measuredHeight)

            val at = "at ${cardDp}dp x$scale"
            for (v in listOf(row.name, row.connection, row.line2, row.developer)) {
                assertTrue("${v.text} must not be ellipsized $at", !v.ellipsized())
                assertTrue("${v.text} must not be clipped $at", v.height >= v.layout.height)
            }
            assertTrue("A long name wraps instead of being cut $at", row.name.layout.lineCount > 1)
            // The badge never shares the name's line, so it cannot take the name's width.
            assertEquals("Name uses the full text column $at", row.info.width, row.name.width)
            assertTrue("Badge sits below the name $at", row.developer.top >= row.name.bottom)
            assertTrue("Badge fits the column $at", row.developer.right <= row.info.width)
            assertTrue("Text column ends before the pause button $at", row.info.right <= row.pause.left)
            assertTrue("Pause button keeps its touch target $at", row.pause.width >= (48 * density).toInt())
        }
    }
}
