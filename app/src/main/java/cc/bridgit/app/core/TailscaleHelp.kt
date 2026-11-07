package cc.bridgit.app.core

/**
 * Tailscale setup help: the text and shell commands behind the Tailscale setup screen, the
 * "Keep Tailscale on" ADB grant and the USB prompt. Plain Kotlin, so all of it is unit-tested.
 *
 * Signing in to Tailscale creates the owner's private network (a tailnet) by itself; nobody has
 * to "create a network". Every device just has to sign in with the same account.
 */
object TailscaleHelp {
    const val PACKAGE = "com.tailscale.ipn"
    /** Tailscale's exported receiver; it takes CONNECT_VPN and DISCONNECT_VPN from any app (the Tasker integration). */
    const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"
    const val DOWNLOAD_URL = "https://tailscale.com/download"

    /**
     * Shell commands (uid shell, over Bridgit's own ADB or a laptop's) that keep Tailscale on:
     * no VPN prompt, always-on VPN so it comes back after a reboot, and no battery sleep.
     * Always-on VPN without lockdown: if Tailscale stops, the phone keeps its normal internet.
     */
    val keepOnCommands = listOf(
        "appops set $PACKAGE ACTIVATE_VPN allow",
        "settings put secure always_on_vpn_app $PACKAGE",
        "settings put secure always_on_vpn_lockdown 0",
        "dumpsys deviceidle whitelist +$PACKAGE",
        "cmd appops set $PACKAGE RUN_ANY_IN_BACKGROUND allow",
    )

    /** Opens Tailscale's Play Store page from the shell, for the owner to tap Install. */
    const val PLAY_COMMAND = "am start -a android.intent.action.VIEW -d market://details?id=$PACKAGE"
    /** Turns Tailscale on from the shell once it's signed in. */
    const val CONNECT_COMMAND = "am broadcast -a $ACTION_CONNECT -n $PACKAGE/.IPNReceiver"

    /** "tail1234.ts.net" from "my-phone.tail1234.ts.net", or null. */
    fun tailnetName(magicDns: String?): String? = CallbackRules.tailnetDomain(magicDns)

    /**
     * The prompt the owner gives the agent (or pastes on the agent's computer) so that computer joins
     * the same tailnet and checks it can reach the phone. [phoneHost] is the MagicDNS name or Tailscale IP.
     */
    fun computerPrompt(phoneHost: String?, port: Int, tailnet: String?): String = buildString {
        appendLine("Please put this computer on my Tailscale network, so you can reach my Android phone through the Bridgit app.")
        appendLine()
        appendLine("1. Check whether Tailscale is already here: run `tailscale status`.")
        if (phoneHost != null) appendLine("   If it works and lists $phoneHost, skip to step 4.")
        appendLine("2. If it's missing, install it (ask me first):")
        appendLine("   - macOS: `brew install --cask tailscale` (or the app from $DOWNLOAD_URL), then open Tailscale.")
        appendLine("   - Windows: `winget install tailscale.tailscale`.")
        appendLine("   - Linux: `curl -fsSL https://tailscale.com/install.sh | sh`.")
        appendLine("   - A container, sandbox or server where you can't install a VPN: run tailscaled in userspace mode")
        appendLine("     (`tailscaled --tun=userspace-networking`) and tell me, so I pick \"Tailscale, userspace host\" in Bridgit.")
        appendLine("3. Run `tailscale up`. It prints a login link: give it to me and wait. I open it and sign in with the")
        append("   same account my phone uses")
        if (tailnet != null) append(" (my network is $tailnet)")
        appendLine(". Signing in is all it takes; there's no network to create.")
        if (phoneHost != null) {
            appendLine("4. Check the phone answers: `tailscale ping $phoneHost`, then")
            appendLine("   `curl -sS -m 10 http://$phoneHost:$port/v1/health`. A 401 reply is good: it means the phone is reachable.")
        } else {
            appendLine("4. Check the phone shows up in `tailscale status`. Bridgit shows its address under Control agents.")
        }
        appendLine("5. Tell me in plain words whether it worked. If the phone isn't listed, ask me to check that Tailscale")
        appendLine("   is on in the phone's Tailscale app and that both are signed in to the same account.")
    }

    /** Lines for the USB prompt, so a laptop agent also checks Tailscale on the phone. */
    fun usbPromptSteps(): List<String> = listOf(
        "Check Tailscale on the phone: `adb shell pm path $PACKAGE`.",
        "   - Not installed: run `adb shell \"$PLAY_COMMAND\"`, ask me to tap Install, then to open Tailscale and sign in.",
        "   - Installed: run `adb shell \"ip -4 addr\"` and look for a 100.x address. None: ask me to open Tailscale and",
        "     sign in (or tap Connect), then run `adb shell \"$CONNECT_COMMAND\"` and check again.",
        "   - Once it's installed, run each of these as `adb shell \"<command>\"` so it stays on:",
    ) + keepOnCommands.map { "     $it" }
}
