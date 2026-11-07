package cc.bridgit.app.ui.more

import org.junit.Assert.*
import org.junit.Test

class MoreSearchTest {
    @Test fun typingFindsPartialWordsWithoutCaseOrAccentSensitivity() {
        assertTrue(MoreSearch.matches("  CAFE  ", "Café connections"))
        assertTrue(MoreSearch.matches("NOTIF", "Notification channels"))
    }

    @Test fun everyQueryWordMustMatchInAnyOrder() {
        assertTrue(MoreSearch.matches("key open", "OpenRouter API key"))
        assertTrue(MoreSearch.matches("  key\n open ", "OpenRouter API key"))
        assertFalse(MoreSearch.matches("key camera", "OpenRouter API key"))
        assertFalse(MoreSearch.matches("zzzzzz", "Settings"))
    }

    @Test fun clearingQueryMatchesAgain() {
        assertTrue(MoreSearch.matches(" \t ", "Settings"))
    }
}
