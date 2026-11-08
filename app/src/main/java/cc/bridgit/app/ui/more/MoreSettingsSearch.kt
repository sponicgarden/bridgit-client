package cc.bridgit.app.ui.more

import cc.bridgit.app.R

/** Labels only: never index saved values, addresses or credentials. */
object MoreSettingsSearch {
    const val ARG_ROW = "settings_search_row"
    const val ARG_PROVIDER = "settings_search_provider"
    data class Entry(val row: Int, val title: Int)
    val entries = listOf(
        Entry(R.id.maxControl, R.string.settings_max_control),
        Entry(R.id.alwaysOn, R.string.settings_always_on),
        Entry(R.id.port, R.string.settings_port),
        Entry(R.id.bind, R.string.settings_bind),
        Entry(R.id.internetCallbacks, R.string.settings_internet_callbacks),
        Entry(R.id.logVisibility, R.string.settings_log_visibility),
        Entry(R.id.lockscreenText, R.string.settings_lockscreen_text),
        Entry(R.id.speakUrgent, R.string.settings_speak_urgent),
        Entry(R.id.outboundLog, R.string.settings_outbound_log),
        Entry(R.id.remoteView, R.string.settings_remote_view),
        Entry(R.id.crashReports, R.string.settings_crash_reports),
        Entry(R.id.usageTallies, R.string.settings_usage_tallies),
        Entry(R.id.usageWhat, R.string.settings_usage_what),
        Entry(R.id.feedbackUpload, R.string.settings_feedback_upload),
        Entry(R.id.securityDeviceLock, R.string.settings_security_device_lock),
        Entry(R.id.securityAlerts, R.string.settings_security_alerts),
        Entry(R.id.securityBiometric, R.string.settings_security_biometric),
        Entry(R.id.securitySigned, R.string.settings_security_signed),
        Entry(R.id.securityLearn, R.string.settings_security_learn),
        Entry(R.id.capturesAgentFiles, R.string.settings_captures_agent_files),
        Entry(R.id.capturesAgentUpload, R.string.settings_captures_agent_upload),
        Entry(R.id.capturesKeep, R.string.settings_captures_keep),
        Entry(R.id.capturesShareAll, R.string.settings_captures_share_all),
        Entry(R.id.capturesOwnerFiles, R.string.settings_captures_owner_files),
        Entry(R.id.capturesLockedApproval, R.string.settings_captures_locked_approval),
        Entry(R.id.capturesOwnerUpload, R.string.settings_captures_owner_upload),
        Entry(R.id.keep, R.string.settings_keep),
        Entry(R.id.export, R.string.settings_export),
        Entry(R.id.clear, R.string.settings_clear),
        Entry(R.id.notifListener, R.string.channel_listener),
        Entry(R.id.notifCapture, R.string.channel_capture),
        Entry(R.id.notifQuestions, R.string.channel_questions),
        Entry(R.id.notifNotices, R.string.channel_notices),
        Entry(R.id.openOnArrival, R.string.settings_open_on_arrival),
        Entry(R.id.alertFrequency, R.string.alerts_frequency),
        Entry(R.id.alertRecipient, R.string.alerts_recipient),
        Entry(R.id.alertTest, R.string.alerts_test),
        Entry(R.id.alertWhat, R.string.alerts_what),
        Entry(R.id.ownKeysWhat, R.string.own_keys_what),
        Entry(R.id.theme, R.string.settings_theme),
        Entry(R.id.version, R.string.settings_version),
        Entry(R.id.checkUpdates, R.string.update_check),
        Entry(R.id.autoUpdateInstall, R.string.update_auto_install),
        Entry(R.id.autoUpdateOffers, R.string.update_auto_offers),
        Entry(R.id.commit, R.string.settings_commit),
        Entry(R.id.cert, R.string.settings_cert),
        Entry(R.id.spec, R.string.settings_spec),
        Entry(R.id.privacyPolicy, R.string.settings_privacy_policy),
        Entry(R.id.terms, R.string.settings_terms),
        Entry(R.id.deleteAll, R.string.settings_delete_all)
    )
}
