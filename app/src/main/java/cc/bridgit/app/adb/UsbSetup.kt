package cc.bridgit.app.adb

/**
 * ADB setup by USB cable (8.27, ADB-06): the other way in, for owners with a laptop and a coding agent on it
 * (Claude Code, Codex, Cursor). The owner does the few taps on the phone; the agent on the laptop runs the
 * same grant commands the on-phone wizard runs. Plain Kotlin, so the steps and prompt are unit-tested.
 */
object UsbSetup {
    class Step(val title: String, val how: String)

    val steps = listOf(
        Step("1. Get a USB cable and a laptop",
            "A cable that fits this phone and your laptop, and an AI control agent on the laptop: Claude Code, Codex or Cursor."),
        Step("2. Turn on Developer options",
            "Settings → About phone (Samsung: Software information) → tap Build number 7 times, then enter your PIN."),
        Step("3. Turn on USB debugging",
            "Settings → System → Developer options (Samsung: Settings → Developer options) → USB debugging → on."),
        Step("4. Plug the phone in",
            "Unlock the phone. When it asks \"Allow USB debugging?\", check the computer is yours, then tap Allow. Choose Always allow only for a computer you trust."),
        Step("5. Give your control agent the prompt",
            "Tap Share prompt and send it to your laptop (email it to yourself, or any chat app), then paste it into your control agent and let it run. The rows under Give Bridgit full access turn On as it works."),
    )

    /** The prompt the owner pastes into the agent on their laptop. [commands] are shell commands for `adb shell`. */
    fun prompt(packageName: String, commands: List<String>): String = buildString {
        appendLine("Please set up the Bridgit app on my Android phone over USB. The phone is plugged into this computer,")
        appendLine("and Developer options and USB debugging are on. Bridgit's package name is $packageName.")
        appendLine()
        appendLine("1. Check that adb works: run `adb version`. If it's missing, install Android platform-tools after asking me")
        appendLine("   (macOS: `brew install android-platform-tools`; Windows: `winget install Google.PlatformTools`;")
        appendLine("   Linux: `sudo apt install adb`; or https://developer.android.com/tools/releases/platform-tools).")
        appendLine("2. Run `adb devices`.")
        appendLine("   - \"unauthorized\": ask me to unlock the phone and tap Allow on \"Allow USB debugging?\", then run it again.")
        appendLine("   - Nothing listed: ask me to try another cable or USB port, or to pick File transfer in the phone's USB notification.")
        appendLine("   - More than one device: ask me which one, and add `-s <serial>` to every adb command below.")
        appendLine("3. Check Bridgit is installed: `adb shell pm path $packageName` should print a path. If not, tell me and stop.")
        appendLine("   Use `adb -s <serial>` for every command, even when only one device is connected. Confirm the intended phone and Android user/profile before changing access.")
        appendLine("   Explain that this grants notification, screen, camera, microphone and other declared access; proceed only for the owner's requested full-access setup.")
        appendLine("4. Run each of these, one at a time, as `adb shell \"<command>\"`. Some may fail on older Android versions;")
        appendLine("   note them and carry on.")
        commands.forEach {
            if (it.startsWith("settings put secure enabled_accessibility_services ")) {
                val own = it.removePrefix("settings put secure enabled_accessibility_services ")
                    .trim().trim('\'').split(':').filter { component -> component.substringBefore('/') == packageName }
                appendLine("   Before enabling accessibility, read `settings get secure enabled_accessibility_services` LIVE on the selected phone.")
                appendLine("   Merge the following Bridgit components into that live colon-separated list, deduplicate, and write the merged list back.")
                appendLine("   Treat null as empty. Do not overwrite other services or restore removed services from an old prompt.")
                appendLine("   Add only these Bridgit components: ${own.joinToString(":")}")
                appendLine("   Write with `settings put secure enabled_accessibility_services '<merged-live-list>'`.")
            } else appendLine("   $it")
        }
        appendLine("5. Verify the changes yourself where possible (dumpsys package $packageName, appops get $packageName, and settings get secure enabled_accessibility_services).")
        appendLine("   A zero exit code alone is not proof. If you have Bridgit API access, also read GET /v1/adb/status and GET /v1/capabilities.")
        appendLine("   Report working, still off, unsupported and unverified separately; never call an unreadable grant successful.")
        appendLine("6. Guide me to Bridgit → More → Settings → Max Agentic Control to enable its feature switches and finish remaining prompts.")
        appendLine("   ADB grants do not enable every Bridgit setting or your per-agent authorization. Never grant yourself Agent Super Powers.")
        appendLine("   If Android still blocks access: open App info with `am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName`.")
        appendLine("   Ask me to tap ⋮ → Allow restricted settings, confirm privately, return to Bridgit and retry the blocked access.")
        appendLine("   Give one step at a time and wait. If that menu is absent, report the Android/OEM or device-policy blocker; don't disable security.")
        appendLine("   More → ADB setup → Revoke Super Powers removes special ADB access; ordinary camera/microphone permissions remain until removed in App info. Turning Max Agentic Control off turns off Bridgit switches.")
        appendLine("7. Bridgit reaches my control agents over Tailscale, so check it too.")
        cc.bridgit.app.core.TailscaleHelp.usbPromptSteps().forEach { appendLine("   $it") }
        appendLine()
        appendLine("Only run the commands above on the phone; don't change anything else on it.")
    }
}
