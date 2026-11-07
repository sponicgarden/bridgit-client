package cc.bridgit.app.ui

import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** One-tap "@gmail.com"-style buttons under an email field, so nobody has to type the domain. */
object EmailSuffixChips {
    val DOMAINS = listOf("gmail.com", "yahoo.com", "outlook.com", "icloud.com", "hotmail.com")

    /** Keeps whatever is before the "@" and swaps in [domain]. */
    fun apply(text: String, domain: String): String = text.trim().substringBefore('@') + "@" + domain

    /** Adds a scrollable chip row right below [input] inside [parent]. */
    fun attach(parent: LinearLayout, input: EditText, index: Int = parent.indexOfChild(input.rootParentField(parent)) + 1) {
        val ctx = parent.context
        val group = ChipGroup(ctx).apply { isSingleLine = true }
        for (d in DOMAINS) {
            group.addView(Chip(ctx).apply {
                text = "@$d"
                setOnClickListener {
                    val name = input.text?.toString()?.trim()?.substringBefore('@').orEmpty()
                    input.setText(apply(name, d))
                    input.requestFocus()
                    // No name yet: put the cursor before the "@" so they type it next.
                    input.setSelection(if (name.isEmpty()) 0 else input.text?.length ?: 0)
                }
            })
        }
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        }
        parent.addView(scroll, index.coerceIn(0, parent.childCount))
    }

    /** The direct child of [parent] that holds [this] (the TextInputLayout around the field). */
    private fun android.view.View.rootParentField(parent: LinearLayout): android.view.View {
        var v: android.view.View = this
        while (v.parent != null && v.parent !== parent) v = v.parent as android.view.View
        return v
    }
}
