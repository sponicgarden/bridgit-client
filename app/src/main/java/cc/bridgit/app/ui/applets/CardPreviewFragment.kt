package cc.bridgit.app.ui.applets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.json.JSONObject
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentCardPreviewBinding
import cc.bridgit.app.ui.interactions.CardWebView

/**
 * One card from Applets › Cards, full screen (owner, 4 Nov 2026). The page runs in the same locked-down card view
 * agents' cards use. It isn't from an agent: an answer is shown on the card and goes nowhere, and Bridgit.act
 * is refused.
 */
class CardPreviewFragment : Fragment() {
    private var _b: FragmentCardPreviewBinding? = null
    private val b get() = _b!!
    private var card: CardWebView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentCardPreviewBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.cardFrame.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val id = requireArguments().getString("cardId")
        val c = AppletCardsFragment.load(requireContext()).firstOrNull { it.id == id }
        if (c == null) { findNavController().navigateUp(); return }
        b.toolbar.title = c.title
        val v = CardWebView(requireContext(), onRespond = { data -> answered(data) }, onCrash = { findNavController().navigateUp() })
        v.contentDescription = c.title
        b.cardFrame.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.load(c.html)
        card = v
    }

    private fun answered(data: Any) {
        val answer = JSONObject().put("button", JSONObject.NULL).put("values", JSONObject()).put("data", data)
        card?.showConfirmation(answer, getString(R.string.applets_card_answered_title), getString(R.string.applets_card_answered_body))
    }

    override fun onDestroyView() {
        card?.release(); card = null
        super.onDestroyView(); _b = null
    }
}
