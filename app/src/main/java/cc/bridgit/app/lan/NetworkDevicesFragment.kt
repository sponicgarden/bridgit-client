package cc.bridgit.app.lan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cc.bridgit.app.R
import cc.bridgit.app.outbound.Outbound
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owner-initiated, foreground-only scan. Does not change any agent grant or feature switch. */
class NetworkDevicesFragment : Fragment() {
    /** The Devices tab hosts this list as its List view (owner, 4 Nov 2026) and shows scans on its Map. */
    interface Host {
        fun listHeader(): View?
        fun listFooter(): View?
        fun onScanStarted()
        fun onScanProgress(found: List<NetworkDevices.Device>)
        fun onScanFinished()
        fun setUpCamera(host: String)
    }
    private fun scanListener() = parentFragment as? Host
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Hosts added to Bridgit (cameras and smart devices): highlighted and listed first. */
    private var addedHosts = emptySet<String>()
    private fun readAddedHosts(): Set<String> = runCatching {
        val app = requireActivity().application as cc.bridgit.app.BridgeApp
        (app.lanCameras.all().map { it.host } + app.lanDevices.all().map { it.host }).toSet()
    }.getOrDefault(emptySet())
    private var scan: Job? = null
    private var consent: androidx.appcompat.app.AlertDialog? = null
    private var details: androidx.appcompat.app.AlertDialog? = null
    private lateinit var find: MaterialButton
    private lateinit var stop: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var summary: LinearLayout
    private lateinit var totalCount: TextView
    private lateinit var connectedCount: TextView
    private lateinit var unconnectedCount: TextView
    private lateinit var connectionBar: LinearProgressIndicator
    private lateinit var results: LinearLayout
    private lateinit var groupControls: LinearLayout
    private var groupsCount: TextView? = null
    private lateinit var introduction: View
    private val brands by lazy { DeviceBrands(requireContext()) }
    private val expandedDevices = mutableSetOf<String>()
    private val expanded = mutableMapOf<NetworkDevicePresentation.Category, Boolean>()
    private var historyScope: String? = null
    private var displayedDevices = emptyList<NetworkDevices.Device>()
    /** Tapping a count filters the list (owner, 4 Nov 2026): null = all, true = connected, false = unconnected. */
    /** The summary tiles are tabs (owner, 4 Nov 2026); exactly one is selected. */
    enum class ListMode { ALL, GROUPS, CONNECTED, UNCONNECTED }
    private var mode = ListMode.ALL
    private val metricColumns = mutableListOf<Pair<ListMode, LinearLayout>>()
    private fun visibleDevices() = when (mode) {
        ListMode.CONNECTED -> displayedDevices.filter { it.present }
        ListMode.UNCONNECTED -> displayedDevices.filter { !it.present }
        else -> displayedDevices
    }
    /** Devices, Connected and Unconnected open every group; Groups folds them all to their headers. */
    private fun select(next: ListMode) {
        mode = next
        NetworkDevicePresentation.Category.entries.forEach { expanded[it] = next != ListMode.GROUPS }
        updateConnectionOverview(); renderGroups()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.b_surface)) }
        val toolbar = MaterialToolbar(context).apply {
            setTitle(R.string.network_devices_title)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.cd_back)
            setNavigationOnClickListener { findNavController().popBackStack() }
        }
        toolbar.menu.add(R.string.network_coverage).setIcon(R.drawable.ic_info)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            showCoverage()
            true
        }
        val embedded = scanListener() != null
        // Devices tab: the list opens on the Devices tile, every group open.
        if (embedded && expanded.isEmpty()) NetworkDevicePresentation.Category.entries.forEach { expanded[it] = mode != ListMode.GROUPS }
        if (!embedded) {
            root.addView(toolbar)
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(top = bars.top, bottom = bars.bottom); insets
            }
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (embedded) 12 else 20), 0, dp(if (embedded) 12 else 20), dp(12)) }
        scanListener()?.listHeader()?.let { header ->
            (header.parent as? ViewGroup)?.removeView(header)
            body.addView(header, LinearLayout.LayoutParams(-1, -2))
        }
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            setBackgroundResource(R.drawable.design_bg_info)
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(badge(R.drawable.ic_network_discovery, R.color.b_primary, R.color.b_surface_container_high), LinearLayout.LayoutParams(dp(48), dp(48)))
        heading.addView(label(getString(R.string.network_hero_title), 22f, R.color.b_on_surface, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        hero.addView(heading)
        hero.addView(label(getString(R.string.network_explanation), 14f, R.color.b_on_surface_variant),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        introduction = hero
        body.addView(hero)
        find = MaterialButton(context).apply {
            setText(R.string.network_find)
            isSingleLine = false
            maxLines = 3
            ellipsize = null
            setIconResource(R.drawable.ic_search)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            cornerRadius = dp(16)
            minHeight = dp(48)
            setOnClickListener { requestScan() }
        }
        if (embedded) {
            // Devices tab (owner, 4 Nov 2026): one slim line, like the Map's header, instead of a full-width button.
            // Owner, 4 Nov 2026: the List spells it out, "Level 2 Diagnostic" and a refresh icon (the Map says "L2").
            find.apply {
                text = "Level 2 Diagnostic"; contentDescription = "Run Level 2 Diagnostic: look around this network"
                setIconResource(R.drawable.ic_refresh); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
                iconSize = dp(18); iconPadding = dp(6); iconTint = ColorStateList.valueOf(color(R.color.b_primary))
                cornerRadius = dp(12); textSize = 14f; minHeight = dp(44)
                insetTop = 0; insetBottom = 0
                setPadding(dp(14), 0, dp(12), 0)
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                strokeColor = ColorStateList.valueOf(color(R.color.b_primary)); strokeWidth = dp(1)
                setTextColor(color(R.color.b_primary))
            }
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), 0, dp(6)) }
            line.addView(label(getString(R.string.devices_on_network), 16f, R.color.b_on_surface, true), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(find, LinearLayout.LayoutParams(-2, -2))
            body.addView(line, LinearLayout.LayoutParams(-1, -2))
        } else body.addView(find, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        stop = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.network_stop); setIconResource(R.drawable.agents_ic_pause); cornerRadius = dp(16); isVisible = false
            setOnClickListener { stopScan() }
        }
        body.addView(stop, LinearLayout.LayoutParams(-1, -2))
        progress = LinearProgressIndicator(context).apply { isIndeterminate = true; isVisible = false; trackCornerRadius = dp(3) }
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) })
        summary = connectionOverview()
        body.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (embedded) 2 else 8); bottomMargin = dp(4) })
        status = TextView(context).apply {
            setText(R.string.network_ready); textSize = 13f; setTextColor(color(R.color.b_on_surface_variant)); setPadding(dp(2), dp(8), dp(2), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            doAfterTextChanged { isVisible = !it.isNullOrBlank() }
        }
        body.addView(status)
        groupControls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isVisible = false }
        fun groupAction(title: Int, value: Boolean) = MaterialButton(context).apply {
            setText(title); cornerRadius = dp(12); minHeight = dp(48)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(color(R.color.b_primary)); strokeWidth = 0; elevation = 0f
            setOnClickListener {
                NetworkDevicePresentation.groups(displayedDevices).forEach { expanded[it.category] = value }
                renderGroups()
            }
        }
        if (embedded) {
            // The Groups tile in the summary does this on the Devices tab.
            groupControls.isVisible = false
        } else {
            groupControls.addView(groupAction(R.string.network_expand_all, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            groupControls.addView(groupAction(R.string.network_collapse_all, false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            body.addView(groupControls, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        }
        results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results)
        scanListener()?.listFooter()?.let { footer ->
            (footer.parent as? ViewGroup)?.removeView(footer)
            body.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val constrained = FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(760)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        root.addView(ScrollView(context).apply { addView(constrained) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun connectionOverview(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(color(R.color.b_surface_container_low))
        }
        isVisible = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val counts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        metricColumns.clear()
        fun metric(title: Int, tint: Int, tab: ListMode): TextView {
            val four = scanListener() != null
            val value = label("0", if (four) 22f else 24f, tint, true).apply { maxLines = 1 }
            val name = label(getString(title), 11f, R.color.b_on_surface_variant).apply {
                maxLines = 1
                // Four tiles on a narrow phone: "Unconnected" shrinks a little rather than wrapping or cutting off.
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(if (four) 6 else 8), dp(4), dp(if (four) 4 else 8), dp(6))
                addView(value)
                addView(name, LinearLayout.LayoutParams(-1, dp(16)))
                isClickable = true; isFocusable = true
                setOnClickListener { select(tab) }
            }
            metricColumns.add(tab to column)
            counts.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(2) })
            return value
        }
        totalCount = metric(R.string.network_count_devices, R.color.b_on_surface, ListMode.ALL)
        // Devices tab (owner, 4 Nov 2026): Groups counts the groups and shows only their headers.
        if (scanListener() != null) groupsCount = metric(R.string.network_count_groups, R.color.b_on_surface, ListMode.GROUPS)
        connectedCount = metric(R.string.network_count_connected, R.color.b_success, ListMode.CONNECTED)
        unconnectedCount = metric(R.string.network_count_unconnected, R.color.b_on_surface_variant, ListMode.UNCONNECTED)
        addView(counts)
        connectionBar = LinearProgressIndicator(context).apply {
            isIndeterminate = false; trackThickness = dp(4); trackCornerRadius = dp(2)
            trackStopIndicatorSize = 0
            setIndicatorColor(color(R.color.b_success)); trackColor = color(R.color.b_outline_variant)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(connectionBar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
    }

    private fun updateConnectionOverview() {
        val total = displayedDevices.size
        val connected = displayedDevices.count { it.present }
        summary.isVisible = total > 0
        totalCount.text = total.toString()
        groupsCount?.text = NetworkDevicePresentation.groups(displayedDevices).size.toString()
        connectedCount.text = connected.toString()
        unconnectedCount.text = (total - connected).toString()
        connectionBar.max = total.coerceAtLeast(1)
        connectionBar.progress = connected
        summary.contentDescription = getString(R.string.network_count_summary, total, connected, total - connected)
        if (mode == ListMode.CONNECTED && connected == 0 || mode == ListMode.UNCONNECTED && total == connected) { mode = ListMode.ALL }
        // Tab look: the selected tile is filled with a primary bar along its bottom; the others are plain.
        metricColumns.forEach { (tab, column) ->
            val on = tab == mode
            column.background = if (on) android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color(R.color.b_surface_container_high)) },
                GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color(R.color.b_primary)) })).apply {
                    setLayerHeight(1, dp(3)); setLayerGravity(1, Gravity.BOTTOM); setLayerInset(1, dp(10), 0, dp(10), 0)
                } else null
            column.isSelected = on
            column.contentDescription = (column.getChildAt(1) as? TextView)?.text.toString() +
                ", " + (column.getChildAt(0) as? TextView)?.text + if (on) ", selected" else ""
            ViewCompat.setAccessibilityDelegate(column, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.roleDescription = "Tab"; info.isSelected = host.isSelected
                }
            })
        }
    }

    private fun showCoverage() {
        val context = requireContext()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(color(R.color.b_on_surface))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            selectable(this)
            contentDescription = getString(R.string.network_close_coverage)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { details?.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(getString(R.string.network_coverage), 18f, R.color.b_on_surface, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(8) })
        panel.addView(header)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        fun category(title: String, description: String) {
            list.addView(label(title, 13f, R.color.b_primary, true).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            list.addView(label(description, 13f, R.color.b_on_surface_variant),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3); bottomMargin = dp(3) })
        }
        DiscoveryCatalog.mdns.groupBy { it.group }.forEach { (group, services) ->
            category(group, services.map { it.label }.distinct().joinToString(" · "))
        }
        category(getString(R.string.network_other_discovery), getString(R.string.network_other_discovery_details))
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * .62f).toInt()
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) maxHeight
                    else minOf(View.MeasureSpec.getSize(heightMeasureSpec), maxHeight)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(list) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        details = MaterialAlertDialogBuilder(context).setView(panel).create().also { it.show() }
    }

    /** Asks once per tap (the consent dialog), then scans. Also used by the Devices Map's Run button. */
    fun requestScan() {
        if (view == null || scan?.isActive == true) return
        consent = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.network_consent_title)
            .setMessage(R.string.network_consent).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.network_allow) { _, _ -> startScan() }.show()
    }

    val scanning get() = scan?.isActive == true

    private fun startScan() {
        if (scan?.isActive == true) return
        if (Outbound.paused()) { status.setText(R.string.network_paused); return }
        val context = requireContext().applicationContext
        busy(true)
        status.setText(R.string.network_scanning)
        scanListener()?.onScanStarted()
        scan = viewLifecycleOwner.lifecycleScope.launch(Outbound.context(Outbound.Actor.OWNER)) {
            try {
                val report = withTimeout(20_000) {
                    withContext(Dispatchers.IO) {
                        val scope = currentCoroutineContext()
                        val lan = HostLan(context)
                        historyScope = lan.historyScope(context)
                        LanDiscovery(context, lan, onFound = { found -> main.post { if (isAdded && view != null) scanListener()?.onScanProgress(found) } }) {
                            scope.ensureActive()
                            if (Outbound.paused()) throw Outbound.Paused()
                        }
                            .run(emptyList(), false)
                    }
                }
                val devices = DiscoveryHistory(context).read(historyScope!!)
                status.text = if (devices.isEmpty()) getString(R.string.network_empty)
                    else ""
                val methods = report.getJSONArray("methods")
                if ((0 until methods.length()).any { methods.getJSONObject(it).optString("status") != "completed" })
                    status.append((if (status.text.isNotEmpty()) "\n" else "") + getString(R.string.network_partial))
                introduction.isVisible = devices.isEmpty()
                displayedDevices = devices
                updateConnectionOverview()
                groupControls.isVisible = devices.isNotEmpty() && scanListener() == null
                renderGroups()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                status.setText(R.string.network_unavailable)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.setText(R.string.network_unavailable) }
            finally { busy(false); if (view != null) scanListener()?.onScanFinished() }
        }
    }

    private fun recheck(device: NetworkDevices.Device, button: MaterialButton) {
        if (scan?.isActive == true) return
        if (Outbound.paused()) { status.setText(R.string.network_paused); return }
        val context = requireContext().applicationContext
        val expectedScope = historyScope ?: return
        button.isEnabled = false
        button.text = "Checking…"
        scan = viewLifecycleOwner.lifecycleScope.launch(Outbound.context(Outbound.Actor.OWNER)) {
            try {
                val reply = withContext(Dispatchers.IO) {
                    val lan = HostLan(context)
                    require(lan.historyScope(context) == expectedScope) { "Network changed" }
                    val reachable = DeviceRecheck.ping(lan, device.host)
                    currentCoroutineContext().ensureActive()
                    require(HostLan(context).historyScope(context) == expectedScope) { "Network changed" }
                    if (reachable) DiscoveryHistory(context).update(expectedScope, listOf(device), false)
                    reachable
                }
                if (historyScope == expectedScope) {
                    displayedDevices = DiscoveryHistory(context).read(expectedScope)
                    updateConnectionOverview()
                    renderGroups()
                    status.text = if (reply) "${device.host} replied just now."
                        else "No ping reply from ${device.host}. Last seen is unchanged; some devices do not answer ping."
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "Could not recheck this device. Check the network and try again." }
            finally { button.isEnabled = true; button.text = "Recheck" }
        }
    }

    private fun renderGroups() {
        results.removeAllViews()
        addedHosts = readAddedHosts()
        NetworkDevicePresentation.groups(visibleDevices(), addedHosts).forEach { group ->
            var open = expanded[group.category] ?: group.category.expandedByDefault
            val section = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            val cards = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; isVisible = open }
            var populated = false
            fun populate() {
                if (populated) return
                group.devices.forEachIndexed { index, device -> cards.addView(deviceRow(device, index), LinearLayout.LayoutParams(-1, -2)) }
                populated = true
            }
            val arrow = ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_chevron); rotation = if (open) 90f else 0f
                imageTintList = ColorStateList.valueOf(color(R.color.b_on_surface_variant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val commonBrands = brands.common(group.devices).map { it.first }
            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(color(R.color.network_category_start), color(R.color.network_category_end)))
                selectable(this)
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(6), dp(10)) }
                row.addView(label(group.category.title, 15f, R.color.network_category_text).apply {
                    typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                    maxWidth = dp(140); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2))
                val logoStrip = object : LinearLayout(context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        var remaining = View.MeasureSpec.getSize(widthMeasureSpec)
                        for (index in 0 until childCount) {
                            val child = getChildAt(index)
                            val params = child.layoutParams as LinearLayout.LayoutParams
                            val needed = params.width + params.marginEnd
                            val fits = remaining >= needed
                            child.visibility = if (fits) View.VISIBLE else View.GONE
                            if (fits) remaining -= needed
                        }
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }.apply {
                    gravity = Gravity.CENTER_VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                commonBrands.forEach { brand ->
                    val drawable = resources.getIdentifier(brand.resource, "drawable", context.packageName)
                        .takeIf { it != 0 }?.let { androidx.core.content.ContextCompat.getDrawable(context, it) }
                    val aspect = drawable?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.coerceAtLeast(1) } ?: 2f
                    val width = (dp(18) * aspect).toInt().coerceIn(dp(20), dp(52))
                    logoStrip.addView(brandLogo(brand), LinearLayout.LayoutParams(width, dp(20)).apply { marginEnd = dp(8) })
                }
                row.addView(logoStrip, LinearLayout.LayoutParams(0, dp(22), 1f).apply { marginStart = dp(10); marginEnd = dp(6) })
                row.addView(label("${group.devices.count { it.present }}/${group.devices.size}", 13f, R.color.b_primary, true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                row.addView(arrow, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(row)
                addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
            }
            fun describeState() {
                header.contentDescription = getString(R.string.network_category_count, group.category.title, group.devices.size) +
                    if (commonBrands.isEmpty()) "" else ", " + commonBrands.joinToString { it.name }
                ViewCompat.setStateDescription(header, getString(if (open) R.string.network_expanded else R.string.network_collapsed))
            }
            header.setOnClickListener {
                open = !open; expanded[group.category] = open
                if (open) populate()
                cards.isVisible = open; arrow.rotation = if (open) 90f else 0f; describeState()
            }
            describeState()
            if (open) populate()
            section.addView(header, LinearLayout.LayoutParams(-1, -2))
            section.addView(cards)
            results.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
    }

    private fun selectable(view: View) {
        val attributes = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        view.foreground = attributes.getDrawable(0); attributes.recycle()
    }
    private fun divider() = View(requireContext()).apply { setBackgroundColor(color(R.color.b_outline_variant)) }
    private fun brandLogo(brand: DeviceBrands.Brand): View {
        val resource = resources.getIdentifier(brand.resource, "drawable", requireContext().packageName)
        return if (resource != 0) ImageView(requireContext()).apply {
            setImageResource(resource); scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(color(R.color.b_on_surface_variant))
            contentDescription = brand.name
        } else label(brand.name, 10f, R.color.b_on_surface_variant).apply { gravity = Gravity.CENTER }
    }

    private fun color(resource: Int) = androidx.core.content.ContextCompat.getColor(requireContext(), resource)

    private fun label(value: String, size: Float, tint: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color(tint))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** Equal icon bounds and full-width labels keep both status lines on the same start edge. */
    private fun statusLabel(value: String, icon: Int, tint: Int) = label(value, 10f, tint).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()
        drawable?.setTint(color(tint))
        drawable?.setBounds(0, 0, dp(13), dp(13))
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = dp(4)
    }

    private fun badge(icon: Int, tint: Int, backgroundTint: Int) = ImageView(requireContext()).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color(tint))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color(backgroundTint)) }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun deviceRow(device: NetworkDevices.Device, index: Int): View {
        val info = NetworkDevicePresentation.describe(device)
        val (icon, accent, fill) = when (info.kind) {
            NetworkDevicePresentation.Kind.CAMERA -> Triple(R.drawable.ic_camera, R.color.b_success, R.color.b_success_container)
            NetworkDevicePresentation.Kind.TV, NetworkDevicePresentation.Kind.CAST -> Triple(R.drawable.ic_network_tv, R.color.b_primary, R.color.b_primary_container)
            NetworkDevicePresentation.Kind.COMPUTER -> Triple(R.drawable.ic_computer, R.color.b_primary, R.color.b_primary_container)
            NetworkDevicePresentation.Kind.MOBILE -> Triple(R.drawable.agentact_ic_phone, R.color.b_primary, R.color.b_primary_container)
            NetworkDevicePresentation.Kind.SPEAKER, NetworkDevicePresentation.Kind.AUDIO -> Triple(R.drawable.agentact_ic_speaker, R.color.b_secondary, R.color.b_secondary_container)
            NetworkDevicePresentation.Kind.PRINTER -> Triple(R.drawable.ic_network_printer, R.color.b_primary, R.color.b_primary_container)
            else -> Triple(R.drawable.ic_dns, R.color.b_on_surface_variant, R.color.b_surface_container_high)
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val added = device.host in addedHosts
            // Added to Bridgit: a tinted row with a primary edge, listed first in its group.
            if (added) background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(color(R.color.b_primary_container)) },
                GradientDrawable().apply { setColor(color(R.color.b_primary)) })).apply {
                    setLayerWidth(1, dp(3)); setLayerGravity(1, Gravity.START)
                }
            else setBackgroundColor(color(if (index % 2 == 0) R.color.b_surface_container_low else R.color.b_surface))
            selectable(this)
            // Owner, 4 Nov 2026: compact rows. Icon, then name (with brand logo) over type · address · model,
            // then the service chips; last seen and the Bridgit link sit on the right. Recheck lives in the details.
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                setPadding(dp(8), dp(8), dp(6), dp(8))
            }
            // Left column (owner, 4 Nov 2026): brand logo pinned top left, device icon pinned bottom left.
            val identity = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
            brands.find(device)?.let { brand ->
                identity.addView(brandLogo(brand).apply { (this as? ImageView)?.scaleType = ImageView.ScaleType.FIT_START; (this as? TextView)?.gravity = Gravity.START },
                    LinearLayout.LayoutParams(dp(42), dp(14)).apply { topMargin = dp(LOGO_TOP); bottomMargin = dp(4) })
            }
            identity.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            identity.addView(badge(icon, accent, fill).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) },
                LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(identity, LinearLayout.LayoutParams(dp(42), -1).apply { marginEnd = dp(10) })
            val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(label(info.title, 15f, R.color.b_on_surface, true).apply {
                includeFontPadding = false // so the name's top lines up with the brand logo's top
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
            copy.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
            val meta = listOfNotNull(info.kind.label, device.host, info.model.takeIf { it.isNotBlank() }?.let(DeviceModelNames::label))
            copy.addView(label(meta.joinToString(" · "), 12f, R.color.b_on_surface_variant).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            val services = device.services.map(NetworkDevices::serviceLabel).distinct().filter { !it.equals(info.kind.label, true) }
            if (services.isNotEmpty()) {
                val pills = ServicePills(context)
                services.forEach { service ->
                    pills.addView(label(service, 10f, R.color.b_on_surface_variant).apply {
                        setPadding(dp(7), dp(1), dp(7), dp(2))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(20).toFloat(); setColor(color(R.color.b_surface_container_high))
                            setStroke(dp(1), color(R.color.b_outline_variant))
                        }
                    }, ViewGroup.LayoutParams(-2, -2))
                }
                copy.addView(pills, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            }
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            val connection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            connection.addView(statusLabel(DeviceLastSeen.relative(device.lastSeen),
                R.drawable.ic_network_wifi,
                if (device.present) R.color.b_success else R.color.b_on_surface_variant),
                LinearLayout.LayoutParams(-1, -2))
            if (added) connection.addView(statusLabel("Added",
                R.drawable.ic_network_link, if (device.present) R.color.b_primary else R.color.b_on_surface_variant).apply { contentDescription = "Added to Bridgit" },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            else if (DeviceInterfaces.available(device) != null) connection.addView(statusLabel("Bridgit interfaced",
                R.drawable.ic_network_link, if (device.present) R.color.b_primary else R.color.b_on_surface_variant),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            row.addView(connection, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            val arrow = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(color(R.color.b_outline))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(arrow, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4); gravity = Gravity.CENTER_VERTICAL })
            val detail = deviceDetails(device).apply { isVisible = device.host in expandedDevices }
            fun updateState() {
                arrow.rotation = if (detail.isVisible) 90f else 0f
                ViewCompat.setStateDescription(this, getString(if (detail.isVisible) R.string.network_expanded else R.string.network_collapsed))
            }
            setOnClickListener {
                detail.isVisible = !detail.isVisible
                if (detail.isVisible) expandedDevices.add(device.host) else expandedDevices.remove(device.host)
                updateState()
            }
            updateState()
            addView(row)
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(60); marginEnd = dp(6); bottomMargin = dp(10) })
            addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun deviceDetails(device: NetworkDevices.Device): View {
        val info = NetworkDevicePresentation.describe(device)
        val lastSeen = if (device.lastSeen > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(device.lastSeen)) else "Not recorded"
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(color(R.color.b_surface_container_high), color(R.color.b_surface_container_low)))
            addView(label("CONNECTION", 10f, R.color.b_primary, true).apply { letterSpacing = .08f })
            addView(label("Last observed · ${DeviceLastSeen.relative(device.lastSeen)}", 14f,
                if (device.present) R.color.b_success else R.color.b_on_surface_variant),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(label("Last seen · $lastSeen", 12f, R.color.b_on_surface_variant),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
            DeviceInterfaces.available(device)?.let { integration ->
                addView(label("BRIDGIT INTERFACE", 10f, R.color.b_primary, true).apply { letterSpacing = .08f })
                addView(label("${integration.label} · Setup available", 13f, R.color.b_on_surface),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(12) })
            }
            if (DeviceInterfaces.available(device)?.adapter == "ssh") addView(Button(context).apply {
                text = "Manage SSH connections"
                setOnClickListener { findNavController().navigate(R.id.sshConnections) }
            })
            addView(label(getString(R.string.network_suggested_type, info.kind.label), 12f, R.color.b_on_surface_variant))
            if (info.model.isNotBlank()) addView(label(DeviceModelNames.label(info.model), 12f, R.color.b_on_surface),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val identifiers = listOf(info.model.takeIf { DeviceModelNames.label(it) != it }.orEmpty(), info.identifier).filter { it.isNotBlank() }
            if (identifiers.isNotEmpty()) addView(label(identifiers.joinToString(" · "), 10f, R.color.b_outline).apply {
                typeface = Typeface.MONOSPACE
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            if (device.cameraCandidate) addView(label(getString(R.string.network_camera_note), 12f, R.color.b_on_surface_variant),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Recheck"; cornerRadius = dp(14)
                contentDescription = "Recheck ${info.title}"
                setOnClickListener { recheck(device, this) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.network_copy_address); setIconResource(R.drawable.ic_copy); cornerRadius = dp(14)
                setOnClickListener {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(getString(R.string.network_devices_title), device.host))
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (device.cameraCandidate) addView(MaterialButton(context).apply {
                setText(R.string.network_camera_setup); cornerRadius = dp(14)
                setOnClickListener {
                    scanListener()?.setUpCamera(device.host) ?: findNavController().navigate(R.id.cameras, bundleOf("discoveredHost" to device.host))
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun busy(value: Boolean) { find.isEnabled = !value; stop.isVisible = value; progress.isVisible = value }
    private fun stopScan() {
        scan?.cancel(); scan = null
        if (view != null) { busy(false); status.setText(R.string.network_stopped) }
    }
    override fun onStart() {
        super.onStart()
        val context = requireContext()
        val scope = runCatching { HostLan(context).historyScope(context) }.getOrNull()
        historyScope = scope
        displayedDevices = scope?.let { DiscoveryHistory(context).read(it) }.orEmpty()
        introduction.isVisible = displayedDevices.isEmpty()
        groupControls.isVisible = displayedDevices.isNotEmpty() && scanListener() == null
        status.text = if (displayedDevices.isNotEmpty()) "" else getString(R.string.network_ready)
        updateConnectionOverview()
        renderGroups()
        // Expire green indicators while the screen remains open; no background network scan.
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                if (scan?.isActive != true) {
                    val previousScope = historyScope
                    historyScope = runCatching { HostLan(context).historyScope(context) }.getOrNull()
                    displayedDevices = historyScope?.let { DiscoveryHistory(context).read(it) }.orEmpty()
                    introduction.isVisible = displayedDevices.isEmpty()
                    groupControls.isVisible = displayedDevices.isNotEmpty() && scanListener() == null
                    if (historyScope != previousScope || displayedDevices.isEmpty())
                        status.text = if (displayedDevices.isNotEmpty()) "" else getString(R.string.network_ready)
                    updateConnectionOverview()
                    renderGroups()
                }
            }
        }.also { expiry = it }
    }
    private var expiry: Job? = null
    override fun onStop() {
        expiry?.cancel(); expiry = null
        stopScan()
        consent?.dismiss(); consent = null
        details?.dismiss(); details = null
        super.onStop()
    }
    override fun onDestroyView() { main.removeCallbacksAndMessages(null); scan?.cancel(); super.onDestroyView() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private companion object { const val LOGO_TOP = 3 }
}
