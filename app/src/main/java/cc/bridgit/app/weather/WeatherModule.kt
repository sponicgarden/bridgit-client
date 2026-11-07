package cc.bridgit.app.weather

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.LocationModule
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.capability.isoAt
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object WeatherEndpoints {
    val WEATHER = EndpointSpec("GET", "/weather", "weather",
        "Weather where the phone is (or at a lat/lon you give): conditions now, the next hours and the next 24 hours, from MET Norway. Schedule it like any call: {\"do\":{\"call\":\"GET /v1/weather\"}}",
        query = listOf(
            Param("lat", "number", "Latitude. With lon: weather there, and no location access is needed"),
            Param("lon", "number", "Longitude, with lat"),
            Param("hours", "integer", "Hourly forecast points after now, 0-${MetWeather.MAX_HOURS} (default ${MetWeather.DEFAULT_HOURS})")),
        example = "GET /v1/weather?hours=3",
        returns = "location{latitude, longitude, from (phone or request), rounded_to_decimals}, now{time, temperature_c, symbol, summary, precipitation_mm, precipitation_period, wind_speed_mps, wind_from_deg, wind_from, humidity_pct, cloud_pct, pressure_hpa}, next_hours[{time, temperature_c, symbol, summary, precipitation_mm, wind_speed_mps}], next_12_hours{symbol, summary}, next_24_hours{min_temperature_c, max_temperature_c, precipitation_mm}, forecast_updated_at, fetched_at, expires_at, cached, stale, source, attribution",
        errors = listOf("capability_disabled", "invalid_parameter", "agent_not_allowed", "permission_denied", "access_needed", "notifications_required", "capture_timeout", "upstream_failed"))
}

/**
 * Weather (owner request 2 Nov 2026): MET Norway's free forecast at the phone's rough location.
 * Coordinates are rounded to 2 decimals (about 1 km) before they leave the phone. Using the phone's
 * position also needs the Location switch and this agent's location access.
 */
class WeatherModule(app: BridgeApp) : Capability(app) {
    override val id = "weather"
    override val titleRes = R.string.cap_weather
    override val descriptionRes = R.string.cap_weather_desc
    override val group = "where"
    override val switchKey = "weather"
    override val endpoints = listOf(WeatherEndpoints.WEATHER)
    override fun details() = JSONObject().put("source", MetWeather.SOURCE).put("attribution", MetWeather.ATTRIBUTION)
        .put("rounded_to_decimals", MetWeather.DECIMALS)

    private val met by lazy { MetClient("Bridgit/${app.appVersion()} (Android; https://bridgit.cc)") }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(WeatherEndpoints.WEATHER) {
            requireUsable()
            val hours = query("hours")?.let { h -> h.toIntOrNull()?.takeIf { it in 0..MetWeather.MAX_HOURS }
                ?: throw ErrorCatalog.error("invalid_parameter", "hours must be 0 to ${MetWeather.MAX_HOURS}.") } ?: MetWeather.DEFAULT_HOURS
            val latQ = query("lat"); val lonQ = query("lon")
            if ((latQ == null) != (lonQ == null)) throw ErrorCatalog.error("invalid_parameter", "Send both lat and lon, or neither for the phone's own location.")
            val (lat, lon, from) = if (latQ != null && lonQ != null) {
                val la = latQ.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: throw ErrorCatalog.error("invalid_parameter", "lat must be -90 to 90.")
                val lo = lonQ.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: throw ErrorCatalog.error("invalid_parameter", "lon must be -180 to 180.")
                Triple(la, lo, "request")
            } else {
                val a = agent!!
                if (!a.allows("location")) throw ErrorCatalog.error("agent_not_allowed",
                    "Weather at the phone needs location access, which this agent doesn't have. Send lat and lon, or ask the owner to allow location for this agent.")
                val loc = app.registry.byId("location") as LocationModule
                loc.ensureUsable()
                // A recent last-known fix is enough for weather; only ask Android for a new one when it's old.
                val last = loc.reader.lastKnown()?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
                val fix = last ?: run {
                    val token = api.service.captureStarted(app.getString(R.string.notif_weather, agentName))
                    try { loc.reader.fresh(high = false, timeoutMs = 10_000) ?: loc.reader.lastKnown() } finally { api.service.captureEnded(token) }
                } ?: throw ErrorCatalog.error("capture_timeout", "No location fix yet. Try again later, or send lat and lon.")
                Triple(fix.latitude, fix.longitude, "phone")
            }
            val f = try {
                withContext(Dispatchers.IO) { met.forecast(lat, lon) }
            } catch (e: WeatherUnavailable) {
                throw ErrorCatalog.error("upstream_failed", e.message ?: "MET Norway is unavailable.",
                    mapOf("service" to "MET Norway", "upstream_status" to e.status, "reason" to e.reason))
            }
            val out = try { MetWeather.reply(f.body, System.currentTimeMillis(), hours, ::isoAt) } catch (e: Exception) {
                throw ErrorCatalog.error("upstream_failed", "MET Norway's forecast couldn't be read: ${e.message}", mapOf("service" to "MET Norway"))
            }
            val rLat = MetWeather.round(lat); val rLon = MetWeather.round(lon)
            out.put("location", JSONObject().put("latitude", rLat).put("longitude", rLon).put("from", from).put("rounded_to_decimals", MetWeather.DECIMALS))
                .put("fetched_at", isoAt(f.fetchedAt)).put("expires_at", isoAt(f.expiresAt))
                .put("cached", f.cached).put("stale", f.stale)
                .put("source", MetWeather.SOURCE).put("attribution", MetWeather.ATTRIBUTION)
            if (f.stale) out.put("note", "MET Norway didn't answer just now; this is the last forecast Bridgit fetched.")
            // LOG-02: coordinates in the log only to 3 decimals; these are already rounded to 2.
            data = JSONObject().put("lat", rLat).put("lon", rLon).put("from", from).put("cached", f.cached)
            Reply.Json(out)
        }
    }
}
