package cc.bridgit.app.judgment

import cc.bridgit.app.api.CapabilityCatalog
import cc.bridgit.app.api.Endpoints
import org.junit.Assert.*
import org.junit.Test

class ServiceCatalogTest {
    @Test fun glmIsGeneralAndAdvertisesExactCallsAndExistingAuthority() {
        val catalog=ServiceCatalog.json(true,false)
        val all=catalog.getJSONArray("services")
        val glm=(0 until all.length()).map(all::getJSONObject).single { it.getString("id")=="glm" }
        assertTrue(glm.getBoolean("applet_compatible"))
        assertTrue(glm.getBoolean("paid"))
        assertEquals("z-ai/glm-5.3-flash",glm.getString("provider_model"))
        assertEquals("/v1/judgment",glm.getJSONObject("call").getString("path"))
        assertEquals("judgment",glm.getJSONObject("call").getString("required_capability"))
        assertEquals("glm",glm.getJSONObject("call").getJSONObject("request").getString("model"))
        assertEquals("glm",glm.getJSONObject("applet").getJSONObject("watch").getString("model"))
        assertEquals("ask_model",glm.getJSONObject("applet").getJSONObject("flow_step").getString("type"))
        assertTrue(glm.getJSONObject("budget").getBoolean("owner_approval"))
        assertTrue(glm.getJSONObject("budget").getBoolean("applet_limits_also_apply"))
        assertEquals("status",Endpoints.SERVICES.capability)
        assertTrue(Endpoints.all.contains(Endpoints.SERVICES))
    }
    @Test fun azureImagesAdvertiseTheSharedAccountLimitAndIdempotentGrantedCall() {
        val all=ServiceCatalog.json(true,false).getJSONArray("services")
        val images=(0 until all.length()).map(all::getJSONObject).single { it.getString("id")=="images" }
        assertEquals("gpt-image-2.5-sunburst",images.getString("provider_model"))
        assertEquals(40,images.getJSONObject("quota").getInt("images_per_month"))
        assertEquals("account",images.getJSONObject("quota").getString("scope"))
        assertEquals("/v1/images/generations",images.getJSONObject("call").getString("path"))
        assertTrue(images.getJSONObject("call").getBoolean("idempotency_key_required"))
        assertTrue(Endpoints.all.contains(JudgmentModule.IMAGE_GENERATE))
        assertTrue(JudgmentModule.IMAGE_GENERATE.idempotencyRequired)
        assertEquals("judgment",JudgmentModule.IMAGE_GENERATE.capability)
    }
    @Test fun fluxSharesImageQuotaBudgetsAndExistingGrantedEndpoint() {
        val all=ServiceCatalog.json(true,false).getJSONArray("services")
        val flux=(0 until all.length()).map(all::getJSONObject).single { it.getString("id")=="flux_images" }
        assertEquals("black-forest-labs/flux-3-image-klein",flux.getString("provider_model"))
        assertEquals(40,flux.getJSONObject("quota").getInt("images_per_month"))
        assertEquals("images",flux.getJSONObject("budget").getString("service"))
        assertEquals("judgment",flux.getJSONObject("call").getString("required_capability"))
        assertEquals("flux_image_generation",flux.getJSONObject("call").getJSONObject("request").getString("task_key"))
    }
    @Test fun controlServerQueriesPointToTheSameGeneralServices() {
        val links=ServiceCatalog.links()
        assertEquals("/v1/services",links.getString("catalog_url"))
        assertEquals("services",links.getString("mcp_tool"))
        assertTrue(cc.bridgit.app.platform.PlatformApi.GUIDE.contains("GLM 5.3 Flash"))
        assertTrue(cc.bridgit.app.platform.PlatformApi.GUIDE.contains("GET /v1/services"))
    }
    @Test fun discoveryKeepsConfigurationAndPauseDistinctFromPermission() {
        val v=ServiceCatalog.json(false,true).getJSONArray("services").getJSONObject(0).getJSONObject("availability")
        assertFalse(v.getBoolean("backend_configured"))
        assertTrue(v.getBoolean("external_calls_paused"))
        assertEquals("/v1/capabilities",v.getString("permission_check"))
        assertFalse(v.has("ready"))
    }
    @Test fun aControlAgentsGlmQueryFindsTheGeneralDecisionCapability() {
        val results=CapabilityCatalog.search("GLM Flash external service",limit=5).getJSONArray("results")
        assertTrue((0 until results.length()).any { results.getJSONObject(it).getString("id")=="v1.judgment" })
        assertTrue(CapabilityCatalog.blurbs.getValue("v1.judgment").first.contains("any applet"))
    }
}
