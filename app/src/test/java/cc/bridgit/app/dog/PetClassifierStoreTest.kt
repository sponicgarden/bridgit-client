package cc.bridgit.app.dog

import android.content.Context
import cc.bridgit.app.applets.AppletPackage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PetClassifierStoreTest {
    private val profile get() = JSONObject(File("../applets/dog-monitor/editions/1.5.0/applet.json").readText())
        .getJSONObject("processing").getJSONObject("classifier")

    @Test fun visualOnlyForkUsesPinnedAudioUntilOwnerSelectsNewProfile() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("sound-test", Context.MODE_PRIVATE)
        val previous = profile.apply { remove("sound"); put("note", "Private visual note") }
        var downloads = 0
        val store = PetClassifierStore(prefs, previous) { downloads++; profile }
        assertEquals("Private visual note", store.current().state().getString("note"))
        assertEquals(0, downloads)
        val original = store.audioQuestions().getJSONObject("sound").getString("instructions")
        assertEquals(1, downloads)
        val fork = profile.apply { getJSONObject("sound").put("instructions", "Listen for this dog's bark.") }
        store.select(fork)
        assertEquals("Listen for this dog's bark.", store.audioQuestions().getJSONObject("sound").getString("instructions"))
        assertThrows(AppletPackage.Bad::class.java) { store.select(profile.put("sound", JSONObject.NULL)) }
        store.select(previous)
        assertEquals(original, store.audioQuestions().getJSONObject("sound").getString("instructions"))
        assertEquals(1, downloads)
    }

    @Test fun olderEditionsCacheOnlyValidatedDefaultsAndPrivateSelectionsSurviveRestart() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("classifier-test", Context.MODE_PRIVATE)
        var requests = 0
        val initial = PetClassifierStore(prefs,null) {
            requests++
            if (requests == 1) JSONObject() else profile
        }
        assertThrows(AppletPackage.Bad::class.java) { initial.current() }
        val historical = initial.current()
        assertEquals(2,requests)
        assertFalse(historical.questions().has("pet_box"))
        assertTrue(historical.questions(withBox=true).has("pet_box"))
        assertEquals(historical.questions().toString(),initial.current().questions().toString())
        val privateProfile = profile.put("note","Private study")
        initial.select(privateProfile)
        assertEquals("Private study",initial.current().state().getString("note"))
        assertFalse(prefs.all.values.any { it.toString().contains("Private study") })
        val restarted = PetClassifierStore(prefs,privateProfile) { error("Unexpected registry download") }
        assertEquals("Private study",restarted.current().state().getString("note"))
        assertThrows(AppletPackage.Bad::class.java) { restarted.select(JSONObject()) }
        assertEquals("Private study",restarted.current().state().getString("note"))
        restarted.select(null)
        assertEquals(historical.state().toString(),restarted.current().state().toString())
        assertFalse(prefs.all.values.any { it.toString().contains("Private study") })
    }
}
