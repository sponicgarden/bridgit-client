package cc.bridgit.app.ui.applets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cc.bridgit.app.R
import cc.bridgit.app.api.Samples
import cc.bridgit.app.databinding.FragmentAppletCardsBinding
import cc.bridgit.app.databinding.ItemAppletCardBinding

/**
 * Applets › Cards (owner, 4 Nov 2026): rich HTML cards, each one screen ([CardPreviewFragment]). For now these
 * are the sample interactions bundled with the app (the samples asset); preloaded cards for agents come later.
 */
class AppletCardsFragment : Fragment() {
    private var _b: FragmentAppletCardsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAppletCardsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.cardsList.removeAllViews()
        for (c in load(requireContext())) {
            val row = ItemAppletCardBinding.inflate(layoutInflater, b.cardsList, true)
            row.cardTitle.text = c.title
            row.cardSummary.text = c.summary
            row.root.setOnClickListener { findNavController().navigate(R.id.cardPreview, bundleOf("cardId" to c.id)) }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        fun load(context: android.content.Context): List<AppletLists.Card> = runCatching {
            AppletLists.cards(context.assets.open(Samples.ASSET).bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
    }
}
