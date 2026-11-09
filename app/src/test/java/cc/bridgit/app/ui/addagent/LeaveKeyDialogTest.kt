package cc.bridgit.app.ui.addagent

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A0217: the leave dialog on the key screen (new agent and Rotate) is a clear two-way choice. */
class LeaveKeyDialogTest {
    private fun strings(): Map<String, String> =
        Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>")
            .findAll(File("src/main/res/values/strings_agents.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun dialog(): String {
        val src = File("src/main/java/cc/bridgit/app/ui/addagent/AddAgentFragment.kt").readText()
        val start = src.indexOf("private fun onClose()")
        assertTrue(start >= 0)
        return src.substring(start, src.indexOf(".show()", start))
    }

    @Test fun buttonsSayWhatTheyDo() {
        val s = strings()
        assertEquals("Copy key", s["addagent_leave_copy"])
        assertEquals("Leave without copying", s["addagent_leave"])
        assertFalse("no ambiguous third choice", s.containsKey("addagent_leave_stay"))
    }

    @Test fun copyKeepsTheScreenOpenAndConfirms() {
        val positive = dialog().lines().first { "addagent_leave_copy" in it }
        assertFalse("Copy must not close the only view of the key", "leave()" in positive)
        assertFalse("Copy must show the usual confirmation", "quiet" in positive)
    }

    @Test fun onlyTheExplicitButtonLeaves() {
        val d = dialog()
        assertFalse(".setNeutralButton" in d)
        assertTrue(d.lines().any { ".setNegativeButton(R.string.addagent_leave)" in it && "leave()" in it })
    }
}
