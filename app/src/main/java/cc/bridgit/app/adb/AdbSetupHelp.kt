package cc.bridgit.app.adb

/** Shared instructions for the controlling agent, its setup guide and the owner's ADB screen. */
object AdbSetupHelp {
    const val promptHint = "GET /v1/adb/setup explains Max Agentic Control, an optional mode where the phone gives you deeper Android control through ADB. Read it only if the owner asks for it."

    const val agentHint = "Max Agentic Control/restricted settings: GET /v1/adb/setup and /v1/adb/status. Owner-authorized ADB + Super Powers: POST /v1/adb/grant {}; verify grants and capabilities, then guide remaining prompts."

    const val ownerHelp = "Already set up ADB? Tap Give Bridgit full access below, then enable Max Agentic Control in More → Settings to finish the feature switches and any remaining Android prompts. Only do this for a control agent you trust: it can read notifications, see and control the screen, and use the camera and microphone. If Android still says Restricted setting, open Bridgit’s App info, tap ⋮ → Allow restricted settings, confirm on the device, then return and retry the blocked access. You can skip access you don’t want. Revoke Super Powers below removes special ADB access. Camera and microphone permissions remain until you remove them in App info."

    fun guide(): String = """
        ## Max Agentic Control and restricted settings

        Only expand access when the owner requests it. Explain that full access includes notifications, screen viewing/control, camera, microphone and location; the owner can skip individual access or revoke it later. Pairing alone is not authorization to enable everything.

        1. Read `GET /v1/adb/status` and `GET /v1/capabilities`. Identify the installed build, ADB connection, shell switch and your agent's allowed features. `ready` alone does not mean your agent is authorized or the shell is connected. The Play build does not support in-app ADB control.
        2. If Bridgit already has ADB and the owner has enabled **Let agents use ADB** and your **Agent Super Powers**, do the work: call `POST /v1/adb/grant {}`. This includes lifting restricted settings for Bridgit before granting accessibility and notification access. Do not make the owner flip switches ADB can grant. Respect `forbidden` or a disabled switch; never grant yourself Super Powers through the shell.
        3. If you have an authorized computer-side ADB connection instead, use **More → ADB setup → Share prompt** (or **Copy prompt**) for this installed app's commands. Select the intended device explicitly. Computer ADB access and Bridgit's own Wireless debugging pairing are separate; one does not prove the other is connected.
        4. Read each command result and fetch `GET /v1/adb/status` again. A false grant still needs attention; a null or unreadable check is **unverified**, not success. Recheck `GET /v1/capabilities` and, for app control, `GET /v2/capabilities`. Android grants do not turn on all Bridgit switches or grant per-agent access. Ask the owner to enable **Max Agentic Control** in **More → Settings**, then finish only the remaining prompts. Do not claim everything is enabled until the relevant checks and a requested feature actually work.
        5. If restricted settings remain blocked, open App info for the installed package: `am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:<installed-package>`. Tell the owner exactly: **⋮** (top right) → **Allow restricted settings** → confirm with their PIN or fingerprint if asked → return to Bridgit → retry the blocked access. The owner enters credentials privately. On some Android versions the menu appears only after trying the blocked access once. If the menu is absent or device policy refuses access, report that exact blocker; do not keep retrying or disable device security.
        6. Guide one device step at a time, wait for the owner, and recheck before continuing. Never send them to a generic help article as the next action. An OEM restriction, work profile, Health Connect grant, or Android consent dialog can still need an on-device action; ADB is shell access, not root or a promise of access to everything. Preserve other accessibility services and unrelated apps' settings. Report **working**, **still off**, **unsupported** and **unverified** separately. **Revoke Super Powers** in **More → ADB setup** removes special ADB access; ordinary camera/microphone permissions remain until removed in App info. **Max Agentic Control** off turns off Bridgit's feature switches.
    """.trimIndent()
}
