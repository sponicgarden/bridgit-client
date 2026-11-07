package cc.bridgit.app.ui.interactions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import cc.bridgit.app.R
import cc.bridgit.app.databinding.InteractionsControlChoiceBinding
import cc.bridgit.app.databinding.InteractionsControlLinkBinding
import cc.bridgit.app.databinding.InteractionsControlTextBinding
import cc.bridgit.app.interact.Control
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.interact.InteractionRules
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M

/**
 * What the owner has typed or picked but not sent yet, per interaction and control, so a list refresh or a
 * rebind never wipes a half-written answer.
 */
class Drafts {
    private val map = HashMap<String, String>()
    fun get(id: Long, control: String): String? = map["$id/$control"]
    fun set(id: Long, control: String, v: String?) { if (v.isNullOrEmpty()) map.remove("$id/$control") else map["$id/$control"] = v }
    fun clear(id: Long) { map.keys.removeAll { it.startsWith("$id/") } }

    /** The text and choice values for [i] (blank text left out), as InteractionStore.answer wants them. */
    fun values(i: Interaction): Map<String, String> = i.controls.mapNotNull { c ->
        when (c) {
            is Control.Text -> get(i.id, c.id)?.trim()?.takeIf { it.isNotEmpty() }?.let { c.id to it }
            is Control.Choice -> get(i.id, c.id)?.let { c.id to it }
            else -> null
        }
    }.toMap()
}

/** Shared pieces of the Interactions list and detail (D26, UI-15, UI-16). */
object InteractionUi {
    fun isQuestion(i: Interaction) = i.kind == IKind.QUESTION || i.kind == IKind.HTML

    /** Still needs the owner: open, or a question that expired unanswered (it can be answered late or closed). */
    fun waiting(i: Interaction) = i.status.open || (isQuestion(i) && i.status == IStatus.EXPIRED)

    /** Rows rebind only when something the owner can see changed (Interaction holds JSON and controls compared by reference). */
    fun contentKey(i: Interaction) = listOf(i.status, i.seenAt, i.answeredAt, i.closedAt, i.title, i.text, i.image, i.agent, i.answer?.toString(),
        i.stack, i.stackIndex, i.stackSize, i.stackAnswered)

    /** #0005: one of 2+ cards that swipe together (a stack of one is a plain card). */
    fun isStack(i: Interaction) = i.stack != null && i.stackSize > 1

    /** #0003: the badge on the avatar, and what it says to TalkBack. */
    fun kindIcon(i: Interaction): Int = when {
        isStack(i) -> R.drawable.interactions_ic_kind_stack
        i.kind == IKind.HTML || i.kind == IKind.PIECES -> R.drawable.interactions_ic_kind_card
        i.kind == IKind.QUESTION -> R.drawable.interactions_ic_kind_question
        i.kind == IKind.TEXT -> R.drawable.interactions_ic_kind_text
        else -> R.drawable.interactions_ic_kind_notice
    }

    fun kindLabel(ctx: Context, i: Interaction): String = ctx.getString(when {
        isStack(i) -> R.string.interactions_kind_stack
        i.kind == IKind.HTML || i.kind == IKind.PIECES -> R.string.interactions_kind_card
        i.kind == IKind.QUESTION -> R.string.interactions_kind_question
        i.kind == IKind.TEXT -> R.string.interactions_kind_text
        else -> R.string.interactions_kind_notice
    })

    /** #0003: the row's second line. The agent's text first line, else what kind of thing it is (and stack progress). */
    fun preview(ctx: Context, i: Interaction): String {
        val text = i.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        return when {
            isStack(i) -> ctx.getString(R.string.interactions_stack_progress, i.stackAnswered, i.stackSize)
            text != null -> text
            i.kind == IKind.HTML || i.kind == IKind.PIECES -> ctx.getString(R.string.interactions_preview_card)
            i.kind == IKind.TEXT -> ctx.getString(R.string.interactions_preview_text)
            i.kind == IKind.QUESTION -> i.controls.filter { it !is Control.Link }.joinToString(" · ") { it.label }
            else -> kindLabel(ctx, i)
        }
    }

    /** #0005: one dot per card; the current one is a wider pill, answered ones green, closed ones faint. */
    fun buildDots(row: LinearLayout, stack: List<Interaction>, at: Int) {
        row.removeAllViews()
        val ctx = row.context
        stack.forEachIndexed { n, c ->
            val color = when {
                n == at -> ctx.themeColor(M.attr.colorPrimary)
                c.status == IStatus.ANSWERED -> ctx.themeColor(R.attr.colorSuccess)
                waiting(c) -> ctx.themeColor(M.attr.colorOutline)
                else -> ctx.themeColor(M.attr.colorOutlineVariant)
            }
            row.addView(View(ctx).apply {
                background = GradientDrawable().apply { cornerRadius = ctx.dp(3).toFloat(); setColor(color) }
            }, LinearLayout.LayoutParams(ctx.dp(if (n == at) 18 else 6), ctx.dp(6)).apply { marginStart = ctx.dp(3); marginEnd = ctx.dp(3) })
        }
    }

    fun agentName(i: Interaction) = i.agent.ifBlank { "Your control agent" }

    /** Questions open by default; everything else starts collapsed to one line (D26). */
    fun defaultExpanded(i: Interaction) = isQuestion(i) && waiting(i)

    fun statusLabel(ctx: Context, s: IStatus): String = ctx.getString(when (s) {
        IStatus.PENDING -> R.string.interactions_status_waiting
        IStatus.SEEN -> R.string.interactions_status_seen
        IStatus.ANSWERED -> R.string.interactions_status_answered
        IStatus.DISMISSED -> R.string.interactions_status_closed
        IStatus.EXPIRED -> R.string.interactions_status_expired
        IStatus.CANCELLED -> R.string.interactions_status_cancelled
    })

    /** Status chip: always a word, colour is never the only signal (UI-29). */
    fun styleStatus(v: TextView, s: IStatus, small: Boolean = false) {
        val ctx = v.context
        val (bg, fg) = when (s) {
            IStatus.PENDING -> M.attr.colorPrimaryContainer to M.attr.colorOnPrimaryContainer
            IStatus.SEEN -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
            IStatus.ANSWERED -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            else -> M.attr.colorSurfaceContainerHigh to M.attr.colorOnSurfaceVariant
        }
        v.background = GradientDrawable().apply { cornerRadius = ctx.dp(if (small) 6 else 8).toFloat(); setColor(ctx.themeColor(bg)) }
        v.setTextColor(ctx.themeColor(fg))
        v.text = statusLabel(ctx, s)
        // #0003: a word and an icon, so Waiting and Answered read at a glance.
        val icon = when (s) {
            IStatus.PENDING, IStatus.SEEN -> R.drawable.interactions_ic_waiting
            IStatus.ANSWERED -> R.drawable.ic_check
            else -> 0
        }
        val d = if (icon == 0) null else androidx.core.content.ContextCompat.getDrawable(ctx, icon)?.mutate()?.apply {
            val e = ctx.dp(if (small) 11 else 14); setTint(ctx.themeColor(fg)); setBounds(0, 0, e, e)
        }
        v.setCompoundDrawablesRelative(d, null, null, null)
        v.compoundDrawablePadding = ctx.dp(if (small) 3 else 4)
    }

    /** "**Grok** asks: Pay this invoice?" or "**Claude**: Backup finished". */
    fun headline(ctx: Context, i: Interaction): CharSequence {
        val agent = agentName(i)
        val s = if (isStack(i)) ctx.getString(R.string.interactions_stack_headline, agent, i.stackSize, i.title) else ctx.getString(when (i.kind) {
            IKind.HTML, IKind.PIECES -> R.string.interactions_card_headline
            IKind.TEXT -> R.string.interactions_text_headline
            IKind.QUESTION -> R.string.interactions_asks
            IKind.NOTICE -> R.string.interactions_says
        }, agent, i.title)
        val out = SpannableStringBuilder(s)
        val at = s.indexOf(agent)
        if (at >= 0) out.setSpan(StyleSpan(Typeface.BOLD), at, at + agent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /** The label of the button the owner picked (or an HTML card's button), if any. */
    private fun answeredButton(i: Interaction, card: AnswerText.Result?): String? {
        val a = i.answer
        val buttonId = a?.takeIf { it.has("button") && !it.isNull("button") }?.optString("button")
        return buttonId?.let { id -> i.controls.firstOrNull { it is Control.Button && it.id == id }?.label ?: id } ?: card?.button
    }

    /**
     * The one line under a row's title, so the row says something new instead of repeating its status
     * (owner, 2 Nov 2026): what the owner answered, Closed/Missed/Cancelled before a preview, or the text preview.
     * A waiting question without text lists its choices. Null when there is nothing to add.
     * While expanded the body shows the text and answer in full, so only the status word is kept.
     */
    /** The row's status line (design pass, 4 Nov 2026): what state it's in, or what the owner answered. */
    fun listLine(ctx: Context, i: Interaction): CharSequence {
        if (i.status == IStatus.ANSWERED) return rowSummary(ctx, i, false) ?: ctx.getString(R.string.interactions_row_answered)
        if (isStack(i)) {
            val progress = ctx.getString(R.string.interactions_stack_progress, i.stackAnswered, i.stackSize)
            return if (waiting(i)) ctx.getString(R.string.interactions_line_card) + " · " + progress
            else statusLabel(ctx, i.status) + " · " + progress
        }
        if (!waiting(i)) return statusLabel(ctx, i.status)
        if (i.status == IStatus.EXPIRED) return ctx.getString(R.string.interactions_line_late)
        return ctx.getString(when {
            i.kind == IKind.QUESTION -> R.string.interactions_line_question
            i.kind == IKind.HTML || i.kind == IKind.TEXT || i.kind == IKind.PIECES -> R.string.interactions_line_card
            i.status == IStatus.PENDING -> R.string.interactions_line_notice_new
            else -> R.string.interactions_line_notice_seen
        })
    }

    /** "Today, 14:03", "Yesterday", or "3 Nov": the same place and form on every row. */
    fun listTime(ctx: Context, ts: Long): String = when {
        android.text.format.DateUtils.isToday(ts) ->
            ctx.getString(R.string.interactions_today_at, android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ts)))
        android.text.format.DateUtils.isToday(ts + android.text.format.DateUtils.DAY_IN_MILLIS) -> Fmt.day(ts)
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }

    fun rowSummary(ctx: Context, i: Interaction, expanded: Boolean): CharSequence? {
        // #0005: a stack row says how far the owner got through its cards.
        if (isStack(i)) {
            val progress = ctx.getString(R.string.interactions_stack_progress, i.stackAnswered, i.stackSize)
            if (waiting(i)) return progress
            if (i.status == IStatus.ANSWERED) return SpannableStringBuilder("✓ ").append(progress)
            val word = statusLabel(ctx, i.status)
            return SpannableStringBuilder(word).append(" · ").append(progress)
        }
        val preview = i.text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ")?.takeIf { it.isNotEmpty() }
        if (i.status == IStatus.ANSWERED) {
            if (expanded) return null
            val a = i.answer
            val card = if (a != null && a.has("data")) AnswerText.of(a.opt("data")) else null
            val firstValue = a?.optJSONObject("values")?.let { v -> v.keys().asSequence().firstOrNull()?.let { v.optString(it) } }
                ?: card?.lines?.firstOrNull()
            val said = answeredButton(i, card) ?: firstValue
            val s = if (said != null) ctx.getString(R.string.interactions_row_you, said) else ctx.getString(R.string.interactions_row_answered)
            return SpannableStringBuilder("✓ ").append(s)
        }
        // A missed question still waits for the owner (it can be answered late), but says so.
        if (!waiting(i) || i.status == IStatus.EXPIRED) {
            val word = statusLabel(ctx, i.status)
            val out = SpannableStringBuilder(word) // plain: the dimmed row already says it's done (redesign, 4 Nov 2026)
            if (!expanded && preview != null) out.append(" · ").append(preview)
            return out
        }
        if (expanded) return null
        if (preview != null) return preview
        if (i.kind != IKind.QUESTION) return null
        val choices = i.controls.flatMap { c ->
            when (c) {
                is Control.Button -> listOf(c.label)
                is Control.Choice -> c.options
                else -> emptyList()
            }
        }
        return if (choices.isNotEmpty()) choices.joinToString("  ·  ") else ctx.getString(R.string.interactions_row_reply)
    }

    /** "You answered Pay · 14:03" plus one "Label: value" line per text or choice value; null if not answered. */
    fun answerSummary(ctx: Context, i: Interaction): CharSequence? {
        if (i.status != IStatus.ANSWERED) return null
        val a = i.answer
        val at = Fmt.time(i.answeredAt ?: i.closedAt ?: i.createdAt)
        // CAP-34: what an HTML card sent with Bridgit.respond, as readable lines rather than raw JSON.
        val card = if (a != null && a.has("data")) AnswerText.of(a.opt("data")) else null
        val buttonLabel = answeredButton(i, card)
        val out = SpannableStringBuilder(
            if (buttonLabel != null) ctx.getString(R.string.interactions_you_answered, buttonLabel, at)
            else ctx.getString(R.string.interactions_you_answered_plain, at))
        buttonLabel?.let { l ->
            val p = out.indexOf(l)
            if (p >= 0) out.setSpan(StyleSpan(Typeface.BOLD), p, p + l.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        card?.lines?.forEach { out.append("\n").append(it) }
        val values = a?.optJSONObject("values")
        values?.keys()?.forEach { k ->
            val label = i.controls.firstOrNull { it.id == k }?.label ?: k
            out.append("\n").append(label).append(": ").append(values.optString(k))
        }
        return out
    }

    /**
     * Builds the controls of [i] into [container]: text fields and choice chips, then the buttons (side by side
     * when there are one or two, stacked full width when more), then links with where they go drawn under them.
     * Inputs and buttons only while a question is open; links always.
     */
    fun buildControls(
        container: LinearLayout,
        i: Interaction,
        drafts: Drafts,
        onAnswer: (button: String?, values: Map<String, String>) -> Unit,
        resubmission: Boolean = false,
        onLink: (Control.Link, View) -> Unit,
    ) {
        container.removeAllViews()
        val ctx = container.context
        val inflater = LayoutInflater.from(ctx)
        val answerable = isQuestion(i) && i.status.acceptsAnswer(resubmission) && i.kind == IKind.QUESTION
        val sendValues = { anchor: View ->
            val v = drafts.values(i)
            if (v.isEmpty()) Snackbar.make(anchor, R.string.interactions_need_answer, Snackbar.LENGTH_SHORT).show()
            else onAnswer(null, v)
        }
        if (answerable) {
            for (c in i.controls) when (c) {
                is Control.Text -> {
                    val t = InteractionsControlTextBinding.inflate(inflater, container, false)
                    t.fieldLayout.hint = c.label
                    c.placeholder?.let { t.fieldLayout.placeholderText = it }
                    t.field.setText(drafts.get(i.id, c.id) ?: "")
                    t.field.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun afterTextChanged(s: Editable?) { drafts.set(i.id, c.id, s?.toString()) }
                    })
                    t.send.contentDescription = ctx.getString(R.string.interactions_send) + ", " + c.label
                    t.send.setOnClickListener { sendValues(it) }
                    container.addView(t.root)
                }
                is Control.Choice -> {
                    val ch = InteractionsControlChoiceBinding.inflate(inflater, container, false)
                    ch.label.text = c.label
                    val picked = drafts.get(i.id, c.id)
                    for (opt in c.options) {
                        val chip = inflater.inflate(R.layout.interactions_choice_chip, ch.options, false) as Chip
                        chip.id = View.generateViewId()
                        chip.text = opt
                        chip.isCheckable = true
                        chip.isChecked = opt == picked
                        chip.setOnCheckedChangeListener { _, on ->
                            if (on) drafts.set(i.id, c.id, opt) else if (drafts.get(i.id, c.id) == opt) drafts.set(i.id, c.id, null)
                        }
                        ch.options.addView(chip)
                    }
                    container.addView(ch.root)
                }
                else -> {}
            }
            val buttons = i.controls.filterIsInstance<Control.Button>()
            if (buttons.isNotEmpty()) {
                val stacked = buttons.size > 2
                val row = LinearLayout(ctx).apply {
                    orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = ctx.dp(8) }
                }
                buttons.forEachIndexed { n, c ->
                    // One look for every answer: filled pill for the agent's primary choice, tonal for the rest.
                    val style = if (c.style == "primary") R.attr.bridgitPillStyle else R.attr.bridgitPillTonalStyle
                    val btn = MaterialButton(ctx, null, style).apply {
                        text = c.label
                        setOnClickListener { onAnswer(c.id, drafts.values(i)) }
                    }
                    val lp = if (stacked) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = ctx.dp(8) }
                    row.addView(btn, lp)
                }
                container.addView(row)
            } else if (i.controls.none { it is Control.Text }) {
                // Only choices: they still need a way to send.
                val send = MaterialButton(ctx, null, R.attr.bridgitPillStyle).apply {
                    setText(R.string.interactions_send)
                    setOnClickListener { sendValues(it) }
                }
                container.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(8) })
            }
        }
        for (c in i.controls.filterIsInstance<Control.Link>()) {
            val l = InteractionsControlLinkBinding.inflate(inflater, container, false)
            l.button.text = c.label
            l.shownAs.text = c.shownAs
            // The real destination is read out too, so TalkBack users see what Bridgit drew (CAP-41).
            l.button.contentDescription = c.label + ". " + ctx.getString(R.string.interactions_link_hint, c.shownAs)
            l.button.setOnClickListener { onLink(c, it) }
            container.addView(l.root)
        }
        container.visibility = if (container.childCount > 0) View.VISIBLE else View.GONE
    }

    /**
     * Opens a link control from the owner's tap (CAP-41): https/geo/mailto view, tel only dials (never calls),
     * a package launches that app. The target is checked against the allowlist again before it is opened.
     */
    fun openLink(anchor: View, c: Control.Link) {
        val ctx = anchor.context
        val pkg = c.pkg
        if (pkg != null) {
            // Needs a <queries> entry for launcher activities in the manifest, or Android 11+ hides other apps.
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                Snackbar.make(anchor, ctx.getString(R.string.interactions_not_installed, pkg), Snackbar.LENGTH_LONG).show()
                return
            }
            start(anchor, launch)
            return
        }
        val target = c.target
        if (target == null || InteractionRules.linkShownAs(target) == null) {
            Snackbar.make(anchor, R.string.interactions_no_app, Snackbar.LENGTH_LONG).show()
            return
        }
        val uri = Uri.parse(target)
        val intent = if (uri.scheme.equals("tel", ignoreCase = true)) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_VIEW, uri)
        start(anchor, intent)
    }

    private fun start(anchor: View, intent: Intent) {
        try {
            anchor.context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(anchor, R.string.interactions_no_app, Snackbar.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Snackbar.make(anchor, R.string.interactions_no_app, Snackbar.LENGTH_LONG).show()
        }
    }
}
