package cc.bridgit.app.platform

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.power.BatteryRules
import cc.bridgit.app.power.PowerRules
import cc.bridgit.app.power.SunTimes
import org.json.JSONArray
import org.json.JSONObject

/** Shared device and offline authoring rules. Device callers supply the owner's live connection checks. */
object WorkflowDefinition {
    val FIELDS = listOf("name","trigger","steps","ui","max_paid_per_day","cooldown_s","max_runs_per_day","only_if")
    /** The `battery` trigger's `on` names: [BatteryRules.EVENTS] without the charging pair (the power trigger has those). */
    val BATTERY_ONS = BatteryRules.EVENTS.filter { "charging" !in it }.map { it.removePrefix("battery.") }

    /**
     * A flow's cooldown in seconds: `cooldown_s`, or an applet.event trigger's old `cooldown_seconds` (its alias).
     * For an applet.event flow it is kept per device and event type ([ApplicationEvents] checks it, default 30),
     * so the whole-flow gate (in the engine) leaves it out.
     */
    fun cooldownOf(definition:JSONObject):Int {
        if(definition.has("cooldown_s")) return definition.optInt("cooldown_s")
        val t=definition.optJSONObject("trigger")
        return if(t!=null && ApplicationEvents.isAppletEvent(t.optString("type")) && t.has("cooldown_seconds")) t.optInt("cooldown_seconds") else 0
    }
    fun perEvent(definition:JSONObject)=ApplicationEvents.isAppletEvent(definition.optJSONObject("trigger")?.optString("type"))

    fun geofenceRadius(trigger: JSONObject): Int = trigger.whole("radius_m", 200, 100, 10000)

    fun validate(b: JSONObject, validateDestinations: (List<String>) -> Unit,
                 validateSend: (FlowSteps.SendUse) -> Unit): JSONObject {
        b.keys().asSequence().firstOrNull { it !in FIELDS }?.let { throw ApiError(422,"invalid_workflow","$it isn't a workflow field (${FIELDS.joinToString()})") }
        b.text("name",120)
        val trigger=b.optJSONObject("trigger") ?: throw ApiError(422,"invalid_workflow","trigger is required")
        val type=trigger.text("type",32)
        when(type) {
            "applet.event", "application.event" -> ApplicationEvents.validateTrigger(trigger)
            "button","charging" -> trigger.only("type")
            "power" -> {
                trigger.only("type","on","for_s")
                if(trigger.opt("on") !in PowerRules.ONS) throw ApiError(422,"invalid_workflow","trigger.on must be ${PowerRules.ONS.joinToString(", ")}")
                trigger.whole("for_s",0,0,PowerRules.MAX_FOR_S)
            }
            "battery" -> {
                trigger.only("type","on")
                if(trigger.opt("on") !in BATTERY_ONS) throw ApiError(422,"invalid_workflow","trigger.on must be ${BATTERY_ONS.joinToString(", ")}")
            }
            "sun" -> {
                trigger.only("type","on","offset_min","latitude","longitude")
                if(trigger.opt("on") !in SunTimes.ONS) throw ApiError(422,"invalid_workflow","trigger.on must be sunrise or sunset")
                trigger.whole("offset_min",0,-SunTimes.MAX_OFFSET_MIN,SunTimes.MAX_OFFSET_MIN)
                if(trigger.has("latitude")!=trigger.has("longitude")) throw ApiError(422,"invalid_workflow","Give both latitude and longitude, or neither (the phone then saves a rough position once)")
                if(trigger.has("latitude") && (!trigger.optDouble("latitude").isFinite() || !trigger.optDouble("longitude").isFinite() || trigger.optDouble("latitude") !in -90.0..90.0 || trigger.optDouble("longitude") !in -180.0..180.0)) throw ApiError(422,"invalid_workflow","Invalid sun coordinates")
            }
            "interval" -> { trigger.only("type","seconds"); trigger.whole("seconds",900,10,86400) }
            "geofence" -> {
                trigger.only("type","latitude","longitude","radius_m")
                if(!trigger.has("latitude") || !trigger.has("longitude") || !trigger.optDouble("latitude").isFinite() || !trigger.optDouble("longitude").isFinite() || trigger.optDouble("latitude") !in -90.0..90.0 || trigger.optDouble("longitude") !in -180.0..180.0) throw ApiError(422,"invalid_workflow","Invalid geofence coordinates")
                geofenceRadius(trigger)
            }
            "watch" -> {
                trigger.keys().asSequence().firstOrNull { it !in setOf("type","watch","on","rule") }?.let { throw ApiError(422,"invalid_workflow","trigger.$it isn't a watch trigger field (watch, on, rule)") }
                val watch=trigger.opt("watch") as? String
                if(watch==null || !Regex("^[A-Za-z0-9_-]{1,64}$").matches(watch)) throw ApiError(422,"invalid_workflow","trigger.watch must be the id of one of your watches")
                when(trigger.opt("on") ?: "match") {
                    "match" -> if(trigger.has("rule")) throw ApiError(422,"invalid_workflow","trigger.rule goes with on: rule")
                    "rule" -> if((trigger.opt("rule") as? String)?.matches(Regex("^[A-Za-z0-9_-]{1,40}$"))!=true) throw ApiError(422,"invalid_workflow","on: rule needs trigger.rule, the id of one of the watch's tally rules")
                    else -> throw ApiError(422,"invalid_workflow","trigger.on must be match or rule")
                }
            }
            "called" -> { trigger.only("type","with"); FlowSteps.withDeclared(trigger.opt("with")) }
            "mode" -> {
                trigger.only("type","to","from")
                for(k in listOf("to","from")) trigger.opt(k)?.let { if(it !is String || !Modes.NAME.matches(it)) throw ApiError(422,"invalid_workflow","trigger.$k must be a mode, like \"away\" (${Modes.DEFAULTS.joinToString()} or one the owner added)") }
            }
            else -> throw ApiError(422,"invalid_workflow","Triggers: button, interval, power, battery, sun, charging, geofence, watch, called, mode, applet.event (legacy application.event)")
        }
        if(b.has("cooldown_s")) {
            b.whole("cooldown_s",0,0,86400)
            if(ApplicationEvents.isAppletEvent(type) && trigger.has("cooldown_seconds") && trigger.optInt("cooldown_seconds")!=b.optInt("cooldown_s")) throw ApiError(422,"invalid_workflow","cooldown_s and trigger.cooldown_seconds disagree: trigger.cooldown_seconds is the old name for the flow's cooldown_s, so give one")
        }
        if(b.has("max_runs_per_day")) b.whole("max_runs_per_day",1000,1,1000)
        val steps=b.optJSONArray("steps") ?: throw ApiError(422,"invalid_workflow","steps are required")
        if(b.toString().toByteArray().size>65536) throw ApiError(422,"invalid_workflow","Use 1–32 steps, at most 64 KiB")
        val with=if(type=="called") FlowSteps.withDeclared(trigger.opt("with")) else emptySet()
        val checked=FlowSteps.check(steps,with)
        // A flow-level only_if (docs/applet-reach.md D1) is checked before the first step; it may read the trigger, not a step.
        val needs=FlowConditions.Needs().apply { add(checked.needs); b.opt("only_if")?.let { add(FlowSteps.flowCondition(it,with)) } }
        // share opens the share sheet: it needs the owner there, so only a flow started by a button may have it (O1).
        if(type!="button" && (0 until steps.length()).any { steps.optJSONObject(it)?.optString("type")=="share" }) throw ApiError(422,"invalid_workflow","A share step needs the owner present: only a flow with a button trigger may share (this one's trigger is $type)")
        checked.destinations.forEach(validateDestinations)
        checked.sends.forEach(validateSend)
        if(b.has("max_paid_per_day")) { if(checked.paidSteps==0) throw ApiError(422,"invalid_workflow","max_paid_per_day goes with an ask_model step"); b.whole("max_paid_per_day",FlowSteps.DEFAULT_PAID_PER_DAY,1,FlowSteps.MAX_PAID_PER_DAY) }
        val scopes=linkedSetOf("workflows")
        if(type=="geofence" || (type=="sun" && !trigger.has("latitude")) || needs.location) scopes.add("location")
        if(ApplicationEvents.isAppletEvent(type)) {
            scopes.add("events.read")
            if(trigger.getString("transport")=="notification") scopes.add("notifications.read")
        }
        scopes.addAll(checked.scopes)
        val ui=b.optJSONObject("ui")
        if(ui!=null) { ui.only("template","title","text"); if(ui.text("template",16) !in setOf("button","status","help")) throw ApiError(422,"invalid_workflow","Unknown native template"); ui.text("title",120,""); ui.text("text",1024,"") }
        return JSONObject().put("valid",true).put("required_scopes",JSONArray(scopes.toList())).put("digest",CanonicalJson.digest(b))
            .put("summary",FlowSteps.summary(b))
            .put("warnings",JSONArray().apply {
                // Validate catches the known traps (docs/applet-dev-experience.md B1): never blocks the proposal.
                if(checked.paidSteps>0 && !b.has("max_paid_per_day")) put(JSONObject().put("code","paid_without_cap").put("where","max_paid_per_day")
                    .put("says","The flow asks Jev or Clef and sets no max_paid_per_day, so up to ${FlowSteps.DEFAULT_PAID_PER_DAY} paid questions a day can run.")
                    .put("fix","Add max_paid_per_day sized for how often it should really run."))
            })
            .put("limits",JSONObject().put("max_steps",32).put("max_run_seconds",checked.runSeconds).put("max_paid_steps",FlowSteps.MAX_PAID_STEPS)
                .apply { if(checked.paidSteps>0) put("max_paid_per_day",b.optInt("max_paid_per_day",FlowSteps.DEFAULT_PAID_PER_DAY)) })
            .put("scheduling","Sub-minute intervals require the active listener; Android may defer maintenance jobs")
    }
}
