package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Budgets (docs/spec-budgets.md section 9, phase 1): an agent changes an installed applet's limits, tops one up for
 * the current period, or changes an action's interval. The phone checks this permission (its master switch is on by
 * default; per agent it is on for the agent that installed the applet), then forwards the change to the Bridgit
 * backend, which holds the budget for every phone of the account and applies the owner's ceiling and the agents'
 * daily change limit. Reading a budget (GET /v1/applets/{id}/budget) needs no Budgets permission.
 */
object BudgetEndpoints {
    const val CAP = BudgetModule.ID
    private val ERRORS = listOf("not_found", "invalid_parameter", "agents_may_not_change", "over_ceiling", "rate_limited", "out_of_range", "capability_disabled", "team_unavailable")
    val SET = EndpointSpec("PUT", "/applets/{id}/budget", CAP,
        "Set an installed applet's money limits (all the owner's phones share them): each {id?, usd 0.01-100, per hour|day|week|month, check_at?}; within the owner's ceiling",
        idempotent = true, body = listOf(Param("limits", "array", "[{id, usd, per, check_at}] — the new limits, replacing the old ones", required = true)),
        example = "PUT /v1/applets/ai0123456789abcd/budget {\"limits\":[{\"id\":\"l2\",\"usd\":3,\"per\":\"month\",\"check_at\":[0.5]}]}",
        returns = "the applet's budget as GET /v1/applets/{id}/budget returns it (after the change)", errors = ERRORS)
    val TOP_UP = EndpointSpec("POST", "/applets/{id}/budget/top-up", CAP,
        "Add money to one limit of an installed applet for the current period only (it ends at the reset); stopped checks resume at once",
        idempotent = true, body = listOf(Param("limit_id", "string", "The limit to top up (GET …/budget lists them)", required = true),
            Param("usd", "number", "Dollars to add for this period, 0.01-100", required = true)),
        example = "POST /v1/applets/ai0123456789abcd/budget/top-up {\"limit_id\":\"l2\",\"usd\":1}",
        returns = "the applet's budget (after the top-up)", errors = ERRORS)
    val ACTION_SET = EndpointSpec("PUT", "/applets/{id}/actions/{action}", CAP,
        "Change how often one action of an installed applet runs (every_s within the action's min_s..max_s), on every phone; the running watches change in place and keep their counts. dry_run returns the new projection without saving",
        idempotent = true, body = listOf(Param("every_s", "integer", "Seconds between runs, within min_s..max_s (GET …/budget lists the actions)", required = true),
            Param("dry_run", "boolean", "true: only show the projection")),
        example = "PUT /v1/applets/ai0123456789abcd/actions/main {\"every_s\":1200,\"dry_run\":true}",
        returns = "the backend's projection (dry_run) or the applet's budget after the change", errors = ERRORS)
    val ALL = listOf(SET, TOP_UP, ACTION_SET)
}

class BudgetModule(app: BridgeApp) : Capability(app) {
    override val id = ID
    override val titleRes = R.string.cap_budgets
    override val descriptionRes = R.string.cap_budgets_desc
    override val group = "apps"
    override val switchKey = ID
    override val endpoints = BudgetEndpoints.ALL

    companion object {
        const val ID = "budgets"

        /** A backend refusal as the phone's error (the codes pass through; anything else is team_unavailable). */
        fun error(e: Exception): Nothing = throw when (e) {
            is BudgetSync.Refused -> {
                val code = e.code.takeIf { runCatching { ErrorCatalog.http(it) }.isSuccess } ?: if (e.http == 400 || e.http == 422) "invalid_parameter" else "team_unavailable"
                val extra = LinkedHashMap<String, Any?>()
                e.body.keys().forEach { k -> if (k != "error" && k != "message") extra[k] = e.body.opt(k) }
                ErrorCatalog.error(code, e.message ?: code, extra)
            }
            is AppletPackage.Bad -> ErrorCatalog.error("invalid_parameter", e.message ?: "Bad limits.")
            is cc.bridgit.app.api.ApiError -> e
            is java.io.IOException -> ErrorCatalog.error("team_unavailable", "The Bridgit backend can't be reached right now; try again later.")
            else -> ErrorCatalog.error("team_unavailable", e.message ?: e.javaClass.simpleName)
        }

        /** What GET /v1/applets/{id}/budget returns: the last sync's applet object, or what the phone knows offline. */
        fun view(app: BridgeApp, r: JSONObject): JSONObject {
            val applet = AppletBudget.appletOf(r)
            val local = AppletBudget.status(app, r)
            val synced = AppletBudget.synced(app, applet)
            val out = synced?.let { JSONObject(it.toString()).apply { remove("recent_changes"); remove("events") } }
                ?: JSONObject().put("applet", applet).put("state", local.optString("state")).put("limits", local.getJSONArray("limits"))
                    .put("source", local.optString("set_by")).put("actions", AppletActions.json(app.budgetSync.actionsOf(r)))
            return out.put("instance_id", r.optString("id")).put("name", r.optString("name"))
                .put("this_phone", JSONObject().put("spent_today_usd", local.optDouble("spent_today_usd")).put("spent_month_usd", local.optDouble("spent_month_usd")))
                .put("synced_at", synced?.optLong("synced_at")?.takeIf { it > 0 }?.let { cc.bridgit.app.capability.isoAt(it) } ?: JSONObject.NULL)
                .put("changes", JSONArray(app.budgetSync.recentChanges(applet)))
        }
    }

    private fun instance(id: String): JSONObject = app.applets.get(id) ?: throw ErrorCatalog.error("not_found", "No installed applet $id.")

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(BudgetEndpoints.SET) {
            requireUsable()
            val r = instance(path("id").orEmpty())
            val list = body.optJSONArray("limits") ?: throw ErrorCatalog.error("invalid_parameter", "limits must be a list of {usd, per}.")
            val limits = try { AppletBudget.parseLimits(list) } catch (e: AppletPackage.Bad) { error(e) }
            val a = agent!!
            withContext(Dispatchers.IO) { try { app.budgetSync.setLimits(AppletBudget.appletOf(r), limits, app.budgetSync.by(a)); app.budgetSync.syncNow() } catch (e: Exception) { error(e) } }
            message = "${a.name} set ${r.optString("name")}'s limits: ${limits.joinToString { "${AppletBudget.money(it.usd)} ${AppletBudget.perWords(it.per)}" }}"
            Reply.Json(view(app, app.applets.get(r.getString("id")) ?: r))
        }
        endpoint(BudgetEndpoints.TOP_UP) {
            requireUsable()
            val r = instance(path("id").orEmpty())
            val limitId = body.optString("limit_id").ifEmpty { throw ErrorCatalog.error("invalid_parameter", "limit_id names the limit to top up.") }
            val usd = (body.opt("usd") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in AppletBudget.MIN_USD..AppletBudget.MAX_USD }
                ?: throw ErrorCatalog.error("invalid_parameter", "usd must be from 0.01 to 100 dollars.")
            val a = agent!!
            withContext(Dispatchers.IO) { try { app.budgetSync.topUp(AppletBudget.appletOf(r), limitId, usd, app.budgetSync.by(a)); app.budgetSync.syncNow() } catch (e: Exception) { error(e) } }
            message = "${a.name} topped up ${r.optString("name")}'s $limitId by ${AppletBudget.money(usd)}"
            Reply.Json(view(app, r))
        }
        endpoint(BudgetEndpoints.ACTION_SET) {
            requireUsable()
            val r = instance(path("id").orEmpty())
            val action = path("action").orEmpty()
            val known = app.budgetSync.actionsOf(r).firstOrNull { it.id == action }
                ?: throw ErrorCatalog.error("not_found", "${r.optString("name")} has no action $action (${app.budgetSync.actionsOf(r).joinToString { it.id }}).")
            val every = (body.opt("every_s") as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
                ?: throw ErrorCatalog.error("invalid_parameter", "every_s must be whole seconds.")
            val dry = body.optBoolean("dry_run", false)
            val a = agent!!
            val reply = withContext(Dispatchers.IO) { try { app.budgetSync.setAction(AppletBudget.appletOf(r), known.id, every, app.budgetSync.by(a), dry).also { if (!dry) app.budgetSync.syncNow() } } catch (e: Exception) { error(e) } }
            message = "${a.name} ${if (dry) "tried" else "set"} ${r.optString("name")}'s ${known.id} every $every s"
            Reply.Json(if (dry) reply.put("dry_run", true) else view(app, r))
        }
    }
}
