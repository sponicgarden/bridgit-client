package cc.bridgit.app.ui.more

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiDocs
import cc.bridgit.app.databinding.FragmentWhatCanBinding
import cc.bridgit.app.databinding.WhatcanCardBinding
import cc.bridgit.app.databinding.WhatcanHeaderBinding
import cc.bridgit.app.databinding.WhatcanSectionBinding
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M

/** More → What can Bridgit do? One card per feature with a use case, grouped by when it arrives. */
class WhatCanFragment : Fragment() {
    private var _b: FragmentWhatCanBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentWhatCanBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Inset on the root, not the toolbar: padding the toolbar squeezed its subtitle into the list.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.list) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + v.context.resources.getDimensionPixelSize(R.dimen.whatcan_gap)); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val abilities = WhatCanData.all.filter { it.kind == WhatCanData.Kind.ABILITY }
        b.toolbar.subtitle = getString(R.string.whatcan_subtitle, abilities.count { it.stage == WhatCanData.Stage.NOW }, abilities.size)
        // Two parts: what agents can do with the phone, then how Bridgit works (owner request).
        val rows = mutableListOf<Any>()
        for ((kind, title, intro) in listOf(
            Triple(WhatCanData.Kind.ABILITY, R.string.whatcan_section_abilities, R.string.whatcan_section_abilities_intro),
            Triple(WhatCanData.Kind.MECHANIC, R.string.whatcan_section_mechanics, R.string.whatcan_section_mechanics_intro))) {
            rows += Section(getString(title), getString(intro))
            var last: String? = null
            for (f in WhatCanData.all.filter { it.kind == kind }) {
                val group = groupOf(f.stage)
                if (group != last) { rows += group; last = group }
                rows += f
            }
        }
        b.list.adapter = Adapter(rows)
    }

    private fun groupOf(s: WhatCanData.Stage) = getString(when (s) {
        WhatCanData.Stage.NOW -> R.string.whatcan_group_now
        WhatCanData.Stage.P2 -> R.string.whatcan_group_next
        WhatCanData.Stage.LATER, WhatCanData.Stage.IDEA -> R.string.whatcan_group_ideas
        else -> R.string.whatcan_group_planned
    })

    private data class Section(val title: String, val intro: String)

    private class Adapter(val rows: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        class H(val b: WhatcanHeaderBinding) : RecyclerView.ViewHolder(b.root)
        class C(val b: WhatcanCardBinding) : RecyclerView.ViewHolder(b.root)
        class S(val b: WhatcanSectionBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) = when (rows[position]) { is String -> 0; is Section -> 2; else -> 1 }
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> H(WhatcanHeaderBinding.inflate(inf, parent, false))
                2 -> S(WhatcanSectionBinding.inflate(inf, parent, false))
                else -> C(WhatcanCardBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (h is H) { h.b.root.text = row as String; return }
            if (h is S) { val sec = row as Section; h.b.title.text = sec.title; h.b.intro.text = sec.intro; return }
            val f = row as WhatCanData.Feature
            val b = (h as C).b
            val ctx = b.root.context
            b.title.text = f.title
            b.what.text = f.what
            b.useCase.text = f.useCase
            val opts = WhatCanData.options[f.title].orEmpty()
            b.optionsPanel.isVisible = opts.isNotEmpty()
            b.options.text = bullets(opts, ctx.themeColor(M.attr.colorPrimary), ctx.resources.displayMetrics.density)
            val pic = pictureOf(ctx, f.title)
            b.picture.isVisible = pic != 0
            if (pic != 0) b.picture.setImageResource(pic)
            b.stage.text = f.stage.label
            b.ref.text = ctx.getString(R.string.whatcan_ref, f.ref)
            // Spec references are for the technical part only; the use cases stay plain.
            b.ref.isVisible = f.ref.isNotEmpty() && f.kind == WhatCanData.Kind.MECHANIC
            val (bg, fg) = when (f.stage) {
                WhatCanData.Stage.NOW -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
                WhatCanData.Stage.P2 -> M.attr.colorPrimaryContainer to M.attr.colorOnPrimaryContainer
                WhatCanData.Stage.IDEA -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
                else -> M.attr.colorSurfaceContainerHighest to M.attr.colorOnSurfaceVariant
            }
            b.stage.background.mutate().setTint(ctx.themeColor(bg))
            b.stage.setTextColor(ctx.themeColor(fg))
            // Owner request (1.5.1): share a link to this card's section on the public API page.
            b.share.contentDescription = ctx.getString(R.string.whatcan_share, f.title)
            b.share.setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Bridgit: ${f.title}")
                    .putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.whatcan_share_text, f.title, ApiDocs.link(f.title)))
                ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.whatcan_share_title, f.title)))
            }
            b.root.contentDescription = "${f.title}. ${f.stage.label}. ${f.what}" +
                (if (opts.isNotEmpty()) " Options: " + opts.joinToString("; ") + "." else "") + " For example: ${f.useCase}"
        }
    }

    private companion object {
        /** One paragraph per option with a round blue bullet; wrapped lines line up with the text, not the bullet. */
        fun bullets(items: List<String>, color: Int, density: Float): CharSequence {
            val sb = SpannableStringBuilder()
            items.forEachIndexed { i, item ->
                val start = sb.length
                sb.append(item)
                if (i < items.lastIndex) sb.append('\n')
                sb.setSpan(BulletSpan((10 * density).toInt(), color, (2.5f * density).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sb
        }

        /**
         * The card's picture, drawable-nodpi/whatcan_<slug>: the title in lower case, every run of other characters
         * one underscore (the prompt file's hyphenated names with underscores). 0 when there is none.
         * Looked up by name, so turning on resource shrinking would need these kept in a keep.xml.
         */
        fun pictureOf(ctx: Context, title: String): Int {
            val slug = WhatCanData.stableTitle(title).lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            @Suppress("DiscouragedApi")
            return ctx.resources.getIdentifier("whatcan_$slug", "drawable", ctx.packageName)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
