package cc.bridgit.app.ui.interactions

import cc.bridgit.app.ui.common.themeColor

import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentInteractionsBinding
import cc.bridgit.app.ui.applets.AppletCardsFragment
import cc.bridgit.app.ui.applets.AppletConfigFragment
import cc.bridgit.app.ui.applets.AppletPortalsFragment
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.ui.feedback.decodeFileThumb
import cc.bridgit.app.ui.feedback.handleFeedbackMenu

/**
 * Interactions tab (D26, UI-15): every question and notice from the agents, open ones first, then newest.
 * Rows expand or collapse to one line; open ones can be answered inline and closed (button or swipe left).
 * Owner, 30 Oct 2026: two subtabs under the toolbar, Inbox (this list) and Applets.
 * Owner, 4 Nov 2026: three tabs. Portals ([AppletPortalsFragment]: web views into applets), Config
 * ([AppletConfigFragment]: installed applets, then available ones) and Cards: this list (Inbox) or the card
 * library ([AppletCardsFragment], the sample cards until preloaded ones arrive). Portals and Config are child
 * fragments hidden while another tab shows, so an open portal keeps its page.
 */
class InteractionsFragment : Fragment() {
    private var _b: FragmentInteractionsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    private val drafts = Drafts()
    private val thumbs = LruCache<String, Bitmap>(24)
    private var all: List<Interaction> = emptyList()
    private var adapter: InteractionsAdapter? = null
    private var restoredOverrides: Map<Long, Boolean> = emptyMap()
    private var tab = -1
    private var library: Boolean? = null
    /** The inbox filter chip to return to when the owner leaves Library. */
    private var lastFilter = R.id.chip_all

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { s ->
            val ids = s.getLongArray(KEY_IDS) ?: return@let
            val open = s.getBooleanArray(KEY_OPEN) ?: return@let
            restoredOverrides = ids.indices.associate { ids[it] to open[it] }
        }
        tab = savedInstanceState?.getInt(KEY_TAB) ?: when {
            arguments?.getBoolean("showPortals") == true -> TAB_PORTALS
            arguments?.getBoolean("showApplets") == true -> TAB_CONFIG
            arguments?.getBoolean("showCards") == true -> TAB_CARDS
            else -> -1
        }
        if (savedInstanceState?.containsKey(KEY_LIBRARY) == true) library = savedInstanceState.getBoolean(KEY_LIBRARY)
        else if (arguments?.getBoolean("showCards") == true) library = false // "See questions" means the inbox
        // One-time Home shortcuts; later tab restores keep the owner's choice.
        arguments?.remove("showApplets"); arguments?.remove("showCards"); arguments?.remove("showPortals")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentInteractionsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.headerRow) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        b.toolbar.inflateMenu(R.menu.applets_portal_menu) // Account circle, on the Portals tab
        b.toolbar.menu.findItem(R.id.portal_menu_account).actionView?.setOnClickListener { anchor ->
            portalsTab()?.accountMenu(anchor)
        }
        b.toolbar.inflateMenu(R.menu.applets_cards_menu) // Files / Shared, on the Cards tab
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.cards_menu_files -> { ownerTools("files"); true }
                R.id.cards_menu_shared -> { ownerTools("shares"); true }
                else -> handleFeedbackMenu(it, "Home › " + when (tab) {
                    TAB_PORTALS -> "Portals"; TAB_CONFIG -> "Applets"; else -> if (library == true) "Cards › Library" else "Cards › Inbox" })
            }
        }
        if (tab !in 0..2) tab = when {
            AppletPortalsFragment.available(requireContext()) -> TAB_PORTALS
            else -> TAB_CONFIG
        }
        b.tabs.getTabAt(tab)?.select()
        showTab()
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; showTab() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        val a = InteractionsAdapter(
            drafts = drafts,
            onOpen = ::open,
            onExpanded = ::markSeenIfNotice,
            onAnswer = ::answer,
            onClose = ::close,
            onLink = { c, anchor -> InteractionUi.openLink(anchor, c) },
            loadThumb = ::loadThumb,
        )
        a.overrides.putAll(restoredOverrides)
        adapter = a
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = a
        b.list.addItemDecoration(SectionPanelDecoration(requireContext(), a))
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // Swipe left to close (D26). Only rows still waiting (open, or expired unanswered questions) can be swiped.
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= a.itemCount) return 0
                val i = a.interactionAt(pos) ?: return 0 // section labels don't swipe
                return if (InteractionUi.waiting(i)) ItemTouchHelper.LEFT else 0
            }
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val i = a.interactionAt(pos) ?: return
                a.notifyItemChanged(pos) // bring the row back; it stays in the list as Closed
                close(i)
            }
        }).attachToRecyclerView(b.list)

        b.chips.setOnCheckedStateChangeListener { _, ids -> lastFilter = ids.firstOrNull() ?: R.id.chip_all; render() }
        b.cardsMode.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { val lib = id == R.id.cards_mode_library; if (library != lib) { library = lib; showCardsMode() } }
        }
        b.emptyAction.setOnClickListener {
            if (all.isEmpty()) { library = true; showCardsMode() } else b.chips.check(R.id.chip_all)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var first = true
                app.interactions.changed.collectLatest {
                    if (!first) delay(300) // debounce bursts (an answer bumps more than once)
                    first = false
                    all = withContext(Dispatchers.IO) { app.interactions.listAll() }
                    if (library == null) { library = false; if (tab == TAB_CARDS) showCardsMode() } // inbox first, even when empty
                    render()
                }
            }
        }
    }

    private fun showTab() {
        val bb = _b ?: return
        bb.inbox.isVisible = tab == TAB_CARDS
        bb.tabHost.isVisible = tab != TAB_CARDS
        val fm = childFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        for ((i, tag) in listOf(TAB_PORTALS to TAG_PORTALS, TAB_CONFIG to TAG_CONFIG)) {
            val f = fm.findFragmentByTag(tag)
            when {
                i == tab && f == null -> tx.add(R.id.tab_host, if (i == TAB_PORTALS) AppletPortalsFragment() else AppletConfigFragment(), tag)
                i == tab -> tx.show(f!!)
                f != null -> tx.hide(f)
            }
        }
        tx.commitNowAllowingStateLoss()
        if (tab == TAB_CARDS) showCardsMode()
        portalMenuChanged()
    }

    private fun ownerTools(kind: String) =
        startActivity(android.content.Intent(requireContext(), cc.bridgit.app.planned.OwnerToolsActivity::class.java).putExtra("filter", kind))

    /** Portals empty state: switch to applet installation and configuration. */
    fun showApplets() {
        b.tabs.getTabAt(TAB_CONFIG)?.select()
    }

    fun showPortal(id: String) {
        AppletPortalsFragment.wanted = id
        b.tabs.getTabAt(TAB_PORTALS)?.select()
        portalsTab()?.refresh()
    }

    private fun portalsTab() = childFragmentManager.findFragmentByTag(TAG_PORTALS) as? AppletPortalsFragment

    /** The profile occupies the top-right toolbar only while a portal is open. */
    fun portalMenuChanged() {
        val menu = _b?.toolbar?.menu ?: return
        val on = tab == TAB_PORTALS && portalsTab()?.hasPortal() == true
        menu.findItem(R.id.portal_menu_account)?.apply {
            val letter = portalsTab()?.accountLetter
            isVisible = on && letter != null
            actionView?.findViewById<android.widget.TextView>(R.id.portal_account)?.text = letter.orEmpty()
        }
        menu.findItem(R.id.cards_menu_files)?.isVisible = tab == TAB_CARDS
        menu.findItem(R.id.cards_menu_shared)?.isVisible = tab == TAB_CARDS
    }

    /** Cards shows the inbox (the default, even when empty) or the library; the filter row belongs to the inbox. */
    private fun showCardsMode() {
        val bb = _b ?: return
        val lib = library ?: return
        val mode = if (lib) R.id.cards_mode_library else R.id.cards_mode_inbox
        if (bb.cardsMode.checkedButtonId != mode) bb.cardsMode.check(mode)
        bb.filterRow.isVisible = !lib
        bb.inboxBody.isVisible = !lib
        bb.library.isVisible = lib
        if (lib && childFragmentManager.findFragmentByTag(TAG_LIBRARY) == null)
            childFragmentManager.beginTransaction().setReorderingAllowed(true)
                .add(R.id.library, AppletCardsFragment(), TAG_LIBRARY).commitNowAllowingStateLoss()
    }

    /**
     * #0005: a stack of cards is one row. Its lead is the first card still waiting (in stack order), else the first
     * card; it opens the stack there. Single interactions pass through.
     */
    private fun grouped(list: List<Interaction>): List<Interaction> {
        val leads = list.filter { it.stack != null }.groupBy { it.agentId to it.stack }.mapValues { (_, cards) ->
            val ordered = cards.sortedWith(compareBy({ it.stackIndex }, { it.id }))
            (ordered.firstOrNull { InteractionUi.waiting(it) } ?: ordered.first()).id
        }
        return list.filter { it.stack == null || leads[it.agentId to it.stack] == it.id }
    }

    private fun render() {
        val bb = _b ?: return
        val rows = grouped(all)
        val shown = when (lastFilter) {
            R.id.chip_questions -> rows.filter { InteractionUi.isQuestion(it) }
            R.id.chip_notices -> rows.filter { !InteractionUi.isQuestion(it) }
            R.id.chip_open -> rows.filter { InteractionUi.waiting(it) }
            else -> rows
        }
        adapter?.submitList(InteractionRow.of(shown))
        bb.empty.isVisible = shown.isEmpty()
        bb.list.isVisible = shown.isNotEmpty()
        if (shown.isEmpty()) {
            // Truly empty: "No cards yet" + Browse library. Empty only because of a filter: say so + Clear filter.
            val chip = lastFilter
            val none = all.isEmpty() || chip == R.id.chip_all || chip == View.NO_ID
            bb.emptyTitle.isVisible = true
            bb.emptyTitle.text = getString(if (none) R.string.interactions_empty_none_title else R.string.interactions_empty_filtered_title)
            bb.emptyBody.text = when {
                none -> getString(R.string.interactions_empty_none_body)
                chip == R.id.chip_questions -> getString(R.string.interactions_empty_questions)
                chip == R.id.chip_notices -> getString(R.string.interactions_empty_notices)
                else -> getString(R.string.interactions_empty_open)
            }
            bb.emptyAction.setText(if (none) R.string.interactions_browse_library else R.string.interactions_clear_filter)
        }
    }

    private fun open(i: Interaction) {
        markSeenIfNotice(i) // rows no longer expand, so opening is when a notice is seen
        // D26: a text interaction opens (or reopens) the live text panel instead of a detail screen.
        if (i.kind == IKind.TEXT) { (activity as? cc.bridgit.app.ui.MainActivity)?.showTextPanel(i.id); return }
        findNavController().navigate(R.id.interactionDetail, bundleOf("interactionId" to i.id))
    }

    /** Expanding or opening a notice that is still new marks it seen (the agent gets interaction.seen). */
    private fun markSeenIfNotice(i: Interaction) {
        if (InteractionUi.isQuestion(i) || i.status != IStatus.PENDING) return
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { store.markSeen(i.id) } } }
    }

    private fun answer(i: Interaction, button: String?, values: Map<String, String>) {
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.answer(i.id, button, values) } }
            val bb = _b ?: return@launch
            r.onSuccess {
                drafts.clear(i.id)
                adapter?.overrides?.put(i.id, true) // keep "You answered ..." on screen after the refresh
                Snackbar.make(bb.root, getString(R.string.interactions_sent_snack, InteractionUi.agentName(i)), Snackbar.LENGTH_SHORT).show()
            }.onFailure { e ->
                Snackbar.make(bb.root, getString(R.string.interactions_answer_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun close(i: Interaction) {
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            // #0005: closing a stack row closes every card in it that still waits.
            withContext(Dispatchers.IO) { runCatching { if (InteractionUi.isStack(i)) store.dismissStack(i) else store.dismiss(i.id) } }
            drafts.clear(i.id)
            val bb = _b ?: return@launch
            Snackbar.make(bb.root, getString(R.string.interactions_closed_snack, InteractionUi.agentName(i)), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun loadThumb(i: Interaction, into: ImageView) {
        val name = i.image ?: return
        into.tag = name
        thumbs.get(name)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        val store = app.interactions
        val edge = (240 * resources.displayMetrics.density).toInt()
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { store.imageFile(name)?.let { runCatching { decodeFileThumb(it.path, edge) }.getOrNull() } }
            if (bmp != null) thumbs.put(name, bmp)
            if (into.tag == name) {
                if (bmp != null) into.setImageBitmap(bmp) else into.isVisible = false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val o = adapter?.overrides ?: restoredOverrides
        outState.putLongArray(KEY_IDS, o.keys.toLongArray())
        outState.putBooleanArray(KEY_OPEN, o.keys.map { o[it] == true }.toBooleanArray())
        outState.putInt(KEY_TAB, tab)
        library?.let { outState.putBoolean(KEY_LIBRARY, it) }
    }

    override fun onDestroyView() {
        adapter?.let { restoredOverrides = HashMap(it.overrides) }
        adapter = null
        super.onDestroyView()
        _b = null
    }

    private companion object {
        const val KEY_IDS = "interactions.expanded.ids"
        const val KEY_OPEN = "interactions.expanded.open"
        const val KEY_TAB = "interactions.tab"
        const val KEY_LIBRARY = "interactions.cards.library"
        const val TAB_PORTALS = 0
        const val TAB_CONFIG = 1
        const val TAB_CARDS = 2
        const val TAG_PORTALS = "applets_portals"
        const val TAG_CONFIG = "applets_config"
        const val TAG_LIBRARY = "applets_library"
    }
}
