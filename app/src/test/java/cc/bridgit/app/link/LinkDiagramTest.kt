package cc.bridgit.app.link

import cc.bridgit.app.link.LinkDiagram.Arrow
import cc.bridgit.app.link.LinkModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0149: the link header's arrows. */
class LinkDiagramTest {
    @Test fun onIsLinkedPermittedAndEnabled() {
        val d = LinkDiagram.direction(State.ON, setOf("mic", "camera"))
        assertEquals(Arrow.ON, d.arrow); assertTrue(d.linked && d.permitted && d.enabled)
        assertEquals(listOf("camera", "mic"), d.services)
    }

    @Test fun pausedIsPermittedButNotEnabled() {
        val d = LinkDiagram.direction(State.PAUSED, setOf("view"))
        assertEquals(Arrow.PAUSED, d.arrow); assertTrue(d.linked && d.permitted); assertFalse(d.enabled)
    }

    @Test fun waitingIsLinkedButNotPermitted() {
        for (s in listOf(State.WAITING_FOR_TARGET, State.WAITING_FOR_USER)) {
            val d = LinkDiagram.direction(s, setOf("camera"))
            assertEquals(Arrow.WAITING, d.arrow); assertTrue(d.linked); assertFalse(d.permitted); assertFalse(d.enabled)
            assertEquals(listOf("camera"), d.services)
        }
    }

    @Test fun offHasNothingAndNoServices() {
        val d = LinkDiagram.direction(State.OFF, setOf("camera"))
        assertEquals(Arrow.OFF, d.arrow); assertFalse(d.linked || d.permitted || d.enabled); assertTrue(d.services.isEmpty())
    }

    @Test fun eachDirectionIsIndependent() {
        val m = LinkDiagram.model("Pixel", "cracky", State.ON, setOf("camera"), State.WAITING_FOR_TARGET, setOf("view"))
        assertEquals("Pixel", m.thisName); assertEquals("cracky", m.farName)
        assertEquals(Arrow.ON, m.out.arrow); assertEquals(Arrow.WAITING, m.inn.arrow)
    }

    @Test fun serviceOrderIsStable() {
        assertEquals(listOf("camera", "speaker", "gallery", "view", "zzz"), LinkDiagram.ordered(setOf("zzz", "view", "gallery", "speaker", "camera")))
    }
}
