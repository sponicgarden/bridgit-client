package cc.bridgit.app.context

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import cc.bridgit.app.BridgeService
import cc.bridgit.app.capability.LocationModule
import cc.bridgit.app.portal.LinkedDevicesGlyph
import cc.bridgit.app.ui.home.UiActions
import com.google.android.material.snackbar.Snackbar
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.OwnerScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.R as M
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Owner-only surface. Durable facts can be corrected/forgotten; applets have read-only access. */
class ContextActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private val vault get() = app.platform.contextVault
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) refresh(manual = true)
        else showRefreshProblem(if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) ContextVault.RefreshResult.PRECISE_LOCATION_REQUIRED else ContextVault.RefreshResult.PERMISSION_REQUIRED, appSettings = true)
    }
    private lateinit var screen: OwnerScreen
    private var refreshing = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        screen = OwnerScreen(this, getString(R.string.automated_context)).apply { maxContentWidthDp = 600 }
        render()
        refresh()
        lifecycleScope.launch { while (true) { delay(60_000); render() } }
    }
    private fun render(animateCollection: Boolean = false) {
        val y = screen.scroll.scrollY
        screen.clear()
        screen.toolbar.subtitle = null
        collectionPill(animateCollection)
        val state = vault.status()
        if (listOf("place", "night", "timezone").any { !vault.deletedTemporary(it) } || vault.ownerTemporary().length() > 0) screen.header(getString(R.string.context_temporary))
        val temp = state.getJSONObject("temporary")
        for (key in listOf("place", "night", "timezone")) {
            if (vault.deletedTemporary(key)) continue
            val f = temp.getJSONObject(key)
            contextRow(label(key), if (key == "place" && refreshing) getString(R.string.context_refreshing) else valueText(f),
                refresh = if (key == "place" && vault.enabled()) ({ refresh(manual = true) }) else null,
                remove = { vault.forgetTemporary(key); render() }) {
                details(label(key), f, place = key == "place")
            }
        }
        val ownerTemporary = vault.ownerTemporary()
        ownerTemporary.keys().asSequence().sorted().forEach { key ->
            val raw = ownerTemporary.getJSONObject(key)
            val f = temp.getJSONObject(key)
            contextRow(label(key), valueText(f), remove = { vault.forgetOwnerTemporary(key); render() }) {
                MaterialAlertDialogBuilder(this).setTitle(label(key)).setMessage(detailsText(f))
                    .setPositiveButton(R.string.context_edit) { _, _ -> edit(key, raw.optString("value"), temporary = true, lifetimeMillis = raw.optLong("expires_at") - raw.optLong("observed_at")) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        screen.header(getString(R.string.context_durable))
        val durable = state.getJSONObject("durable")
        if (durable.length() == 0) screen.note(getString(R.string.context_empty))
        durable.keys().asSequence().sorted().forEach { key ->
            val f = durable.getJSONObject(key)
            contextRow(label(key), valueText(f), temporary = false, remove = { vault.forget(key); render() }) {
                MaterialAlertDialogBuilder(this).setTitle(label(key)).setMessage(detailsText(f))
                    .setPositiveButton(R.string.context_edit) { _, _ -> edit(key, f.optString("value")) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        action(R.string.context_add, R.attr.bridgitPillTonalStyle) { addContext() }
        screen.add(View(this).apply { setBackgroundColor(themeColor(M.attr.colorOutlineVariant)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(20), dp(24), dp(20), dp(12)) })
        action(R.string.context_clear, R.attr.bridgitPillTextStyle, danger = true) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.context_clear)
                .setPositiveButton(R.string.context_clear) { _, _ -> vault.clear(); render() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        screen.scroll.post { screen.scroll.scrollTo(0, y) }
    }
    private fun action(label: Int, style: Int, danger: Boolean = false, click: () -> Unit) {
        screen.add(MaterialButton(this, null, style).apply {
            setText(label); minHeight = dp(48); isAllCaps = false
            if (danger) setTextColor(themeColor(M.attr.colorError))
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), 0) })
    }
    private fun contextRow(title: String, value: String, temporary: Boolean = true, refresh: (() -> Unit)? = null,
                           remove: () -> Unit, open: () -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(4), dp(8))
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), dp(4), dp(8))
            isClickable = true; isFocusable = true
            val typed = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = typed.getDrawable(0); typed.recycle()
            setOnClickListener { open() }
            addView(TextView(this@ContextActivity).apply {
                this.text = getString(R.string.context_tagged_name, title, getString(if (temporary) R.string.context_tag_temporary else R.string.context_tag_durable))
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_Label)
                setTextColor(themeColor(M.attr.colorOnSurfaceVariant))
            })
            addView(TextView(this@ContextActivity).apply {
                text = value
                setTextAppearance(M.style.TextAppearance_Material3_TitleMedium)
                setTextColor(themeColor(M.attr.colorOnSurface))
                setPadding(0, dp(4), 0, 0)
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        fun icon(drawable: Int, description: String, enabled: Boolean = true, click: () -> Unit) {
            row.addView(ImageButton(this).apply {
                setImageResource(drawable)
                imageTintList = ColorStateList.valueOf(themeColor(M.attr.colorOnSurfaceVariant))
                contentDescription = description
                setPadding(dp(14), dp(14), dp(14), dp(14))
                val typed = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                background = typed.getDrawable(0); typed.recycle()
                isEnabled = enabled; alpha = if (enabled) 1f else .38f
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        refresh?.let { icon(R.drawable.ic_refresh, getString(R.string.context_refresh_item, title), !refreshing, it) }
        icon(R.drawable.ic_close, getString(R.string.context_delete_item, title), click = remove)
        screen.add(MaterialCardView(this).apply {
            radius = dp(18).toFloat(); strokeWidth = 0; cardElevation = 0f
            setCardBackgroundColor(themeColor(M.attr.colorSurfaceContainerLow))
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })
    }
    private fun addContext() {
        val missing = listOf("place", "night", "timezone").filter { vault.deletedTemporary(it) }
        if (missing.isEmpty()) { edit(); return }
        val choices = (missing.map { label(it) } + getString(R.string.context_saved_fact)).toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.context_add).setItems(choices) { _, which ->
            if (which == missing.size) edit()
            else {
                val key = missing[which]; vault.restoreTemporary(key)
                if (key == "place" && vault.enabled()) refresh() else render()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun collectionPill(animate: Boolean) {
        val on = vault.enabled()
        val dark = LinkedDevicesGlyph.dark(this)
        val colour = if (on) LinkedDevicesGlyph.thisPhone(dark) else if (dark) 0xFFFFAEAA.toInt() else 0xFFA72C2C.toInt()
        val outline = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (dark) 0xFF0D1424.toInt() else 0xFFE6EAF3.toInt())
            setStroke(dp(1), colour)
        }
        val pill = TextView(this).apply {
            setText(if (on) R.string.context_enabled else R.string.context_disabled)
            setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_Label)
            setTextColor(colour); gravity = Gravity.CENTER
            minHeight = dp(48)
            val ripple = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((colour and 0x00FFFFFF) or 0x33000000), outline, null)
            background = android.graphics.drawable.InsetDrawable(ripple, 0, dp(6), 0, dp(6))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val connection = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@ContextActivity, if (on) R.drawable.ic_network_wifi else R.drawable.ic_network_wifi_off
            )!!.mutate().apply {
                setTint(colour)
                setBounds(0, 0, dp(24), dp(24))
            }
            setCompoundDrawablesRelative(connection, null, null, null)
            compoundDrawablePadding = dp(8)
            isClickable = true; isFocusable = true
            setOnClickListener {
                isEnabled = false
                animate().alpha(0f).setDuration(100).withEndAction {
                    vault.setEnabled(!vault.enabled())
                    if (vault.enabled()) refresh(animateCollection = true) else render(animateCollection = true)
                }.start()
            }
        }
        ViewCompat.setStateDescription(pill, getString(if (on) R.string.context_enabled else R.string.context_disabled))
        ViewCompat.setAccessibilityDelegate(pill, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true; info.isChecked = on
            }
        })
        screen.add(pill, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })
        if (animate) {
            pill.alpha = 0f
            pill.animate().alpha(1f).setDuration(150).start()
        }
    }
    private fun refresh(manual: Boolean = false, animateCollection: Boolean = false) {
        if (refreshing) {
            if (animateCollection) render(animateCollection = true)
            return
        }
        refreshing = true; render(animateCollection)
        lifecycleScope.launch {
            val result = try {
                if (manual && vault.refreshProblem(precise = true) == null) {
                    runCatching { BridgeService.start(this@ContextActivity) }
                    delay(300)
                }
                withContext(Dispatchers.IO) { vault.refresh(force = manual) }
            } finally { refreshing = false; render() }
            if (!manual || isFinishing || isDestroyed) return@launch
            when (result) {
                ContextVault.RefreshResult.UPDATED -> Snackbar.make(screen.list, R.string.context_location_updated, Snackbar.LENGTH_LONG).show()
                ContextVault.RefreshResult.UNKNOWN_PLACE -> MaterialAlertDialogBuilder(this@ContextActivity)
                    .setTitle(R.string.context_location_updated).setMessage(R.string.context_place_unidentified)
                    .setPositiveButton(R.string.context_identify) { _, _ -> identify() }
                    .setNegativeButton(android.R.string.ok, null).show()
                ContextVault.RefreshResult.SUPERSEDED -> Unit
                else -> showRefreshProblem(result)
            }
        }
    }
    private fun showRefreshProblem(result: ContextVault.RefreshResult, appSettings: Boolean = false) {
        val (message, action) = when (result) {
            ContextVault.RefreshResult.PAUSED -> R.string.context_refresh_paused to R.string.context_resume
            ContextVault.RefreshResult.DELETED -> R.string.context_refresh_deleted to R.string.context_add
            ContextVault.RefreshResult.LISTENER_OFF -> R.string.context_refresh_listener to R.string.context_enable_listener
            ContextVault.RefreshResult.FEATURE_OFF -> R.string.context_refresh_feature to R.string.context_enable_location
            ContextVault.RefreshResult.PERMISSION_REQUIRED -> R.string.context_refresh_permission to if (appSettings) R.string.context_open_settings else R.string.context_allow_location
            ContextVault.RefreshResult.PRECISE_LOCATION_REQUIRED -> R.string.context_refresh_precise to R.string.context_open_settings
            ContextVault.RefreshResult.NOTIFICATIONS_OFF -> R.string.context_refresh_notifications to R.string.context_open_settings
            ContextVault.RefreshResult.LOCATION_OFF -> R.string.context_refresh_location_off to R.string.context_open_settings
            ContextVault.RefreshResult.SERVICE_UNAVAILABLE -> R.string.context_refresh_service to R.string.context_retry
            ContextVault.RefreshResult.INACCURATE_FIX -> R.string.context_refresh_accuracy to R.string.context_retry
            ContextVault.RefreshResult.CLASSIFICATION_FAILED -> R.string.context_refresh_classification to R.string.context_retry
            ContextVault.RefreshResult.BUSY -> R.string.context_refresh_busy to R.string.context_retry
            else -> R.string.context_refresh_no_fix to R.string.context_retry
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.context_refresh).setMessage(message)
            .setPositiveButton(action) { _, _ ->
                when (result) {
                    ContextVault.RefreshResult.PAUSED -> { vault.setEnabled(true); refresh(manual = true) }
                    ContextVault.RefreshResult.DELETED -> addContext()
                    ContextVault.RefreshResult.LISTENER_OFF -> { app.settings.listenerEnabled = true; refresh(manual = true) }
                    ContextVault.RefreshResult.FEATURE_OFF -> { UiActions.setSwitch(app, app.registry.byId("location") as LocationModule, true); refresh(manual = true) }
                    ContextVault.RefreshResult.PERMISSION_REQUIRED -> if (appSettings) openAppSettings() else permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    ContextVault.RefreshResult.PRECISE_LOCATION_REQUIRED -> openAppSettings()
                    ContextVault.RefreshResult.NOTIFICATIONS_OFF -> startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                    ContextVault.RefreshResult.LOCATION_OFF -> startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    else -> refresh(manual = true)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun openAppSettings() = startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    private fun identify() {
        val choices = arrayOf(getString(R.string.context_save_home), getString(R.string.context_save_work))
        MaterialAlertDialogBuilder(this).setTitle(R.string.context_place).setItems(choices) { _, i ->
            if (!vault.identifyPlace(if (i == 0) "home" else "work")) Toast.makeText(this, R.string.context_no_location, Toast.LENGTH_SHORT).show()
            else refresh()
            render()
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun edit(existing: String? = null, value: String = "", temporary: Boolean = false, lifetimeMillis: Long = 24 * 60 * 60_000L) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun field(label: Int, value: String, lines: Int): TextInputEditText {
            val box = TextInputLayout(this, null, M.attr.textInputOutlinedStyle).apply { hint = getString(label) }
            val input = TextInputEditText(box.context).apply { setText(value); maxLines = lines; if (lines == 1) setSingleLine() }
            box.addView(input, LinearLayout.LayoutParams(-1, -2))
            fields.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            return input
        }
        val key = field(R.string.context_key, existing.orEmpty(), 1).apply { isEnabled = existing == null }
        val input = field(R.string.context_value, value, 3)
        val lifetime = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
        }
        val durableChoice = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); setText(R.string.context_tag_durable); isAllCaps = false
        }
        val temporaryChoice = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); setText(R.string.context_tag_temporary); isAllCaps = false
        }
        lifetime.addView(durableChoice, LinearLayout.LayoutParams(0, -2, 1f))
        lifetime.addView(temporaryChoice, LinearLayout.LayoutParams(0, -2, 1f))
        fields.addView(lifetime, LinearLayout.LayoutParams(-1, -2))
        val durationBox = TextInputLayout(this, null, M.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
            hint = getString(R.string.context_expiry)
        }
        val duration = MaterialAutoCompleteTextView(durationBox.context).apply {
            inputType = android.text.InputType.TYPE_NULL
            setAdapter(ArrayAdapter(this@ContextActivity, android.R.layout.simple_dropdown_item_1line,
                listOf(getString(R.string.context_hour), getString(R.string.context_day), getString(R.string.context_week))))
            setText(getString(when {
                lifetimeMillis <= 60 * 60_000L -> R.string.context_hour
                lifetimeMillis >= 7 * 24 * 60 * 60_000L -> R.string.context_week
                else -> R.string.context_day
            }), false)
        }
        durationBox.addView(duration, LinearLayout.LayoutParams(-1, -2))
        fields.addView(durationBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        lifetime.addOnButtonCheckedListener { _, _, _ -> durationBox.visibility = if (lifetime.checkedButtonId == temporaryChoice.id) View.VISIBLE else View.GONE }
        lifetime.check(if (temporary) temporaryChoice.id else durableChoice.id)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (existing == null) R.string.context_add else R.string.context_edit)
            .setView(fields).setPositiveButton(R.string.context_save, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val k = key.text.toString().trim().lowercase().replace(' ', '_')
            if (!ContextFacts.keyValid(k)) { key.error = getString(R.string.context_invalid_key); return@setOnClickListener }
            if (k in listOf("place", "night", "timezone")) { key.error = getString(R.string.context_reserved_key); return@setOnClickListener }
            if (input.text.isNullOrBlank() || (input.text?.length ?: 0) > 240) { input.error = getString(R.string.context_invalid_value); return@setOnClickListener }
            if (runCatching { vault.remember(k, input.text.toString(), lifetime.checkedButtonId == temporaryChoice.id,
                when (duration.text.toString()) {
                    getString(R.string.context_hour) -> 60 * 60_000L
                    getString(R.string.context_week) -> 7 * 24 * 60 * 60_000L
                    else -> 24 * 60 * 60_000L
                }) }.isFailure) { input.error = getString(R.string.context_full); return@setOnClickListener }
            dialog.dismiss(); render()
        } }
        dialog.show()
    }
    private fun label(key: String) = when (key) {
        "place" -> getString(R.string.context_place); "night" -> getString(R.string.context_night)
        "timezone" -> getString(R.string.context_timezone); else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    private fun valueText(f: JSONObject): String {
        if (f.isNull("value")) return getString(if (f.optString("status") == "expired") R.string.context_expired else R.string.context_unknown)
        return when (val v = f.opt("value")) {
            true -> getString(R.string.context_yes); false -> getString(R.string.context_no)
            "unknown" -> getString(R.string.context_unknown); else -> v.toString().replace('_', ' ')
        }
    }
    private fun description(f: JSONObject): String {
        val value = valueText(f)
        if (f.isNull("value")) return value
        val source = when (f.optString("source")) {
            "overnight_pattern" -> getString(R.string.context_source_overnight)
            "weekday_pattern" -> getString(R.string.context_source_weekday)
            "owner" -> getString(R.string.context_source_you); "jev" -> "Jev · ${(f.optDouble("confidence") * 100).toInt()}%"
            "saved_place" -> getString(R.string.context_source_saved); "sunrise_sunset" -> getString(R.string.context_source_sun)
            "local_clock_22_07" -> getString(R.string.context_source_clock); else -> getString(R.string.context_source_device)
        }
        return "$value · $source"
    }
    private fun detailsText(f: JSONObject): String = buildString {
        append(description(f))
        val at = f.optLong("observed_at")
        if (at > 0) append("\n").append(getString(R.string.context_updated, android.text.format.DateUtils.getRelativeTimeSpanString(at)))
        val until = f.optLong("expires_at")
        if (until > 0) append("\n").append(getString(R.string.context_expires, android.text.format.DateUtils.getRelativeTimeSpanString(until)))
    }
    private fun details(title: String, f: JSONObject, place: Boolean = false) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(detailsText(f))
            .apply { if (place) setNeutralButton(R.string.context_identify) { _, _ -> identify() } }
            .setPositiveButton(android.R.string.ok, null).show()
    }
}
