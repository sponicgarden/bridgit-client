package cc.bridgit.app.ui.addagent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.Pairing
import cc.bridgit.app.R
import java.util.Locale

/**
 * Pair with code (AGT-13): a 6-digit code for 5 minutes. The agent calls
 * POST /v1/pair with it and receives its key once. FLAG_SECURE (AGT-08).
 */
class PairCodeFragment : Fragment() {
    /** Holds the window this screen opened, in memory only, so its state survives a rotation. */
    class PairViewModel : ViewModel() { var window: Pairing.Window? = null; var initialised = false }

    private enum class State { ACTIVE, EXPIRED, REDEEMED, TOO_MANY, NO_KEY }

    private var _b: cc.bridgit.app.databinding.FragmentPairCodeBinding? = null
    private val b get() = _b!!
    private val vm: PairViewModel by viewModels()
    private val app get() = requireActivity().application as BridgeApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = cc.bridgit.app.databinding.FragmentPairCodeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!vm.initialised) { vm.initialised = true; vm.window = app.pairing.window }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        // AGT-08a: TalkBack still reads the code (the owner may need to hear it); other services can't on Android 14+.
        cc.bridgit.app.ui.common.SecretViews.markSensitive(b.code)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { render(); delay(500) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(activity?.window)
    }

    override fun onStop() {
        super.onStop()
        val dest = runCatching { findNavController().currentDestination?.id }.getOrNull()
        if (dest != R.id.addAgent && dest != R.id.pairCode) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDestroy() {
        // Leaving the screen closes the pairing window (only the one this screen opened).
        if (isRemoving && app.pairing.window === vm.window && vm.window != null) app.pairing.close()
        super.onDestroy()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun state(w: Pairing.Window?, now: Long): State = when {
        w == null -> State.NO_KEY
        w.redeemed -> State.REDEEMED
        now > w.expiresAt -> State.EXPIRED
        app.pairing.window !== w -> State.TOO_MANY // redeem() closed it after five wrong codes
        else -> State.ACTIVE
    }

    private var lastState: State? = null

    private fun render() {
        if (_b == null) return
        val w = vm.window
        val now = System.currentTimeMillis()
        val s = state(w, now)
        b.active.isVisible = s == State.ACTIVE
        if (s == State.ACTIVE && w != null) {
            b.code.text = w.code.chunked(3).joinToString(" ")
            b.code.contentDescription = getString(R.string.pair_code_desc, w.code.toList().joinToString(" "))
            val left = ((w.expiresAt - now) / 1000).coerceAtLeast(0)
            b.countdown.setProgressCompat(left.toInt(), false)
            b.expires.text = getString(R.string.pair_expires, String.format(Locale.US, "%d:%02d", left / 60, left % 60))
        }
        if (s == lastState) return
        lastState = s
        b.state.isVisible = s != State.ACTIVE
        b.stateDetail.isVisible = false
        b.action.isVisible = s != State.ACTIVE
        when (s) {
            State.ACTIVE -> {}
            State.REDEEMED -> {
                // The agent has its key: tell Add agent, which treats this as acknowledged (AGT-05a).
                findNavController().previousBackStackEntry?.savedStateHandle?.set(KEY_REDEEMED, true)
                b.state.text = getString(R.string.pair_redeemed, w!!.agentName)
                b.action.text = getString(R.string.addagent_done)
                b.action.setOnClickListener { findNavController().popBackStack() }
            }
            State.EXPIRED, State.TOO_MANY -> {
                b.state.text = getString(if (s == State.EXPIRED) R.string.pair_expired else R.string.pair_too_many)
                b.action.text = getString(R.string.pair_new_code)
                b.action.setOnClickListener { newCode() }
            }
            State.NO_KEY -> {
                b.state.text = getString(R.string.pair_expired)
                b.stateDetail.isVisible = true
                b.stateDetail.text = getString(R.string.pair_rotate_needed)
                b.action.text = getString(R.string.addagent_done)
                b.action.setOnClickListener { findNavController().popBackStack() }
            }
        }
    }

    /** Re-opens the window with the same key while this flow still holds it; otherwise only Rotate helps. */
    private fun newCode() {
        val old = vm.window
        val pending = app.vault.get()?.takeIf { old != null && it.agentId == old.agentId }
        val key = old?.key ?: pending?.key
        vm.window = if (old != null && key != null) app.pairing.open(old.agentId, old.agentName, key) else null
        lastState = null
        render()
    }

    companion object { const val KEY_REDEEMED = "pairRedeemed" }
}
