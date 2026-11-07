package cc.bridgit.app.dog

import android.content.SharedPreferences
import org.json.JSONObject

/** The owner-approved profile wins over the immutable compatibility release, including after restart. */
internal class PetClassifierStore(private val prefs: SharedPreferences, installed: JSONObject?, private val fetchDefault: () -> JSONObject) {
    @Volatile private var selected = installed?.let(PetClassifier::parse)
    private val legacy by lazy {
        val cached = prefs.getString(DEFAULT_KEY, null)
        val profile = cached?.let { runCatching { PetClassifier.parse(JSONObject(it)).takeIf { p -> p.audioQuestions() != null } }.getOrNull() }
        profile ?: fetchDefault().let { definition ->
            val validated = PetClassifier.parse(definition)
            requireNotNull(validated.audioQuestions()) { "Registry edition is missing the sound question." }
            prefs.edit().putString(DEFAULT_KEY, definition.toString()).apply()
            validated
        }
    }
    fun current(): PetClassifier = selected ?: legacy.let { selected ?: it }

    fun audioQuestions(): JSONObject = current().audioQuestions() ?: requireNotNull(legacy.audioQuestions())

    fun select(profile: JSONObject?) {
        selected = profile?.let(PetClassifier::parse)
    }

    companion object {
        const val REVISION = 6
        private const val DEFAULT_KEY = "dog_monitor_classifier_r6"
    }
}
