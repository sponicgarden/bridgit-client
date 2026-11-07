package cc.bridgit.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import cc.bridgit.app.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.R as M

/**
 * The look of the app's redesigned screens (Settings, Interactions) for owner screens built in code:
 * a toolbar with Back, blue section labels, Settings-style tappable rows and rounded shaded cards with an
 * accent pill for things that need the owner. Call [clear] then add views on each render.
 */
class OwnerScreen(private val activity: AppCompatActivity, title: CharSequence) {
    enum class Accent { NEEDS_YOU, NOTICE, NONE }

    val toolbar = MaterialToolbar(activity).apply {
        minimumHeight = activity.dp(64)
        this.title = title
        setNavigationIcon(R.drawable.ic_back)
        setNavigationContentDescription(R.string.cd_back)
        setNavigationOnClickListener { activity.finish() }
    }
    val scroll = NestedScrollView(activity).apply { clipToPadding = false }
    /** The content column; [maxContentWidthDp] caps it (centred) on wide screens. */
    val list = object : LinearLayout(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = activity.dp(maxContentWidthDp)
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(if (maxContentWidthDp > 0 && w > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else widthMeasureSpec, heightMeasureSpec)
        }
    }.apply { orientation = LinearLayout.VERTICAL; updatePadding(bottom = activity.dp(24)) }
    /** 0 = full width (the default); the Installed applets screen and its editor use 600. */
    var maxContentWidthDp = 0
        set(v) { field = v; list.requestLayout() }
    /** The pinned action bar under the scrolling content (a review's Decline / Approve); hidden until [bottomBar]. */
    private val actionBar = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(activity.themeColor(M.attr.colorSurface))
    }
    private var insetBottom = 0

    init {
        activity.enableEdgeToEdge()
        scroll.addView(list, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.themeColor(android.R.attr.colorBackground))
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(actionBar, LinearLayout.LayoutParams(-1, -2))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(left = bars.left, right = bars.right)
            toolbar.updatePadding(top = bars.top)
            insetBottom = maxOf(bars.bottom, ime)
            placeBottom()
            insets
        }
        activity.setContentView(root)
    }

    /** Empties the content and hides the bottom bar; each render adds them again. */
    fun clear() { list.removeAllViews(); actionBar.removeAllViews(); actionBar.visibility = View.GONE; placeBottom() }

    /** The inset goes under the bar when it shows, else under the content, so the last field stays reachable. */
    private fun placeBottom() {
        val shown = actionBar.visibility == View.VISIBLE
        scroll.updatePadding(bottom = if (shown) 0 else insetBottom)
        actionBar.updatePadding(bottom = if (shown) insetBottom + activity.dp(8) else 0)
    }

    /**
     * A bar pinned under the content for a screen's decision: a 1dp divider, an optional quiet [note], then a quiet
     * [secondary] action and the filled [primary] one at the end. [primaryEnabled] false greys the primary out.
     */
    fun bottomBar(note: CharSequence?, secondary: Pair<CharSequence, () -> Unit>?, primary: Pair<CharSequence, () -> Unit>, primaryEnabled: Boolean = true) {
        val ctx = activity
        actionBar.removeAllViews()
        actionBar.addView(View(ctx).apply { setBackgroundColor(ctx.themeColor(M.attr.colorOutlineVariant)) }, LinearLayout.LayoutParams(-1, ctx.dp(1)))
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        note?.let {
            inner.addView(TextView(ctx).apply {
                text = it
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            })
        }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        secondary?.let { (label, action) ->
            bar.addView(MaterialButton(ctx, null, R.attr.bridgitPillTextStyle).apply { text = label; setOnClickListener { action() } },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(8) })
        }
        bar.addView(MaterialButton(ctx, null, R.attr.bridgitPillStyle).apply {
            text = primary.first; isEnabled = primaryEnabled; setOnClickListener { primary.second() }
        }, LinearLayout.LayoutParams(-2, -2))
        inner.addView(bar, LinearLayout.LayoutParams(-1, -2))
        actionBar.addView(inner, LinearLayout.LayoutParams(-1, -2))
        actionBar.visibility = View.VISIBLE
        placeBottom()
    }

    /** Blue section label, like Settings (an accessibility heading); [topDp] overrides the space above it. */
    fun header(text: CharSequence, topDp: Int? = null) {
        list.addView(TextView(activity, null, 0, R.style.SettingsHeader).apply {
            this.text = text
            ViewCompat.setAccessibilityHeading(this, true)
            topDp?.let { updatePadding(top = activity.dp(it)) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Any view straight in the content column. */
    fun add(v: View, params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) = list.addView(v, params)

    /**
     * The list-screen empty state: a centred 40dp neutral [icon], a 16sp title and a 14sp explanation.
     */
    fun empty(icon: Int, title: CharSequence, body: CharSequence) {
        val ctx = activity
        list.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(16), ctx.dp(24), ctx.dp(16), ctx.dp(16))
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
            addView(TextView(ctx).apply {
                text = title; gravity = Gravity.CENTER
                setTextAppearance(M.style.TextAppearance_Material3_TitleMedium)
                setTextColor(ctx.themeColor(M.attr.colorOnSurface))
                setPadding(0, ctx.dp(12), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = body; gravity = Gravity.CENTER
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                setPadding(0, ctx.dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * A detail card (Installed applets): 16dp margins and padding, 16dp corners, a 1dp outline, no elevation, as tall
     * as its content. Build it top to bottom with [Card.section], [Card.line], [Card.actions] and the rest.
     */
    fun detailCard(build: Card.() -> Unit) {
        val ctx = activity
        val card = MaterialCardView(ctx).apply {
            radius = ctx.dp(12).toFloat()
            strokeWidth = ctx.dp(1)
            strokeColor = ctx.themeColor(M.attr.colorOutlineVariant)
            cardElevation = 0f
            setCardBackgroundColor(ctx.themeColor(M.attr.colorSurfaceContainerLow))
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16)) }
        card.addView(body)
        Card(body).build()
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.dp(16), 0, ctx.dp(16), ctx.dp(12)) })
    }

    /** Quiet explanatory line under a header. */
    fun note(text: CharSequence) {
        list.addView(TextView(activity).apply {
            this.text = text
            setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
            setTextColor(activity.themeColor(M.attr.colorOnSurfaceVariant))
            setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(8))
        })
    }

    /** Settings-style row: a title, an optional value or summary line, the whole row tappable. */
    fun row(title: CharSequence, value: CharSequence? = null, action: () -> Unit): View {
        val v = activity.layoutInflater.inflate(R.layout.settings_row, list, false)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.value).apply { text = value; visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE }
        v.setOnClickListener { action() }
        list.addView(v)
        return v
    }

    /** Rounded shaded card, like an Interactions row. Waiting things are raised and carry an accent pill. */
    fun card(accent: Accent = Accent.NONE, build: Card.() -> Unit) {
        val ctx = activity
        val card = MaterialCardView(ctx).apply {
            radius = ctx.dp(12).toFloat()
            strokeWidth = 0
            cardElevation = if (accent == Accent.NONE) 0f else ctx.dp(2).toFloat()
            setCardBackgroundColor(ctx.themeColor(if (accent == Accent.NONE) M.attr.colorSurfaceContainerLow else M.attr.colorSurfaceContainer))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (accent != Accent.NONE) row.addView(View(ctx).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(2).toFloat()
                setColor(ctx.themeColor(if (accent == Accent.NEEDS_YOU) M.attr.colorPrimary else M.attr.colorSecondary))
            }
        }, LinearLayout.LayoutParams(ctx.dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = ctx.dp(5); topMargin = ctx.dp(14); bottomMargin = ctx.dp(14) })
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(if (accent == Accent.NONE) 16 else 11), ctx.dp(14), ctx.dp(16), ctx.dp(10))
        }
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        Card(body).build()
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4)) })
    }

    inner class Card(private val body: LinearLayout) {
        private var lastBar: WrapRow? = null
        private val ctx get() = activity

        /** Card title; [large] (20sp) for the one thing a screen is about, like the applet under review. */
        fun title(text: CharSequence, large: Boolean = false) = body.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(if (large) M.style.TextAppearance_Material3_TitleLarge else M.style.TextAppearance_Material3_TitleMedium)
            setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        /** Body line; [gapDp] adds space above it, to start a new item in a list. */
        fun text(text: CharSequence, quiet: Boolean = true, gapDp: Int = 2) = body.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
            setTextColor(ctx.themeColor(if (quiet) M.attr.colorOnSurfaceVariant else M.attr.colorOnSurface))
            setPadding(0, ctx.dp(gapDp), 0, 0)
        })

        /** A 16sp lead line under a large title (a summary). */
        fun lead(text: CharSequence) = body.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
            setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
        })

        /** One item of a list, 16sp with a hanging indent; [first] drops the 8dp gap above it. */
        fun bullet(text: CharSequence, first: Boolean = false) = body.addView(TextView(ctx).apply {
            this.text = android.text.SpannableString(text).apply {
                setSpan(android.text.style.BulletSpan(ctx.dp(8), ctx.themeColor(M.attr.colorOnSurfaceVariant)), 0, length, 0)
            }
            setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
            setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            setPadding(ctx.dp(4), ctx.dp(if (first) 0 else 8), 0, 0)
        })

        /**
         * One item of a review list (applet install design pass): a 16sp medium [title], quiet 14sp [lines] 4dp under
         * it, then [subs] indented 12dp, 4dp apart: "Label: effect" with the label in medium, or a plain line when the
         * label is null. Items after the [first] get a 1dp divider with 12dp above and below.
         */
        fun item(title: CharSequence, lines: List<CharSequence>, subs: List<Pair<String?, String>> = emptyList(), first: Boolean = false) {
            if (!first) divider(12, 12)
            body.addView(TextView(ctx).apply {
                text = title
                setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            })
            for (l in lines) body.addView(TextView(ctx).apply {
                text = l
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            for ((label, effect) in subs) body.addView(TextView(ctx).apply {
                text = if (label == null) effect else android.text.SpannableStringBuilder().apply {
                    append(label, android.text.style.TypefaceSpan("sans-serif-medium"), 0)
                    setSpan(android.text.style.ForegroundColorSpan(ctx.themeColor(M.attr.colorOnSurface)), 0, length, 0)
                    append(": ").append(effect)
                }
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4); marginStart = ctx.dp(12) })
        }

        /** A thing to fix before going on: a 20dp warning glyph in the error colour, 12dp gap, 16sp text in the normal colour. */
        fun warnRow(text: CharSequence) {
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_warning)
                    imageTintList = android.content.res.ColorStateList.valueOf(ctx.themeColor(M.attr.colorError))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginEnd = ctx.dp(12); topMargin = ctx.dp(2) })
                addView(TextView(ctx).apply {
                    this.text = text
                    setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                    setTextColor(ctx.themeColor(M.attr.colorOnSurface))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        }

        /** A permission: 16sp medium [name], an optional "New" badge after it, and a quiet 14sp [explanation]. 8dp between rows. */
        fun permission(name: CharSequence, explanation: CharSequence?, isNew: Boolean, first: Boolean = false) {
            body.addView(TextView(ctx).apply {
                text = android.text.SpannableStringBuilder(name).apply {
                    if (isNew) {
                        append("  ")
                        val s = length
                        append(" New ")
                        setSpan(Badge(ctx.themeColor(M.attr.colorPrimaryContainer), ctx.themeColor(M.attr.colorOnPrimaryContainer), ctx.dp(8).toFloat(), ctx.dp(8).toFloat()), s, length, 0)
                    }
                }
                setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ctx.themeColor(M.attr.colorOnSurface))
                if (isNew) contentDescription = "New: $name"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(if (first) 0 else 8) })
            explanation?.let { e -> body.addView(TextView(ctx).apply {
                text = e
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
            }) }
        }

        /** A good-news status line (Running), in the success colour. */
        fun ok(text: CharSequence) = body.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_Label)
            setTextColor(ctx.themeColor(R.attr.colorSuccess))
            setPadding(0, ctx.dp(2), 0, 0)
        })

        /** A problem line (a failed run), in the error colour. */
        fun warn(text: CharSequence) = body.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
            setTextColor(ctx.themeColor(M.attr.colorError))
            setPadding(0, ctx.dp(2), 0, 0)
        })

        /** Exact text the owner must inspect (a clipboard request): selectable, on a lighter panel. */
        fun quote(text: CharSequence) = body.addView(TextView(ctx).apply {
            this.text = text
            setTextIsSelectable(true)
            setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
            setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(8).toFloat(); setColor(ctx.themeColor(M.attr.colorSurfaceContainerLowest)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })

        /** Any view, full width (form fields on a review screen). */
        fun view(v: View) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })

        /** Filled button for the main action; at most one per card. */
        fun primary(label: CharSequence, action: () -> Unit) = add(MaterialButton(ctx).apply { text = label }, action)
        /** Text button for the rest (Deny, Save a copy). [danger] colours it red, for deletes. */
        fun secondary(label: CharSequence, danger: Boolean = false, action: () -> Unit) =
            add(MaterialButton(ctx, null, M.attr.borderlessButtonStyle).apply {
                text = label
                if (danger) setTextColor(ctx.themeColor(M.attr.colorError))
            }, action)

        // ---- ordered building blocks for detail cards (each lands exactly where it is called) ----------------------

        /**
         * A section of a detail card: when something is above it, a 1dp divider with 16dp on each side, then the
         * 14sp medium primary heading (an accessibility heading) with 8dp under it.
         */
        fun section(heading: CharSequence) {
            if (body.childCount > 0) divider(16, 16)
            body.addView(TextView(ctx).apply {
                text = heading
                setTextAppearance(M.style.TextAppearance_Material3_TitleSmall)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ctx.themeColor(M.attr.colorPrimary))
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(8) })
        }

        /** A 1dp outline-variant rule. */
        fun divider(topDp: Int = 0, bottomDp: Int = 0) = body.addView(View(ctx).apply {
            setBackgroundColor(ctx.themeColor(M.attr.colorOutlineVariant))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, ctx.dp(1)).apply { topMargin = ctx.dp(topDp); bottomMargin = ctx.dp(bottomDp) })

        /** One text line: [sp] size, [medium] weight, a theme colour [colorAttr], [topDp] above it. Returns it. */
        fun line(text: CharSequence, sp: Int = 14, medium: Boolean = false, colorAttr: Int = M.attr.colorOnSurfaceVariant, topDp: Int = 0): TextView =
            TextView(ctx).apply {
                this.text = text
                setTextAppearance(if (sp >= 16) M.style.TextAppearance_Material3_BodyLarge else if (sp <= 12) cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall else cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                textSize = sp.toFloat()
                if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ctx.themeColor(colorAttr))
            }.also { body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(topDp) }) }

        /**
         * A flat row (12dp above and below, no icon or chevron): a 16sp medium [title] and quiet 14sp [lines] 4dp
         * under it. [divided] puts a 1dp rule above it (between rows).
         */
        fun flatRow(title: CharSequence, lines: List<CharSequence>, divided: Boolean) {
            if (divided) divider()
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, ctx.dp(12), 0, ctx.dp(12))
                addView(TextView(ctx).apply {
                    text = title
                    setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(ctx.themeColor(M.attr.colorOnSurface))
                })
                lines.forEachIndexed { i, l ->
                    addView(TextView(ctx).apply {
                        text = l
                        setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                        setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                        if (i == 0) setPadding(0, ctx.dp(4), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }

        /**
         * A wrapping group of pill buttons placed here (8dp apart, whole buttons wrap to the next line). Add
         * buttons with [pill].
         */
        fun actions(topDp: Int = 12): FlowRow = FlowRow(ctx, ctx.dp(8), 0).also {
            body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(topDp) })
        }

        /** A pill in [group]: [styleAttr] is bridgitPillStyle / Tonal / Text; [danger] colours it red; [description] for TalkBack. */
        fun pill(group: FlowRow, label: CharSequence, styleAttr: Int, description: CharSequence? = null, danger: Boolean = false, action: () -> Unit): MaterialButton =
            MaterialButton(ctx, null, styleAttr).apply {
                text = label
                minHeight = ctx.dp(48)
                if (danger) setTextColor(ctx.themeColor(M.attr.colorError))
                description?.let { contentDescription = it }
                setOnClickListener { action() }
            }.also { group.addView(it, ViewGroup.LayoutParams(-2, -2)) }

        /**
         * A full-width determinate meter, 6dp high with round ends: [fraction] 0..1 (clamped), coloured by
         * [colorAttr] on the highest surface container. [description] is the real amount, for TalkBack.
         */
        fun meter(fraction: Float, colorAttr: Int, description: CharSequence, topDp: Int = 8) =
            com.google.android.material.progressindicator.LinearProgressIndicator(ctx).apply {
                isIndeterminate = false
                max = 1000
                progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
                trackThickness = ctx.dp(6)
                trackCornerRadius = ctx.dp(3)
                trackColor = ctx.themeColor(M.attr.colorSurfaceContainerHighest)
                setIndicatorColor(ctx.themeColor(colorAttr))
                contentDescription = description
            }.also { body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(topDp) }) }

        private fun add(b: MaterialButton, action: () -> Unit) {
            // Main's wrapping bar, reused only while it is still the last thing in the card, so a button never
            // floats above content added after the previous one.
            val last = lastBar?.takeIf { body.childCount > 0 && body.getChildAt(body.childCount - 1) === it }
            val bar = last ?: WrapRow(ctx, ctx.dp(8)).apply {
                setPadding(0, ctx.dp(6), 0, 0)
                body.addView(this, LinearLayout.LayoutParams(-1, -2))
                lastBar = this
            }
            b.setOnClickListener { action() }
            bar.addView(b, ViewGroup.LayoutParams(-2, -2))
        }
    }

    /**
     * A card's buttons, right-aligned, wrapping onto another line when they don't fit. A single horizontal row
     * pushed the fourth button (Show on web portal, next to Remove, Pause and Change spending limit) off a tablet.
     */
    class WrapRow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
        private val lines = mutableListOf<List<View>>()
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val max = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
            lines.clear(); var line = mutableListOf<View>(); var used = 0; var height = 0; var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i); if (c.visibility == GONE) continue
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val w = c.measuredWidth + if (line.isEmpty()) 0 else gap
                if (line.isNotEmpty() && used + w > max) { lines += line; height += lineH; line = mutableListOf(); used = 0; lineH = 0 }
                used += if (line.isEmpty()) c.measuredWidth else w; lineH = maxOf(lineH, c.measuredHeight); line += c
            }
            if (line.isNotEmpty()) { lines += line; height += lineH }
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(height + paddingTop + paddingBottom, heightSpec))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var y = paddingTop
            for (line in lines) {
                val lineH = line.maxOf { it.measuredHeight }
                var x = (r - l) - paddingRight - line.sumOf { it.measuredWidth } - gap * (line.size - 1)
                for (c in line) { val top = y + (lineH - c.measuredHeight) / 2; c.layout(x, top, x + c.measuredWidth, top + c.measuredHeight); x += c.measuredWidth + gap }
                y += lineH
            }
        }
    }
}

/** A small rounded label inside text ("New"): 12sp medium on a filled pill with [padX] side padding and [radius] corners. */
private class Badge(private val bg: Int, private val fg: Int, private val padX: Float, private val radius: Float) : android.text.style.ReplacementSpan() {
    private fun small(p: android.graphics.Paint) = android.graphics.Paint(p).apply { textSize = p.textSize * 0.75f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    override fun getSize(p: android.graphics.Paint, t: CharSequence, s: Int, e: Int, fm: android.graphics.Paint.FontMetricsInt?): Int =
        (small(p).measureText(t.subSequence(s, e).toString().trim()) + padX * 2).toInt()
    override fun draw(c: android.graphics.Canvas, t: CharSequence, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint) {
        val sp = small(p)
        val label = t.subSequence(s, e).toString().trim()
        val w = sp.measureText(label) + padX * 2
        val h = sp.textSize * 1.5f
        val cy = (top + bottom) / 2f
        sp.color = bg
        c.drawRoundRect(android.graphics.RectF(x, cy - h / 2, x + w, cy + h / 2), radius, radius, sp)
        sp.color = fg
        c.drawText(label, x + padX, cy - (sp.descent() + sp.ascent()) / 2, sp)
    }
}
