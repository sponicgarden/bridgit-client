package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Failure cases first: overlap, deletions, ambiguous ordering, stale work and lost ancestry. */
class AppletEvolutionTest {
    private fun j(s: String) = JSONObject(s)
    @Test fun independentChangesMergeWithoutMutatingInputs() {
        val b = j("""{"name":"Chef","summary":"old","version":"1"}""")
        val p = j("""{"name":"My chef","summary":"old","version":"2"}""")
        val t = j("""{"name":"Chef","summary":"new","version":"3"}""")
        val out = AppletEvolution.merge(b,p,t)
        assertEquals(0,out.getJSONArray("conflicts").length())
        assertEquals("My chef",out.getJSONObject("candidate").getString("name"))
        assertEquals("new",out.getJSONObject("candidate").getString("summary"))
        assertEquals("old",b.getString("summary"))
    }
    @Test fun conflictingEditsNeverSilentlyPreferTemplate() {
        val out = AppletEvolution.merge(j("""{"x":1}"""),j("""{"x":2}"""),j("""{"x":3}"""))
        assertEquals(1,out.getJSONArray("conflicts").length())
        assertEquals(2,out.getJSONObject("candidate").getInt("x"))
    }
    @Test fun deletedFeatureStaysDeletedAndEditDeleteNeedsDecision() {
        assertFalse(AppletEvolution.merge(j("""{"x":1}"""),j("{}"),j("""{"x":1,"y":2}""")).getJSONObject("candidate").has("x"))
        assertEquals(1,AppletEvolution.merge(j("""{"x":1}"""),j("{}"),j("""{"x":2}""")).getJSONArray("conflicts").length())
    }
    @Test fun componentArraysMergeByIdNotPosition() {
        val b=j("""{"processing":{"watches":[{"id":"a","x":1},{"id":"b","x":1}]}}""")
        val p=j("""{"processing":{"watches":[{"id":"a","x":2},{"id":"b","x":1}]}}""")
        val t=j("""{"processing":{"watches":[{"id":"a","x":1},{"id":"b","x":3}]}}""")
        val out=AppletEvolution.merge(b,p,t)
        assertEquals(0,out.getJSONArray("conflicts").length())
        val a=out.getJSONObject("candidate").getJSONObject("processing").getJSONArray("watches")
        assertEquals(2,a.getJSONObject(0).getInt("x"));assertEquals(3,a.getJSONObject(1).getInt("x"))
    }
    @Test fun unkeyedArraysAndReorderingAreNotGuessed() {
        assertTrue(AppletEvolution.merge(j("""{"x":[1,2]}"""),j("""{"x":[2,1]}"""),j("""{"x":[1,2,3]}""")).getJSONArray("conflicts").length()>0)
    }
    @Test fun legacyNeverInventsBaselineAndDetachKeepsOrigin() {
        val r=j("""{"definition":{"id":"chef"},"source":{"kind":"url","url":"https://bridgit.cc/chef.json"}}""")
        assertFalse(AppletEvolution.state(r).has("baseline"))
        AppletEvolution.initialize(r)
        val origin=AppletEvolution.state(r).getJSONObject("origin").toString()
        AppletEvolution.setPolicy(r,"detached","Repurpose as inventory")
        assertEquals(origin,AppletEvolution.state(r).getJSONObject("origin").toString())
        assertEquals("chef",r.getJSONObject("definition").getString("id"))
        assertFalse(AppletEvolution.tracking(r))
    }
    @Test fun personalSnapshotsAndRequirementsSurvivePolicyChange() {
        val r=j("""{"definition":{"id":"chef"},"config":{"vegetarian":true},"source":{"kind":"url","url":"https://bridgit.cc/chef.json"}}""")
        AppletEvolution.initialize(r)
        AppletEvolution.record(r,"Vegetarian only",JSONArray().put("No meat suggestions"))
        AppletEvolution.setPolicy(r,"paused","Keep this version")
        assertEquals("Vegetarian only",AppletEvolution.state(r).getJSONArray("journal").getJSONObject(0).getString("intent"))
        assertTrue(r.getJSONObject("config").getBoolean("vegetarian"))
    }
}
