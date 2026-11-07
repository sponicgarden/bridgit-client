package cc.bridgit.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import cc.bridgit.app.ui.common.themeColor

/** Isolated reversible UI fixture. Never present in a release APK. */
class ControlFixtureActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var input: TextInputEditText
    private lateinit var result: TextView
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(state)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun text(value: String, appearance: Int) = TextView(this).apply {
            text = value
            setTextAppearance(appearance)
        }
        fun LinearLayout.add(view: View, gap: Int = 0) {
            addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
        }
        content.add(text("BRIDGIT · LOCAL TEST", com.google.android.material.R.style.TextAppearance_Material3_LabelLarge).apply {
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
        })
        content.add(text("Control test", com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge).apply {
            isAccessibilityHeading = true
        }, 12)
        content.add(text("Try a delivery address and save a sample draft.", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge).apply {
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, 8)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val field = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Delivery address"
        }
        input = TextInputEditText(field.context).apply {
            contentDescription = "Delivery address"
            isSingleLine = true
            minHeight = dp(56)
            setText(state?.getString("address") ?: "")
        }
        field.addView(input, LinearLayout.LayoutParams(-1, -2))
        form.add(field)
        result = text(state?.getString("result") ?: "Draft not saved", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        form.add(MaterialButton(this).apply {
            text = "Save draft locally"
            isAllCaps = false
            minHeight = dp(56)
            setOnClickListener {
                result.text = "Saved locally: ${input.text}"
                result.setTextColor(themeColor(R.attr.colorOnSuccessContainer))
                result.setBackgroundColor(themeColor(R.attr.colorSuccessContainer))
            }
        }, 16)
        form.add(text("DRAFT STATUS", com.google.android.material.R.style.TextAppearance_Material3_LabelMedium).apply {
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, 24)
        form.add(result, 8)
        content.add(MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
            addView(form)
        }, 28)
        content.add(text("Test data only", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall), 24)
        content.add(text("This fixture does not send messages, store credentials or contact another service.", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium).apply {
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, 8)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
            addView(content, LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            addView(wrapper)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        wrapper.doOnLayout {
            content.layoutParams = (content.layoutParams as LinearLayout.LayoutParams).apply {
                width = minOf(dp(560), wrapper.width - wrapper.paddingLeft - wrapper.paddingRight)
            }
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("address", input.text?.toString())
        out.putString("result", result.text.toString())
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); app.platform.store.setting("fixture_active", "true", true) }
    override fun onPause() { app.platform.store.setting("fixture_active", "false", true); super.onPause() }
}
