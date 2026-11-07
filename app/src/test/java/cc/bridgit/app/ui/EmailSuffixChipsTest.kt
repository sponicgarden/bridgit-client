package cc.bridgit.app.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import cc.bridgit.app.databinding.SettingsDialogFieldBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class EmailSuffixChipsTest {
    @Test fun addsDomainToName() = assertEquals("sam@gmail.com", EmailSuffixChips.apply("sam", "gmail.com"))
    @Test fun replacesExistingDomain() = assertEquals("sam@yahoo.com", EmailSuffixChips.apply("sam@gmail.com", "yahoo.com"))
    @Test fun replacesHalfTypedDomain() = assertEquals("sam@outlook.com", EmailSuffixChips.apply(" sam@gma ", "outlook.com"))
    @Test fun emptyNameGivesBareDomain() = assertEquals("@icloud.com", EmailSuffixChips.apply("", "icloud.com"))

    /** The real email dialog layout: chips sit right under the field and fill it in when tapped. */
    @Test fun chipsUnderFieldFillTheAddress() {
        val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.google.android.material.R.style.Theme_Material3_Dark)
        val b = SettingsDialogFieldBinding.inflate(LayoutInflater.from(ctx))
        val root = b.root as LinearLayout
        b.input1.filters = arrayOf(android.text.InputFilter.LengthFilter(254)) // as both email dialogs set it
        EmailSuffixChips.attach(root, b.input1)
        val row = root.getChildAt(1)
        assertTrue(row is HorizontalScrollView)
        val group = (row as HorizontalScrollView).getChildAt(0) as ChipGroup
        val chips = (0 until group.childCount).map { group.getChildAt(it) as Chip }
        assertEquals(listOf("@gmail.com", "@yahoo.com", "@outlook.com", "@icloud.com", "@hotmail.com"), chips.map { it.text.toString() })

        chips[0].performClick()
        assertEquals("@gmail.com", b.input1.text.toString())
        assertEquals(0, b.input1.selectionStart) // cursor waits before the "@"

        b.input1.setText("sam")
        chips[0].performClick()
        assertEquals("sam@gmail.com", b.input1.text.toString())
        chips[1].performClick()
        assertEquals("sam@yahoo.com", b.input1.text.toString())
        assertEquals("sam@yahoo.com".length, b.input1.selectionStart)
    }
}
