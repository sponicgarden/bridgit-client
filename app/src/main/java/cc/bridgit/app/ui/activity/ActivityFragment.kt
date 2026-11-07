package cc.bridgit.app.ui.activity

import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.capability.LogModule
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.databinding.FragmentActivityBinding
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.LogRowAdapter
import cc.bridgit.app.feedback.ReportKind
import cc.bridgit.app.ui.feedback.handleFeedbackMenu
import cc.bridgit.app.ui.feedback.openReport
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity (UI-17, ui-spec 5.7): the log, readable by a person. Search, Agent / Type /
 * Errors-only filters, a Live toggle, day-grouped rows, entry sheet, export and clear.
 * The overflow switches to the diagnostic channel ("Diagnostics").
 *
 * Opened with argument "agentId" (Agent detail's "See all"), it starts filtered to that agent.
 */
class ActivityFragment : Fragment() {
    private var _b: FragmentActivityBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    // Filter state. Plain fields, saved in onSaveInstanceState.
    private var q = ""
    private var agentFilter: String? = null       // null = all agents, "" = Bridgit (system), else an agent id
    private val types = linkedSetOf<String>()     // keys of TYPES
    private var errorsOnly = false
    private var diagnostics = false
    private var minLevel: String? = null          // diagnostics mode only
    private var live = LIVE_DEFAULT

    private var adapter: LogRowAdapter? = null
    private var footer: LoadingFooter? = null
    private var liveBusy = false
    private var liveAgain = false
    private var decoration: DayHeaderDecoration? = null
    private var generation = 0
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var hasMore = false
    private var loadingMore = false
    private var loaded = false
    /** Highest log id the list has checked for Live, so a new entry is fetched once. */
    private var topId = 0L
    private var pendingNew = 0
    private var lastAnnounce = 0L
    private var pulse: ValueAnimator? = null
    private var rendering = false

    private var exportQuery: LogQuery? = null
    private val exportJsonl = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        uri?.let { writeExport(it, "jsonl") }
    }
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { writeExport(it, "csv") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = savedInstanceState
        if (s != null) {
            q = s.getString(K_Q, "")
            agentFilter = s.getString(K_AGENT)
            types.clear(); s.getStringArray(K_TYPES)?.let { types.addAll(it) }
            errorsOnly = s.getBoolean(K_ERRORS)
            diagnostics = s.getBoolean(K_DIAG)
            minLevel = s.getString(K_LEVEL)
            live = s.getBoolean(K_LIVE, LIVE_DEFAULT)
        } else {
            arguments?.getString("agentId")?.let { agentFilter = it }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(K_Q, q); out.putString(K_AGENT, agentFilter); out.putStringArray(K_TYPES, types.toTypedArray())
        out.putBoolean(K_ERRORS, errorsOnly); out.putBoolean(K_DIAG, diagnostics); out.putString(K_LEVEL, minLevel)
        out.putBoolean(K_LIVE, live)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentActivityBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        // D26: Activity is no longer a tab (opened from More, Home, Agent detail), so it has a back arrow.
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        // No bottom bar any more, so the list pads for the system navigation bar (edge-to-edge).
        val listPad = b.list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.list) { v, insets ->
            v.updatePadding(bottom = listPad + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom); insets
        }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.activity_export -> ExportSheet().show(childFragmentManager, "export")
                R.id.activity_clear -> confirmClear()
                R.id.activity_diagnostics -> setDiagnostics(true)
                R.id.activity_back -> setDiagnostics(false)
                else -> return@setOnMenuItemClickListener handleFeedbackMenu(item, "Activity") { feedbackContext() }
            }
            true
        }
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)

        val rows = LogRowAdapter(app.agents) { e ->
            if (childFragmentManager.findFragmentByTag("entry") == null) EntrySheet.newInstance(e.id).show(childFragmentManager, "entry")
        }
        adapter = rows
        val lm = LinearLayoutManager(requireContext())
        b.list.layoutManager = lm
        val foot = LoadingFooter()
        footer = foot
        b.list.adapter = ConcatAdapter(rows, foot)
        b.list.itemAnimator?.addDuration = 150
        val deco = DayHeaderDecoration(b.list) { pos -> rows.currentList.getOrNull(pos)?.ts }
        decoration = deco
        b.list.addItemDecoration(deco)
        // TalkBack can't see the drawn day headers, so the first row of each day says its day.
        b.list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(v: View) { dayPrefix(v) }
            override fun onChildViewDetachedFromWindow(v: View) {}
        })
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (lm.findFirstVisibleItemPosition() <= 0) hidePill()
                if (hasMore && !loadingMore && lm.findLastVisibleItemPosition() >= rows.itemCount - LOAD_AHEAD)
                    rv.post { loadMore() }
            }
        })
        b.newPill.setOnClickListener { hidePill(); b.list.smoothScrollToPosition(0) }

        b.search.setText(q)
        b.search.doAfterTextChanged { t ->
            val text = t?.toString() ?: ""
            if (text == q) return@doAfterTextChanged
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                q = text
                reload()
            }
        }

        b.chipAgent.closeIconContentDescription = getString(R.string.activity_choose_agent)
        b.chipType.closeIconContentDescription = getString(R.string.activity_choose_type)
        b.chipLevel.closeIconContentDescription = getString(R.string.activity_choose_level)
        b.chipAgent.setOnClickListener { renderChips(); showAgentMenu() }
        b.chipAgent.setOnCloseIconClickListener { showAgentMenu() }
        b.chipType.setOnClickListener { renderChips(); showTypeDialog() }
        b.chipType.setOnCloseIconClickListener { showTypeDialog() }
        b.chipLevel.setOnClickListener { renderChips(); showLevelMenu() }
        b.chipLevel.setOnCloseIconClickListener { showLevelMenu() }
        b.chipErrors.setOnCheckedChangeListener { _, on ->
            if (rendering) return@setOnCheckedChangeListener
            errorsOnly = on; reload()
        }
        b.chipLive.chipIconTint = null
        b.chipLive.setOnCheckedChangeListener { _, on ->
            if (rendering) return@setOnCheckedChangeListener
            live = on
            updatePulse()
            ViewCompat.setStateDescription(b.chipLive, getString(if (on) R.string.activity_live_on else R.string.activity_live_off))
            if (on) checkNew(app.log.latestId.value)
        }
        b.clearFilters.setOnClickListener { clearFilters() }

        renderChips()
        renderToolbar()
        reload()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updatePulse()
                launch { app.log.latestId.collect { delay(400); loadToday() } }
                app.log.latestId.collect { id -> if (live && loaded) checkNew(id) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        pulse?.cancel(); pulse = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulse?.cancel(); pulse = null
        adapter = null; decoration = null; footer = null
        liveBusy = false; liveAgain = false
        loaded = false
        _b = null
    }

    // ---- Query ----

    /** The current filters as a LogQuery (no paging). Export and the count use this too. */
    fun baseQuery(): LogQuery {
        val text = q.trim().takeIf { it.isNotEmpty() }?.take(200)
        if (diagnostics) return LogQuery(channels = listOf("diagnostic"), minLevel = minLevel, q = text)
        val kinds = types.flatMap { key -> TYPES.first { it.key == key }.kinds }
        // Normal mode shows the audit and agent channels; Diagnostics in the Type menu adds the
        // diagnostic channel so service/crash entries can show up next to the rest.
        val channels = if ("diagnostics" in types) emptyList() else listOf("audit", "agent")
        return LogQuery(channels = channels, agentIds = agentFilter?.let { listOf(it) }, kinds = kinds,
            result = if (errorsOnly) "error" else null, q = text)
    }

    private fun filtersActive(): Boolean = q.isNotBlank() ||
        (if (diagnostics) minLevel != null else agentFilter != null || types.isNotEmpty() || errorsOnly)

    private fun reload() {
        val rows = adapter ?: return
        loadJob?.cancel()
        val gen = ++generation
        hasMore = false; loadingMore = true; loaded = false
        pendingNew = 0; hidePill()
        footer?.showing = false
        b.loading.visibility = View.VISIBLE
        val query = baseQuery()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val (latest, page) = withContext(Dispatchers.IO) {
                val l = app.log.latestId.value
                l to app.log.query(query.copy(limit = PAGE))
            }
            if (gen != generation || _b == null) return@launch
            topId = maxOf(latest, page.firstOrNull()?.id ?: 0L)
            loadingMore = false; hasMore = page.size >= PAGE; loaded = true
            b.loading.visibility = View.INVISIBLE
            rows.submitList(page) { afterSubmit(); _b?.list?.scrollToPosition(0) }
            updateEmpty(page.isEmpty())
            if (live) checkNew(app.log.latestId.value)
        }
    }

    private fun loadMore() {
        val rows = adapter ?: return
        if (loadingMore || !hasMore) return
        val last = rows.currentList.lastOrNull() ?: return
        loadingMore = true
        footer?.showing = true
        val gen = generation
        val query = baseQuery().copy(limit = PAGE, cursor = last.id)
        viewLifecycleOwner.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { app.log.query(query) }
            if (gen != generation || _b == null) return@launch
            loadingMore = false; hasMore = page.size >= PAGE
            footer?.showing = false
            rows.submitList(rows.currentList + page) { afterSubmit() }
        }
    }

    /** Live (LOG-07 in the app): fetches entries newer than [topId] that match the filters and prepends them. */
    private fun checkNew(latest: Long) {
        val rows = adapter ?: return
        if (!loaded || latest <= topId) return
        if (liveBusy) { liveAgain = true; return }
        liveBusy = true
        val gen = generation
        val since = topId
        val query = baseQuery().copy(sinceId = since, ascending = true, limit = LogQuery.MAX_LIMIT, cursor = null)
        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = try { withContext(Dispatchers.IO) { app.log.query(query) } } finally {
                liveBusy = false
                if (liveAgain) { liveAgain = false; view?.post { if (live && _b != null) checkNew(app.log.latestId.value) } }
            }
            if (gen != generation || _b == null || topId != since) return@launch
            if (fresh.size >= LogQuery.MAX_LIMIT) {
                // More than one batch arrived: take the rest on the next round.
                topId = fresh.last().id
                liveAgain = true
                view?.post { if (live && _b != null && liveAgain) { liveAgain = false; checkNew(app.log.latestId.value) } }
            } else topId = maxOf(latest, fresh.lastOrNull()?.id ?: 0L)
            if (fresh.isEmpty()) return@launch
            val known = rows.currentList.firstOrNull()?.id ?: 0L
            val add = fresh.filter { it.id > known }.asReversed()
            if (add.isEmpty()) return@launch
            val lm = b.list.layoutManager as LinearLayoutManager
            val atTop = lm.findFirstVisibleItemPosition() <= 0
            // Keep the existing instances so DiffUtil only inserts (JSONObject has identity equals).
            rows.submitList(add + rows.currentList) { afterSubmit(); if (atTop) _b?.list?.scrollToPosition(0) }
            updateEmpty(false)
            if (!atTop) {
                pendingNew += add.size
                b.newPill.text = resources.getQuantityString(R.plurals.activity_new_entries, pendingNew, pendingNew)
                b.newPill.isVisible = true
            }
            val now = System.currentTimeMillis()
            if (now - lastAnnounce >= ANNOUNCE_EVERY_MS) {
                lastAnnounce = now
                b.list.announceForAccessibility(resources.getQuantityString(R.plurals.activity_new_entries, add.size, add.size))
            }
        }
    }

    /** First row of a day says its day to TalkBack ("Today. 14:02, Grok fetched …"); other rows don't. */
    private fun dayPrefix(v: View) {
        val bb = _b ?: return
        val rows = adapter ?: return
        val deco = decoration ?: return
        val pos = bb.list.getChildAdapterPosition(v)
        val e = rows.currentList.getOrNull(pos) ?: return
        val cd = v.contentDescription?.toString() ?: return
        val prefix = Fmt.day(e.ts) + ". "
        val base = cd.removePrefix(prefix)
        v.contentDescription = if (deco.isFirstOfDay(pos)) prefix + base else base
    }

    /** After the list changes: day-header space and the TalkBack day prefix follow the new first rows. */
    private fun afterSubmit() {
        val list = _b?.list ?: return
        list.invalidateItemDecorations()
        for (i in 0 until list.childCount) dayPrefix(list.getChildAt(i))
    }

    private fun hidePill() {
        pendingNew = 0
        _b?.newPill?.isVisible = false
    }

    private fun updateEmpty(empty: Boolean) {
        val bb = _b ?: return
        bb.empty.isVisible = empty
        if (!empty) return
        if (filtersActive()) {
            bb.emptyTitle.setText(R.string.activity_nomatch_title)
            bb.emptyBody.isVisible = false
            bb.clearFilters.isVisible = true
        } else {
            bb.emptyTitle.setText(R.string.activity_empty_title)
            bb.emptyBody.setText(R.string.activity_empty_body)
            bb.emptyBody.isVisible = true
            bb.clearFilters.isVisible = false
        }
    }

    // ---- Filters ----

    private fun clearFilters() {
        q = ""; agentFilter = null; types.clear(); errorsOnly = false; minLevel = null
        searchJob?.cancel()
        b.search.setText("")
        renderChips()
        reload()
    }

    /** Called by the entry sheet's "<Agent>'s activity". [agentId] "" = Bridgit's own entries. */
    /** FBK-06 screen_context: the filters in use, in words ("Filters: Grok, Requests, errors only"). */
    private fun feedbackContext(): String {
        val parts = if (diagnostics) listOfNotNull(
            getString(R.string.activity_subtitle_diagnostics),
            minLevel?.let { l -> LEVELS.firstOrNull { it.first == l }?.let { getString(it.second) } },
        ) else listOfNotNull(
            agentLabel(agentFilter),
            *types.mapNotNull { k -> TYPES.firstOrNull { it.key == k }?.let { getString(it.label) } }.toTypedArray(),
            if (errorsOnly) "errors only" else null,
        )
        val search = q.trim().takeIf { it.isNotEmpty() }?.let { "search \"${it.take(100)}\"" }
        val all = parts + listOfNotNull(search)
        return if (all.isEmpty()) "Filters: none" else "Filters: " + all.joinToString(", ")
    }

    /** "Report a bug about this" from the entry sheet: attaches that entry (FBK-01). */
    fun reportAbout(entryId: Long) = openReport(this, ReportKind.BUG, "Activity", feedbackContext(), entryId)

    fun filterToAgent(agentId: String) {
        if (_b == null) return
        diagnostics = false; minLevel = null
        agentFilter = agentId
        renderToolbar(); renderChips(); reload()
    }

    private fun setDiagnostics(on: Boolean) {
        diagnostics = on
        renderToolbar(); renderChips(); reload(); loadToday()
    }

    /** "Last 24 hours" (moved from Home): a rolling 24 hours of agent requests, not since midnight. */
    private fun loadToday() {
        viewLifecycleOwner.lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching {
                    val base = LogQuery(channels = listOf("audit"), kinds = listOf("request"), sinceTs = System.currentTimeMillis() - 24L * 60 * 60 * 1000)
                    listOf(app.log.count(base),
                        app.log.count(base.copy(endpointPrefix = "/v1/camera/snapshot", result = "ok")),
                        app.log.count(base.copy(endpointPrefix = "/v1/mic/record", result = "ok")),
                        app.log.count(base.copy(result = "error")))
                }.getOrNull()
            }
            val bb = _b ?: return@launch
            bb.today.isVisible = counts != null && !diagnostics
            if (counts == null) return@launch
            val sb = android.text.SpannableStringBuilder()
            sb.append(getString(R.string.activity_today), android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            fun item(id: Int, n: Int) = resources.getQuantityString(id, n, n).replace(' ', '\u00A0')
            sb.append(" · ").append(item(R.plurals.home_requests, counts[0]))
            sb.append(" · ").append(item(R.plurals.home_photos, counts[1]))
            sb.append(" · ").append(item(R.plurals.home_clips, counts[2]))
            sb.append(" · ")
            val err = item(R.plurals.home_errors, counts[3])
            if (counts[3] > 0) sb.append(err, android.text.style.ForegroundColorSpan(requireContext().themeColor(com.google.android.material.R.attr.colorError)), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else sb.append(err)
            bb.today.text = sb
        }
    }

    private fun renderToolbar() {
        val bb = _b ?: return
        bb.toolbar.subtitle = if (diagnostics) getString(R.string.activity_subtitle_diagnostics) else null
        val m: Menu = bb.toolbar.menu
        m.findItem(R.id.activity_diagnostics)?.isVisible = !diagnostics
        m.findItem(R.id.activity_back)?.isVisible = diagnostics
    }

    private fun agentLabel(id: String?): String? = when (id) {
        null -> null
        "" -> getString(R.string.activity_bridgit_system)
        else -> app.agents.get(id)?.name
    }

    private fun renderChips() {
        val bb = _b ?: return
        rendering = true
        bb.chipAgent.isVisible = !diagnostics
        bb.chipType.isVisible = !diagnostics
        bb.chipErrors.isVisible = !diagnostics
        bb.chipLevel.isVisible = diagnostics

        bb.chipAgent.text = agentLabel(agentFilter) ?: getString(R.string.activity_chip_agent)
        bb.chipAgent.isChecked = agentFilter != null
        bb.chipType.text = when (types.size) {
            0 -> getString(R.string.activity_chip_type)
            1 -> getString(TYPES.first { it.key == types.first() }.label)
            else -> getString(R.string.activity_chip_types, types.size)
        }
        bb.chipType.isChecked = types.isNotEmpty()
        bb.chipErrors.isChecked = errorsOnly
        bb.chipLevel.text = LEVELS.firstOrNull { it.first == minLevel }?.takeIf { minLevel != null }?.let { getString(it.second) }
            ?: getString(R.string.activity_chip_level)
        bb.chipLevel.isChecked = minLevel != null
        bb.chipLive.isChecked = live
        ViewCompat.setStateDescription(bb.chipLive, getString(if (live) R.string.activity_live_on else R.string.activity_live_off))
        rendering = false
    }

    private fun showAgentMenu() {
        val pm = androidx.appcompat.widget.PopupMenu(requireContext(), b.chipAgent)
        val agents = app.agents.all()
        pm.menu.add(0, 0, 0, R.string.activity_all_agents)
        agents.forEachIndexed { i, a -> pm.menu.add(0, i + 1, i + 1, a.name) }
        pm.menu.add(0, agents.size + 1, agents.size + 1, R.string.activity_bridgit_system)
        pm.menu.setGroupCheckable(0, true, true)
        val checkedId = when (val f = agentFilter) {
            null -> 0
            "" -> agents.size + 1
            else -> agents.indexOfFirst { it.id == f }.let { if (it < 0) -1 else it + 1 }
        }
        pm.menu.findItem(checkedId)?.isChecked = true
        pm.setOnMenuItemClickListener { item ->
            agentFilter = when (item.itemId) {
                0 -> null
                agents.size + 1 -> ""
                else -> agents[item.itemId - 1].id
            }
            renderChips(); reload(); true
        }
        pm.show()
    }

    private fun showTypeDialog() {
        val labels = TYPES.map { getString(it.label) }.toTypedArray()
        val checked = TYPES.map { it.key in types }.toBooleanArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.activity_choose_type)
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.activity_type_done) { _, _ ->
                types.clear()
                TYPES.forEachIndexed { i, t -> if (checked[i]) types += t.key }
                renderChips(); reload()
            }
            .setNeutralButton(R.string.activity_type_clear) { _, _ -> types.clear(); renderChips(); reload() }
            .setOnCancelListener { renderChips() }
            .show()
    }

    private fun showLevelMenu() {
        val pm = androidx.appcompat.widget.PopupMenu(requireContext(), b.chipLevel)
        LEVELS.forEachIndexed { i, (_, label) -> pm.menu.add(0, i, i, label) }
        pm.menu.setGroupCheckable(0, true, true)
        pm.menu.findItem(LEVELS.indexOfFirst { it.first == minLevel }.coerceAtLeast(0))?.isChecked = true
        pm.setOnMenuItemClickListener { item ->
            minLevel = LEVELS[item.itemId].first
            renderChips(); reload(); true
        }
        pm.show()
    }

    /** Live chip dot pulses (ui-spec 2.5) unless animations are off. */
    private fun updatePulse() {
        val bb = _b ?: return
        pulse?.cancel(); pulse = null
        val icon = bb.chipLive.chipIcon ?: return
        icon.alpha = 255
        if (!live || !ValueAnimator.areAnimatorsEnabled()) { bb.chipLive.invalidate(); return }
        pulse = ValueAnimator.ofInt(255, 90).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a -> icon.alpha = a.animatedValue as Int; _b?.chipLive?.invalidate() }
            start()
        }
    }

    // ---- Clear ----

    private fun confirmClear() {
        viewLifecycleOwner.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { app.log.count(LogQuery(channels = listOf("audit", "agent"))) }
            if (_b == null) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.activity_clear_title)
                .setMessage(getString(R.string.activity_clear_body, NumberFormat.getIntegerInstance().format(n)))
                .setPositiveButton(R.string.activity_clear_confirm) { _, _ -> clearLog() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun clearLog() {
        val log = app.log
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { log.clear("You"); log.flush() } // LOG-05
            if (_b == null) return@launch
            reload()
            Snackbar.make(b.root, R.string.activity_cleared, Snackbar.LENGTH_SHORT).show()
        }
    }

    // ---- Export (LOG-10 in the app) ----

    /** Called by the export sheet. */
    fun startExport(format: String) {
        exportQuery = baseQuery()
        val name = "bridgit-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + "." + format
        runCatching { if (format == "csv") exportCsv.launch(name) else exportJsonl.launch(name) }
            .onFailure { _b?.let { Snackbar.make(it.root, R.string.activity_export_failed, Snackbar.LENGTH_LONG).show() } }
    }

    private fun writeExport(uri: Uri, format: String) {
        val query = exportQuery ?: baseQuery()
        val a = app
        val resolver = requireContext().applicationContext.contentResolver
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    var count = 0
                    (resolver.openOutputStream(uri) ?: error("no stream")).bufferedWriter().use { w ->
                        if (format == "csv") w.append(LogModule.CSV_HEADER).append('\n')
                        a.log.forEach(query) { e: LogEntry ->
                            val json = e.toJson(::isoAt, e.agentId?.let { a.agents.get(it)?.name } ?: e.agent)
                            w.append(if (format == "csv") LogModule.csv(json) else json.toString()).append('\n')
                            count++
                        }
                    }
                    count
                }.getOrNull()
            }
            if (n != null) a.log.system("audit", "setting", "You exported $n log entries")
            val root = _b?.root ?: return@launch
            if (n == null) Snackbar.make(root, R.string.activity_export_failed, Snackbar.LENGTH_LONG).show()
            else Snackbar.make(root, getString(R.string.activity_export_done, NumberFormat.getIntegerInstance().format(n)),
                Snackbar.LENGTH_SHORT).show()
        }
    }

    /** The small progress row at the end while the next page loads. */
    private class LoadingFooter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var showing = false
            set(v) {
                if (field == v) return
                field = v
                if (v) notifyItemInserted(0) else notifyItemRemoved(0)
            }

        override fun getItemCount() = if (showing) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.activity_row_loading, parent, false)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    private class TypeOption(val key: String, val label: Int, val kinds: List<String>)

    companion object {
        /** Live is on when the screen opens, so new entries appear without a refresh (Live default; spec leaves it open). */
        const val LIVE_DEFAULT = true
        private const val PAGE = 100
        private const val LOAD_AHEAD = 20
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val ANNOUNCE_EVERY_MS = 5_000L

        private val TYPES = listOf(
            TypeOption("requests", R.string.activity_type_requests, listOf("request")),
            TypeOption("settings", R.string.activity_type_settings, listOf("setting", "mode")),
            TypeOption("agents", R.string.activity_type_agents, listOf("agent", "pairing")),
            TypeOption("diagnostics", R.string.activity_type_diagnostics, listOf("service", "crash")),
            TypeOption("notes", R.string.activity_type_notes, listOf("note")),
            // Grants and Interactions join here with their features (P2).
        )
        private val LEVELS = listOf(
            null to R.string.activity_level_all,
            "info" to R.string.activity_level_info,
            "warn" to R.string.activity_level_warn,
            "error" to R.string.activity_level_error,
        )

        private const val K_Q = "q"
        private const val K_AGENT = "agent"
        private const val K_TYPES = "types"
        private const val K_ERRORS = "errors"
        private const val K_DIAG = "diag"
        private const val K_LEVEL = "level"
        private const val K_LIVE = "live"
    }
}
