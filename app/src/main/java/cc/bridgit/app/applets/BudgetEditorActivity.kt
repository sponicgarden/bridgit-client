package cc.bridgit.app.applets

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.OwnerScreen
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as M
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The full-screen budget editor of one installed applet (design pass docs/design/installed-applets.md): its spending
 * limits, whether agents may change them and up to what, and a reset to the applet's own limits. Saves through the
 * same paths the old dialog used: the owner's limits on this device, then the backend's limits and ceiling.
 * Invalid input keeps the editor open with an inline error; nothing is lost.
 */
class BudgetEditorActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var screen: OwnerScreen
    private val words by lazy { AppletCardText(this) }
    private lateinit var r: JSONObject
    private lateinit var applet: String

    /** The limits the fields show (in force, or the package's after a reset), in package order. */
    private var limits: List<AppletBudget.Limit> = emptyList()
    private var original: List<AppletBudget.Limit> = emptyList()
    private var originalCeiling = JSONObject()
    private var originalMayChange = true
    private var resetToPackage = false
    /** Limit id → what the owner typed (kept across a reset's rebuild). */
    private val amountText = LinkedHashMap<String, String>()
    private val ceilingText = LinkedHashMap<String, String>()
    private var mayChange = true
    /** Agent maximums a reset drops (their limit isn't in the applet's own budget): label → amount. */
    private var droppedCeilings: List<String> = emptyList()
    private val amountFields = LinkedHashMap<String, TextInputLayout>()
    private val ceilingFields = LinkedHashMap<String, TextInputLayout>()
    private var message: TextView? = null
    private var busy = false
    /** What Back compares against: the state when opened (or when a save reached this device). */
    private var baseline = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val rec = intent.getStringExtra(EXTRA_INSTANCE)?.let { app.applets.get(it) }
        if (rec == null) { finish(); return }
        r = rec
        applet = AppletBudget.appletOf(r)
        screen = OwnerScreen(this, getString(R.string.applets_budget_title))
        screen.toolbar.minimumHeight = dp(48)
        screen.maxContentWidthDp = 600
        screen.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val synced = AppletBudget.synced(app, applet)
        original = AppletBudget.limitsOf(app, r)
        limits = original
        originalCeiling = synced?.optJSONObject("ceiling")?.let { JSONObject(it.toString()) } ?: JSONObject()
        originalMayChange = synced?.optBoolean("agents_may_change", true) ?: true
        mayChange = originalMayChange
        for (l in limits) {
            amountText[l.id] = AppletCardText.amountText(l.usd)
            ceilingText[l.id] = AppletCardText.amountText(originalCeiling.optDouble(l.id, Double.NaN).takeIf { it.isFinite() })
        }
        baseline = snapshot()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        render()
    }

    private fun snapshot() = JSONObject().put("a", JSONObject(amountText as Map<*, *>)).put("c", JSONObject(ceilingText.filterKeys { k -> limits.any { it.id == k } } as Map<*, *>))
        .put("m", mayChange).put("reset", resetToPackage).toString()

    private fun dirty() = snapshot() != baseline

    private fun close() {
        if (busy) return
        if (!dirty()) return finish()
        MaterialAlertDialogBuilder(this).setTitle(R.string.applets_discard_title)
            .setNegativeButton(R.string.applets_keep_editing, null)
            .setPositiveButton(R.string.applets_discard) { _, _ -> finish() }.show()
    }

    private fun render() {
        screen.clear()
        amountFields.clear(); ceilingFields.clear()
        val pad = dp(16)
        fun add(v: View, top: Int = 0) = screen.add(v, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, dp(top), pad, 0) })
        fun text(t: CharSequence, sp: Int = 14, medium: Boolean = false, color: Int = M.attr.colorOnSurfaceVariant) = TextView(this).apply {
            this.text = t
            setTextAppearance(if (sp >= 16) M.style.TextAppearance_Material3_BodyLarge else cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
            textSize = sp.toFloat()
            if (medium) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(themeColor(color))
        }
        fun heading(t: CharSequence, top: Int) = add(text(t, medium = true, color = M.attr.colorPrimary).also { ViewCompat.setAccessibilityHeading(it, true) }, top)

        add(text(r.optString("name"), sp = 16, medium = true, color = M.attr.colorOnSurface), 8)
        add(text(getString(R.string.applets_editor_intro)), 8)
        message = text("", color = M.attr.colorError).apply { visibility = View.GONE; ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE) }
        add(message!!, 12)

        val ordered = words.byPeriod(limits) { it.per }
        heading(getString(R.string.applets_limits_heading), 24)
        words.fieldLabels(ordered.map { it.per }, ceiling = false).forEachIndexed { i, label ->
            val l = ordered[i]
            add(currencyField(label, amountText[l.id].orEmpty()) { amountText[l.id] = it }.also { amountFields[l.id] = it }, if (i == 0) 8 else 16)
        }
        add(text(getString(R.string.applets_limit_helper)), 8)

        heading(getString(R.string.applets_agent_access), 24)
        add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(MaterialSwitch(context).apply {
                text = getString(R.string.applets_agents_switch)
                minHeight = dp(48)
                setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                setTextColor(themeColor(M.attr.colorOnSurface))
                isChecked = mayChange
                setOnCheckedChangeListener { _, on -> mayChange = on; ceilingFields.values.forEach { it.isEnabled = on } }
            }, LinearLayout.LayoutParams(-1, -2))
            addView(text(getString(R.string.applets_agents_switch_help)))
        }, 8)
        words.fieldLabels(ordered.map { it.per }, ceiling = true).forEachIndexed { i, label ->
            val l = ordered[i]
            add(currencyField(label, ceilingText[l.id].orEmpty()) { ceilingText[l.id] = it }.also { it.isEnabled = mayChange; ceilingFields[l.id] = it }, 16)
        }
        add(text(getString(R.string.applets_ceiling_helper)), 8)
        if (droppedCeilings.isNotEmpty()) add(text(getString(R.string.applets_removed_maximums, droppedCeilings.joinToString(" · ")), color = M.attr.colorOnSurface), 8)

        add(com.google.android.material.button.MaterialButton(this, null, R.attr.bridgitPillTextStyle).apply {
            text = getString(R.string.applets_reset)
            minHeight = dp(48)
            setOnClickListener { confirmReset() }
        }.let { b -> LinearLayout(this).apply { addView(b, LinearLayout.LayoutParams(-2, -2)) } }, 24)

        screen.bottomBar(null, getString(R.string.applets_cancel) to { close() }, getString(R.string.applets_save) to { save() })
    }

    /** An outlined field with a "$" prefix and decimal input (the device's separator works too). */
    private fun currencyField(label: String, value: String, changed: (String) -> Unit) = TextInputLayout(this, null, M.attr.textInputOutlinedStyle).apply {
        hint = label
        prefixText = "$"
        minimumHeight = dp(56)
        val layout = this
        addView(TextInputEditText(context).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            doAfterTextChanged { t -> changed(t?.toString().orEmpty()); layout.error = null }
        })
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.applets_reset_title)
            .setMessage(R.string.applets_reset_body)
            .setNegativeButton(R.string.applets_cancel, null)
            .setPositiveButton(R.string.applets_reset_confirm) { _, _ -> reset() }.show()
    }

    /** The applet's own limits in the fields; agent settings stay for the limits that remain. Save commits it. */
    private fun reset() {
        val pkg = AppletBudget.packageLimits(r)
        val keep = pkg.map { it.id }.toSet()
        val old = words.byPeriod(limits) { it.per }
        val oldLabels = words.fieldLabels(old.map { it.per }, ceiling = true)
        droppedCeilings = old.mapIndexedNotNull { i, l ->
            AppletCardText.parseAmount(ceilingText[l.id])?.takeIf { l.id !in keep }?.let { "${oldLabels[i]} ${words.money(it)}" }
        }
        limits = pkg
        resetToPackage = true
        amountText.keys.retainAll(keep)
        for (l in pkg) { amountText[l.id] = AppletCardText.amountText(l.usd); ceilingText.putIfAbsent(l.id, "") }
        render()
    }

    private fun showMessage(text: String) {
        message?.apply { this.text = text; visibility = View.VISIBLE; screen.scroll.post { screen.scroll.smoothScrollTo(0, 0) } }
    }

    private fun save() {
        if (busy) return
        message?.visibility = View.GONE
        val error = getString(R.string.applets_amount_error)
        var firstBad: TextInputLayout? = null
        val usd = limits.associate { l -> l.id to AppletCardText.parseAmount(amountText[l.id]) }
        for (l in limits) if (usd[l.id] == null) amountFields[l.id]?.let { it.error = error; if (firstBad == null) firstBad = it }
        // A maximum is blank (Bridgit's $100) or a valid amount; invalid text never becomes a blank.
        val ceilings = limits.associate { l -> l.id to ceilingText[l.id].orEmpty().trim() }
        for (l in limits) {
            val t = ceilings[l.id].orEmpty()
            if (t.isNotEmpty() && AppletCardText.parseAmount(t) == null) ceilingFields[l.id]?.let { it.isEnabled = true; it.error = error; if (firstBad == null) firstBad = it }
        }
        firstBad?.let { f -> f.editText?.requestFocus(); screen.scroll.post { screen.scroll.smoothScrollTo(0, f.top) }; return }

        val newLimits = limits.map { it.copy(usd = usd[it.id]!!) }
        val newCeiling = JSONObject().apply { limits.forEach { l -> AppletCardText.parseAmount(ceilings[l.id])?.let { put(l.id, it) } } }
        val limitsChanged = resetToPackage || newLimits != original
        val ceilingChanged = mayChange != originalMayChange || newCeiling.toString() != JSONObject().apply {
            limits.forEach { l -> originalCeiling.optDouble(l.id, Double.NaN).takeIf { it.isFinite() }?.let { put(l.id, it) } } }.toString()
        if (!limitsChanged && !ceilingChanged) return finish()
        val toPackage = resetToPackage && newLimits == AppletBudget.packageLimits(r)
        busy = true
        lifecycleScope.launch {
            var local = false
            val outcome: Result<Unit> = runCatching {
                if (limitsChanged) {
                    // The reset path is the old "Use the applet's own": the owner's override goes and the package's limits sync.
                    app.applets.setBudget(r.getString("id"), if (toPackage) null else newLimits)
                    local = true
                }
                withContext(Dispatchers.IO) {
                    if (limitsChanged) app.budgetSync.setLimits(applet, newLimits, app.budgetSync.by(null))
                    if (ceilingChanged) app.budgetSync.setCeiling(applet, newCeiling.takeIf { it.length() > 0 }, mayChange)
                    app.budgetSync.syncNow(); Unit
                }
            }
            busy = false
            val e = outcome.exceptionOrNull()
            when {
                e == null -> done(getString(R.string.applets_saved))
                // Offline: the limits are saved here and sync later; an agent setting lives only on the backend.
                e is java.io.IOException && local && !ceilingChanged -> done(getString(R.string.applets_saved_offline))
                else -> {
                    val reason = if (e is java.io.IOException) getString(R.string.applets_offline_reason) else e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.applets_couldnt)
                    if (local) {
                        // What reached this device is saved: Back doesn't ask about it again.
                        original = newLimits; resetToPackage = false; baseline = snapshot()
                    }
                    showMessage(getString(if (local) R.string.applets_saved_not_synced else R.string.applets_couldnt_sync, reason))
                }
            }
        }
    }

    private fun done(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_MESSAGE, text))
        finish()
    }

    companion object {
        const val EXTRA_INSTANCE = "budget_instance_id"
        const val EXTRA_MESSAGE = "budget_message"
    }
}
