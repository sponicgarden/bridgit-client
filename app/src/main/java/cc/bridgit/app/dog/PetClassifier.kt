package cc.bridgit.app.dog

import cc.bridgit.app.applets.AppletPackage
import org.json.JSONObject

/** The legacy tracker consumes these answer IDs. Prompt text belongs to the pinned package. */
class PetClassifier private constructor(private val profile: JSONObject) {
    fun state(ref: String? = null): JSONObject = JSONObject().put("note", profile.getString("note")).apply {
        if (ref != null) put("photo", ref)
    }

    fun questions(photo: String = "`photo`", withBox: Boolean = false): JSONObject {
        val result = JSONObject(profile.getJSONObject("questions").toString())
        if (!withBox) result.remove("pet_box")
        for (key in result.keys()) {
            val question = result.getJSONObject(key)
            question.put("instructions", question.getString("instructions").replace("{{photo}}", photo))
        }
        return result
    }

    fun audioQuestions(): JSONObject? = profile.optJSONObject("sound")?.let {
        JSONObject().put("sound", JSONObject(it.toString()))
    }

    companion object {
        fun parse(profile: JSONObject): PetClassifier {
            fun invalid(): Nothing = throw AppletPackage.Bad("Invalid processing.classifier: expected a note and the tracker's dog_present, activity, mood and pet_box questions.")
            fun text(v: Any?) = v is String && v.isNotBlank() && v.length <= 1000
            val types = mapOf("dog_present" to "noul", "activity" to "choice", "mood" to "choice", "pet_box" to "box")
            val questions = profile.optJSONObject("questions") ?: invalid()
            if (profile.length() != (if (profile.has("sound")) 3 else 2) || !text(profile.opt("note")) || questions.length() != types.size) invalid()
            for ((key,type) in types) {
                val q = questions.optJSONObject(key) ?: invalid()
                if (q.opt("type") != type || !text(q.opt("instructions"))) invalid()
                if (type == "choice") {
                    val criteria = q.optJSONObject("criteria") ?: invalid()
                    val labels = if (key == "activity") DogJev.ACTIVITIES else DogJev.MOODS
                    if (q.length() != 3 || criteria.length() != labels.size || !labels.all { text(criteria.opt(it)) }) invalid()
                } else if (q.length() != 2) invalid()
            }
            if (profile.has("sound")) {
                val q = profile.optJSONObject("sound") ?: invalid()
                val criteria = q.optJSONObject("criteria") ?: invalid()
                if (q.length() != 3 || q.opt("type") != "choice" || !text(q.opt("instructions")) ||
                    criteria.length() != DogAudio.SOUNDS.size || !DogAudio.SOUNDS.all { text(criteria.opt(it)) })
                    throw AppletPackage.Bad("Invalid processing.classifier.sound question or criteria.")
            }
            return PetClassifier(JSONObject(profile.toString()))
        }
    }
}
