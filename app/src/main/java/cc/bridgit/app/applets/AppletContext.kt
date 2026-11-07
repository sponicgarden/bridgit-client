package cc.bridgit.app.applets

import cc.bridgit.app.api.Endpoints
import org.json.JSONArray
import org.json.JSONObject

/** Metadata only. Runtime endpoint/capability checks still govern every read and purpose. */
object AppletContext {
    fun catalog(): JSONObject {
        val variables=JSONArray()
        fun add(id: String,type: String,unit: String,path: String,field: String,purpose: String,freshness: String,fallback: String,scope: String? = null) {
            val endpoint=Endpoints.all.firstOrNull { it.method=="GET" && it.fullPath==path } ?: return
            variables.put(JSONObject().put("id",id).put("type",type).put("unit",unit).put("operation","GET $path")
                .put("field",field).put("capability",endpoint.capability).put("purpose",purpose).put("freshness",freshness).put("fallback",fallback).apply { if (scope != null) put("scope", scope) })
        }
        add("battery.level","number","percent","/v1/health","battery_level","Adapt optional work to remaining power","Read on demand; do not retain a constant","unknown")
        add("power.charging","boolean","none","/v1/health","charging","Defer optional work until power is connected","Read on demand; charging does not prove battery presence","unknown")
        add("battery.runtime","object","seconds","/v1/health","battery_runtime","Estimate available unplugged runtime","Honor learning/status and observed_seconds","unknown; do not assume zero")
        add("device.api_revision","integer","revision","/v1/health","api_revision","Choose compatible applet operations","Read after connecting/updating device","block unsupported features")
        add("location.fix","object","degrees/metres/seconds","/v1/location","latitude,longitude,accuracy_m,fix_time,age_s,precision","Only for an owner-requested location feature","Honor age_s, accuracy_m and precision; never treat a cached fix as current","owner-entered city or unavailable")
        add("context.place","string","none","/v1/context","temporary.place.value","Adapt an owner-approved applet to home, work, retail, vacation_spot or other","Honor status, confidence and expires_at; uncertain or deleted is null","unknown", "context.read")
        add("context.night","boolean","none","/v1/context","temporary.night.value","Adapt an owner-approved applet to night separately from place","Honor source and expires_at; local-clock fallback is inferred","unknown", "context.read")
        add("context.timezone","string","time zone","/v1/context","temporary.timezone.value","Use the phone's current time zone","Honor expires_at and deleted status","unknown", "context.read")
        add("context.home","string","none","/v1/context","durable.home.value","Read the owner's saved or inferred home label","Honor source, confidence and observed_at; it is not an exact GPS fix","unknown", "context.read")
        add("context.work","string","none","/v1/context","durable.work.value","Read the owner's saved or inferred workplace label","Honor source, confidence and observed_at; it is not an exact GPS fix","unknown", "context.read")
        return JSONObject().put("variables",variables).put("readiness","GET /v1/capabilities")
            .put("requirements","Readings require agent_allowed, enabled and ready. Declared purpose and access grants do not authorize retention or upload. Unsupported and denied are not false/zero.")
            .put("guide",AppletEvolution.GUIDE)
    }
}
