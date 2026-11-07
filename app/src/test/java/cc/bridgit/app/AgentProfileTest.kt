package cc.bridgit.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentProfileTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var policy: Policy

    @Before fun setup() {
        context.getSharedPreferences("policy", Context.MODE_PRIVATE).edit().clear().commit()
        policy = Policy(context)
    }

    private fun png(width: Int = 32, height: Int = 32): String {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        image.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun rejects(status: Int = 422, action: () -> Unit) {
        try { action(); fail("Expected ApiError") } catch (e: ApiError) { assertEquals(status, e.http) }
    }

    @Test fun profilePersistsAndSurvivesTouchAndRotation() {
        val (agent, token) = policy.addAgent("Original")
        val icon = png()
        val wordmark = png(300, 60)
        policy.updateProfile(agent.id, JSONObject().put("name", "Muse").put("icon_base64", icon).put("wordmark_base64", wordmark))
        policy.touch(agent.id)
        val reloaded = Policy(context).agents().single()
        assertEquals("Muse", policy.authenticate(token)!!.name)
        assertEquals(icon, reloaded.iconBase64)
        assertEquals(wordmark, reloaded.wordmarkBase64)
        val (rotated, newToken) = policy.rotate(agent.id)
        assertEquals(icon, rotated.iconBase64)
        assertEquals(wordmark, rotated.wordmarkBase64)
        assertNull(policy.authenticate(token))
        assertEquals(agent.id, policy.authenticate(newToken)!!.id)
        assertFalse(AgentBranding.json(rotated).has("hash"))
        assertFalse(AgentBranding.json(rotated).has("salt"))
    }

    @Test fun updatesOnlyCallerAndSupportsPartialUpdateAndClear() {
        val (first, _) = policy.addAgent("First")
        val (other, _) = policy.addAgent("Other")
        policy.updateProfile(first.id, JSONObject().put("icon_base64", png()).put("wordmark_base64", png()))
        policy.updateProfile(first.id, JSONObject().put("name", "Renamed"))
        assertNotNull(policy.agents().first { it.id == first.id }.iconBase64)
        policy.updateProfile(first.id, JSONObject().put("icon_base64", JSONObject.NULL))
        assertNull(policy.agents().first { it.id == first.id }.iconBase64)
        assertNotNull(policy.agents().first { it.id == first.id }.wordmarkBase64)
        assertEquals(other, policy.agents().first { it.id == other.id })
        rejects { policy.updateProfile(first.id, JSONObject().put("name", "other")) }
        rejects { policy.updateProfile(first.id, JSONObject().put("id", other.id)) }
        rejects { policy.updateProfile(first.id, JSONObject().put("name", JSONObject.NULL)) }
    }

    @Test fun invalidAssetsAreRejectedWithoutPartialSave() {
        val (agent, _) = policy.addAgent("Original")
        for (invalid in listOf("", "https://example.com/icon.png", "<svg/>", "abcd", 123)) {
            rejects { policy.updateProfile(agent.id, JSONObject().put("name", "Changed").put("icon_base64", invalid)) }
            assertEquals("Original", policy.agents().single().name)
        }
        rejects { policy.updateProfile(agent.id, JSONObject().put("icon_base64", png(513, 1))) }
        rejects { policy.updateProfile(agent.id, JSONObject().put("wordmark_base64", png(2049, 1))) }
        rejects { policy.updateProfile(agent.id, JSONObject().put("wordmark_base64", png(1, 513))) }
        rejects(413) { policy.updateProfile(agent.id, JSONObject().put("icon_base64", "A".repeat(180000))) }
    }

    @Test fun legacyAgentsWorkWithoutArtworkAndRemovedAgentCannotUpdate() {
        val (agent, _) = policy.addAgent("Legacy")
        val profile = AgentBranding.json(policy.agents().single())
        assertTrue(profile.isNull("icon_base64"))
        assertTrue(profile.isNull("wordmark_base64"))
        policy.remove(agent.id)
        rejects(401) { policy.updateProfile(agent.id, JSONObject()) }
    }
}
