package cc.bridgit.app.ui.applets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.platform.DeviceName
import cc.bridgit.app.databinding.FragmentAppletConfigBinding
import cc.bridgit.app.databinding.ItemAgentAppBinding
import cc.bridgit.app.databinding.ItemAvailableAppletBinding
import cc.bridgit.app.applets.AppletInstallActivity
import cc.bridgit.app.applets.AppletLibrary
import cc.bridgit.app.applets.AppletBudget
import org.json.JSONObject
import android.text.format.DateUtils

/**
 * Applets › Config (owner, 4 Nov 2026): set up and find applets. Installed ones first (Pet monitor once its web
 * portal is on or it runs, and the apps agents built), then the ones you can add, and how to build your own.
 */
class AppletConfigFragment : Fragment() {
    private var _b: FragmentAppletConfigBinding? = null
    private val b get() = _b!!
    private var pageJob: kotlinx.coroutines.Job? = null
    private var entries = emptyList<AppletLibrary.Entry>()
    private val cursors = mutableListOf<String?>(null)
    private var libraryQuery = ""
    private var displayedQuery = ""
    private val app get() = requireActivity().application as BridgeApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAppletConfigBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.dogMonitor.setOnClickListener { findNavController().navigate(R.id.dogMonitor) }
        b.connectionsCard.setOnClickListener { startActivity(Intent(requireContext(), cc.bridgit.app.platform.ConnectionsActivity::class.java)) }
        b.createApplet.setOnClickListener {
            cc.bridgit.app.ui.interactions.AppletAgentHandoff.open(requireActivity(), app) {
                findNavController().navigate(R.id.addAgent)
            }
        }
        b.buildApplet.setOnClickListener { openBuildInstructions() }
        val search = android.widget.Button(requireContext()).apply {
            text = "Search applets"
            setOnClickListener {
                val input = android.widget.EditText(requireContext()).apply { setText(libraryQuery); maxLines = 1 }
                MaterialAlertDialogBuilder(requireContext()).setTitle("Search applets").setView(input)
                    .setPositiveButton("Search") { _, _ -> libraryQuery = input.text.toString().take(100); loadLibrary() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        b.availableList.addView(search)
        loadLibrary()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.dog.state.collect { d ->
                    _b?.dogMonitorRunning?.isVisible = d.running
                    placeDog(app.remoteView.dogBackend || d.running)
                }
            }
        }
        // Apps agents built (tally watches): refreshed every 10 s while the screen is shown. The Pet monitor's
        // web portal switch is read on the same beat, since it changes on another screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    placeDog(app.remoteView.dogBackend || app.dog.state.value.running)
                    val installed = app.applets.all()
                    showAgentApps(installed)
                    showCatalogEntries(installed)
                    delay(10_000)
                }
            }
        }
    }

    private fun loadLibrary(after: String? = null, query: String = libraryQuery) {
        pageJob?.cancel()
        val application = app
        pageJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { AppletLibrary.page(application, after, query) }
            }
            val b = _b ?: return@launch
            // Keep local installed applets visible on download failure.
            if (result.isSuccess) {
                val page = result.getOrThrow()
                entries = page.entries
                displayedQuery = query
                if (after == null) cursors.clear()
                val existing = cursors.indexOf(after)
                if (existing >= 0) cursors.subList(existing + 1, cursors.size).clear() else cursors.add(after)
                clearCatalogRows()
                showCatalogEntries(app.applets.all())
                if (cursors.size > 1) {
                    b.availableList.addView(android.widget.Button(requireContext()).apply {
                        tag = "library-more"; text = "Previous applets"; setOnClickListener { loadLibrary(cursors[cursors.lastIndex - 1], displayedQuery) }
                    })
                }
                page.next?.let { cursor ->
                    b.availableList.addView(android.widget.Button(requireContext()).apply {
                        tag = "library-more"; text = "More applets"; setOnClickListener { loadLibrary(cursor, displayedQuery) }
                    })
                }
            } else {
                com.google.android.material.snackbar.Snackbar.make(b.root, "Couldn't load applets", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction("Retry") { loadLibrary(after, query) }.show()
            }
        }
    }

    private fun clearCatalogRows() {
        for (i in b.availableList.childCount - 1 downTo 0) {
            val row = b.availableList.getChildAt(i)
            if ((row.tag as? String)?.startsWith("bundled:") == true || row.tag == "library-more") b.availableList.removeView(row)
        }
    }

    private fun openBuildInstructions() {
        startActivity(Intent(requireContext(), BuildInstructionsActivity::class.java))
    }

    /** Pet monitor's card sits under Installed once set up, under Available before that. */
    private fun placeDog(installed: Boolean) {
        val b = _b ?: return
        val target = if (installed) b.installedList else b.availableList
        val card = b.dogMonitor
        if (card.parent !== target) {
            (card.parent as? ViewGroup)?.removeView(card)
            target.addView(card, 0)
        }
        updateEmpty()
    }

    /** Render the current registry page against a single snapshot of installed packages. */
    private fun showCatalogEntries(installed: List<JSONObject>) {
        val b = _b ?: return
        val installedIds = installed.mapTo(HashSet()) { it.optString("package_id") }
        val rows = b.availableList.children.associateBy { it.tag }
        for (e in entries) {
            val tag = "bundled:${e.key}"
            val card = rows[tag]
            if (e.id in installedIds) { card?.let { b.availableList.removeView(it) }; continue }
            if (card != null) continue
            val item = ItemAvailableAppletBinding.inflate(layoutInflater, b.availableList, true)
            item.root.tag = tag
            item.availableIcon.setImageResource(e.icon)
            item.availableTitle.text = e.title
            item.availableDesc.text = e.desc
            item.root.contentDescription = getString(R.string.applets_bundled_review_cd, e.title) + ". " + e.desc
            item.root.setOnClickListener {
                startActivity(Intent(requireContext(), AppletInstallActivity::class.java).setData(android.net.Uri.parse(e.installUri)))
            }
        }
    }

    private fun updateEmpty() {
        val b = _b ?: return
        b.installedEmpty.isVisible = b.installedList.childCount == 0 && !b.agentAppsPanel.isVisible
    }

    private fun showAgentApps(applets: List<JSONObject> = app.applets.all()) {
        val b = _b ?: return
        // An installed applet's own watches show under its package row, not as a separate agent app.
        val appletAgents = applets.mapNotNull { it.optString("agent_id").ifEmpty { null } }.toSet()
        val list = app.watches.tallies().filter { it.agentId !in appletAgents }
        b.agentAppsPanel.isVisible = list.isNotEmpty() || applets.isNotEmpty()
        b.agentAppsList.removeAllViews()
        for (r in applets) {
            val item = ItemAgentAppBinding.inflate(layoutInflater, b.agentAppsList, true)
            item.agentAppTitle.text = r.optString("name")
            val agentId = r.optString("agent_id")
            val watchTimes = app.watches.list(agentId).mapNotNull { w ->
                w.json(false).optJSONObject("last_check")?.optString("at")?.let(AppletBudget::parseTime)
            }
            val workflows = app.platform.store.list(agentId, "workflow", 1000)
            val schedule = app.schedules.record(agentId)?.optJSONObject("state")
            val executions = app.platform.store.list(agentId, "execution", 1000)
            val times = watchTimes + workflows.map { it.optLong("last_run") } +
                executions.map { maxOf(it.optLong("started_at"), it.optLong("finished_at")) } +
                (schedule?.keys()?.asSequence()?.map { schedule.optJSONObject(it)?.optLong("last_run_at") ?: 0L }?.toList() ?: emptyList())
            val last = maxOf(times.maxOrNull() ?: 0L, if (r.optString("native") == "dog") app.dog.state.value.frameAt else 0L)
            val state = cc.bridgit.app.applets.AppletLifecycle.state(r).replaceFirstChar { it.uppercase() }
            val mode = cc.bridgit.app.applets.AppletPackage.runMode(r.getJSONObject("definition")).replaceFirstChar { it.uppercase() }
            item.agentAppToday.text = "$mode · $state · ${activityLine(last)}"
            item.agentAppHome.text = getString(R.string.library_home, DeviceName.of(app))
            val definition = r.getJSONObject("definition")
            val icon = definition.optJSONObject("ui")?.optString("icon")?.let { definition.optJSONObject("assets")?.optJSONObject(it) }
            val bitmap = icon?.let { asset -> runCatching {
                val bytes = cc.bridgit.app.applets.AppletPackage.pngBytes(asset)
                val header = java.nio.ByteBuffer.wrap(bytes)
                val edge = maxOf(header.getInt(16), header.getInt(20))
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, Integer.highestOneBit(edge / 96)) }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull() }
            if (bitmap == null) item.agentAppIcon.setImageResource(R.drawable.ic_nav_applets)
            else { item.agentAppIcon.imageTintList = null; item.agentAppIcon.setImageBitmap(bitmap) }
            item.agentAppMeta.text = getString(R.string.installed_applet_meta, r.optString("version"))
            val manage = View.OnClickListener {
                startActivity(Intent(requireContext(), AppletInstallActivity::class.java)
                    .putExtra(AppletInstallActivity.EXTRA_INSTANCE, r.getString("id")))
            }
            val hasScreen = app.applets.hasCard(r)
            item.root.setOnClickListener { v ->
                if (hasScreen) {
                    app.applets.checkTemplateSoon(r.getString("id"))
                    startActivity(AppletNavigation.openIntent(app, r))
                } else manage.onClick(v)
            }
            app.applets.checkTemplateSoon(r.getString("id"))
            item.agentAppUpdate.isVisible = app.applets.templateUpdate(r) != null
            item.agentAppUpdate.contentDescription = getString(R.string.applets_update_named, r.optString("name"))
            item.agentAppUpdate.setOnClickListener {
                startActivity(Intent(requireContext(), AppletInstallActivity::class.java)
                    .putExtra(AppletInstallActivity.EXTRA_INSTANCE, r.getString("id"))
                    .putExtra(AppletInstallActivity.EXTRA_UPDATE, true))
            }
            item.agentAppAction.contentDescription = getString(R.string.applets_more_options_cd, r.optString("name"))
            item.agentAppAction.tooltipText = item.agentAppAction.contentDescription
            item.agentAppAction.setOnClickListener { anchor ->
                androidx.appcompat.widget.PopupMenu(requireContext(), anchor).apply {
                    menu.add(R.string.installed_applet_manage).setOnMenuItemClickListener {
                        manage.onClick(anchor); true
                    }
                    menu.add(R.string.modify_applet).setOnMenuItemClickListener {
                        cc.bridgit.app.ui.interactions.AppletAgentHandoff.open(requireActivity(), app, r.getString("id")) {
                            findNavController().navigate(R.id.addAgent)
                        }
                        true
                    }
                    show()
                }
            }

            androidx.core.view.ViewCompat.replaceAccessibilityAction(item.root,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                if (hasScreen) getString(R.string.applets_action_cd, getString(R.string.applets_open), r.optString("name"))
                else getString(R.string.applets_manage_named, r.optString("name")), null)
        }
        for (w in list) {
            val item = ItemAgentAppBinding.inflate(layoutInflater, b.agentAppsList, true)
            item.agentAppTitle.text = w.spec.label
            val last = w.json(false).optJSONObject("last_check")?.optString("at")?.let(AppletBudget::parseTime) ?: 0L
            item.agentAppToday.text = listOfNotNull(w.todayLine()?.takeIf { it.isNotBlank() }, activityLine(last)).joinToString(" · ")
            item.agentAppHome.text = getString(R.string.library_home, DeviceName.of(app))
            val until = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(w.expiresAt))
            val rules = w.spec.tally?.rules?.size ?: 0
            item.agentAppMeta.text = getString(R.string.agent_apps_by, w.agentName, w.spec.intervalS, until) +
                (if (rules > 0) "\n" + getString(R.string.agent_apps_rules, rules, w.rulesFired) else "")
            item.agentAppAction.setImageResource(R.drawable.ic_close)
            item.agentAppAction.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(item.agentAppAction, com.google.android.material.R.attr.colorError))
            item.agentAppAction.contentDescription = getString(R.string.applets_stop_named, w.spec.label)
            item.agentAppAction.tooltipText = getString(R.string.agent_apps_stop)
            val stop = View.OnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.agent_apps_stop_title, w.spec.label))
                    .setMessage(getString(R.string.agent_apps_stop_body, w.agentName))
                    .setNegativeButton(R.string.agent_apps_stop_cancel, null)
                    .setPositiveButton(R.string.agent_apps_stop) { _, _ -> app.watches.stopByOwner(w.id); showAgentApps() }
                    .show()
            }
            item.agentAppAction.setOnClickListener(stop)
            item.root.setOnClickListener(stop)
        }
        updateEmpty()
    }

    private fun activityLine(at: Long): String = if (at <= 0L) getString(R.string.library_no_activity)
        else getString(R.string.library_last_activity, DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))

    override fun onDestroyView() { super.onDestroyView(); _b = null; entries = emptyList(); pageJob = null }
}
