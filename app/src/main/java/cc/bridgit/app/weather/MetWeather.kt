package cc.bridgit.app.weather

import cc.bridgit.app.core.SafeHttp
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Weather from MET Norway's Locationforecast 2.0 (api.met.no): free, no key, CC BY 4.0.
 * Their terms: an identifying User-Agent, at most 4 decimals in coordinates, and no new request
 * before the reply's Expires time (If-Modified-Since after that). Pure Kotlin, unit-tested.
 */
object MetWeather {
    const val URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
    const val SOURCE = "MET Norway (api.met.no)"
    const val ATTRIBUTION = "Weather data from MET Norway, CC BY 4.0"
    /** Two decimals is about 1 km: enough for weather, and the weather service never sees the exact spot. */
    const val DECIMALS = 2
    const val MAX_HOURS = 48
    const val DEFAULT_HOURS = 6

    fun round(coord: Double): Double = BigDecimal(coord).setScale(DECIMALS, RoundingMode.HALF_UP).toDouble()

    fun url(lat: Double, lon: Double): String = "$URL?lat=${plain(round(lat))}&lon=${plain(round(lon))}"

    private fun plain(d: Double) = BigDecimal(d).setScale(DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** An HTTP date ("Mon, 02 Nov 2026 04:02:29 GMT") as epoch ms, or null. */
    fun httpDate(s: String?): Long? = s?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }

    /** "lightrainshowers_day" → "Light rain showers". */
    fun summary(symbol: String?): String? {
        if (symbol.isNullOrBlank()) return null
        var s = symbol.substringBefore('_')
        val thunder = s.endsWith("andthunder")
        s = s.removeSuffix("andthunder")
            .replace("lightss", "light s") // MET's own spelling in e.g. "lightssleetshowersandthunder"
            .replace(Regex("^(light|heavy)(?! )"), "$1 ")
            .replace("clearsky", "clear sky").replace("partlycloudy", "partly cloudy")
            .replace("showers", " showers").replace("  ", " ").trim()
        if (thunder) s += " and thunder"
        return s.replaceFirstChar { it.uppercase() }
    }

    fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((((deg % 360) + 360) % 360 + 22.5) / 45).toInt() % 8]

    /**
     * The agent-facing reply from a Locationforecast "compact" body: conditions now, the next [hours]
     * hours, and the next 24 hours' range. [iso] formats epoch ms the way the rest of the API does.
     */
    fun reply(body: JSONObject, now: Long, hours: Int, iso: (Long) -> String): JSONObject {
        val props = body.getJSONObject("properties")
        val series = props.getJSONArray("timeseries")
        val points = (0 until series.length()).map { series.getJSONObject(it) }
            .mapNotNull { p -> runCatching { Instant.parse(p.getString("time")).toEpochMilli() }.getOrNull()?.let { it to p } }
        if (points.isEmpty()) throw IllegalArgumentException("MET Norway sent no forecast points.")
        // "Now" is the newest point at or before this moment (the current hour), else the first one.
        val currentIdx = points.indexOfLast { it.first <= now }.coerceAtLeast(0)
        val (curTime, cur) = points[currentIdx]
        val out = JSONObject()
        out.put("now", point(cur, curTime, iso, full = true))
        val upcoming = points.drop(currentIdx + 1).filter { it.first - curTime <= hours * 3_600_000L }.take(hours)
        out.put("next_hours", JSONArray(upcoming.map { (t, p) -> point(p, t, iso, full = false) }))
        val day = points.drop(currentIdx).filter { it.first - curTime < 24 * 3_600_000L }
        val temps = day.mapNotNull { instant(it.second)?.optDoubleOrNull("air_temperature") }
        // Hourly precipitation where the series is hourly; summed over the next 24 hours.
        val rain = day.mapNotNull { it.second.getJSONObject("data").optJSONObject("next_1_hours")?.optJSONObject("details")?.optDoubleOrNull("precipitation_amount") }
        out.put("next_24_hours", JSONObject()
            .put("min_temperature_c", temps.minOrNull() ?: JSONObject.NULL)
            .put("max_temperature_c", temps.maxOrNull() ?: JSONObject.NULL)
            .put("precipitation_mm", if (rain.isEmpty()) JSONObject.NULL else Math.round(rain.sum() * 10) / 10.0))
        val outlook = cur.getJSONObject("data").optJSONObject("next_12_hours")?.optJSONObject("summary")?.optString("symbol_code")?.ifEmpty { null }
        out.put("next_12_hours", JSONObject().put("symbol", outlook ?: JSONObject.NULL).put("summary", summary(outlook) ?: JSONObject.NULL))
        props.optJSONObject("meta")?.optString("updated_at")?.ifEmpty { null }?.let { u ->
            runCatching { Instant.parse(u).toEpochMilli() }.getOrNull()?.let { out.put("forecast_updated_at", iso(it)) }
        }
        return out
    }

    private fun instant(p: JSONObject) = p.optJSONObject("data")?.optJSONObject("instant")?.optJSONObject("details")

    private fun point(p: JSONObject, t: Long, iso: (Long) -> String, full: Boolean): JSONObject {
        val d = instant(p) ?: JSONObject()
        val data = p.getJSONObject("data")
        val next = data.optJSONObject("next_1_hours") ?: data.optJSONObject("next_6_hours")
        val symbol = next?.optJSONObject("summary")?.optString("symbol_code")?.ifEmpty { null }
        val rain = next?.optJSONObject("details")?.optDoubleOrNull("precipitation_amount")
        val o = JSONObject().put("time", iso(t))
            .put("temperature_c", d.optDoubleOrNull("air_temperature") ?: JSONObject.NULL)
            .put("symbol", symbol ?: JSONObject.NULL).put("summary", summary(symbol) ?: JSONObject.NULL)
            .put("precipitation_mm", rain ?: JSONObject.NULL)
            .put("wind_speed_mps", d.optDoubleOrNull("wind_speed") ?: JSONObject.NULL)
        if (full) {
            val from = d.optDoubleOrNull("wind_from_direction")
            o.put("wind_from_deg", from ?: JSONObject.NULL).put("wind_from", from?.let(::compass) ?: JSONObject.NULL)
                .put("humidity_pct", d.optDoubleOrNull("relative_humidity") ?: JSONObject.NULL)
                .put("cloud_pct", d.optDoubleOrNull("cloud_area_fraction") ?: JSONObject.NULL)
                .put("pressure_hpa", d.optDoubleOrNull("air_pressure_at_sea_level") ?: JSONObject.NULL)
                .put("precipitation_period", if (data.has("next_1_hours")) "next_1_hours" else if (data.has("next_6_hours")) "next_6_hours" else JSONObject.NULL)
        }
        return o
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
}

/** Thrown when MET Norway can't be reached and nothing is cached; [status] is their HTTP status, if any. */
class WeatherUnavailable(message: String, val status: Int? = null, val reason: String? = null) : Exception(message)

/**
 * Fetches and caches forecasts per rounded location, honouring Expires / Last-Modified as MET asks.
 * [fetch] and [clock] are swappable for tests.
 */
class MetClient(
    private val userAgent: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (String, Map<String, String>) -> SafeHttp.Result = { url, headers ->
        SafeHttp.get(url, SafeHttp.Policy.PUBLIC, 15_000, 2_000_000, headers = headers)
    },
) {
    class Forecast(val body: JSONObject, val fetchedAt: Long, val expiresAt: Long, val lastModified: String?, val cached: Boolean, val stale: Boolean)

    /** [stale]: MET failed and this older forecast is held until [expiresAt], a short back-off before asking again. */
    private class Entry(val body: String, val fetchedAt: Long, val expiresAt: Long, val lastModified: String?, val stale: Boolean = false)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 32
    }

    /** Requests actually sent to MET (tests). */
    @Volatile var requests = 0; private set

    @Synchronized
    fun forecast(lat: Double, lon: Double): Forecast {
        val url = MetWeather.url(lat, lon)
        val now = clock()
        val hit = cache[url]
        if (hit != null && now < hit.expiresAt) return hit.toForecast(cached = true, stale = hit.stale)
        val headers = mutableMapOf("User-Agent" to userAgent, "Accept" to "application/json")
        hit?.lastModified?.let { headers["If-Modified-Since"] = it }
        val r = try {
            requests++
            fetch(url, headers)
        } catch (e: SafeHttp.Failure) {
            if (hit != null) return backOff(url, hit, now)
            throw WeatherUnavailable("Couldn't reach MET Norway: ${e.message}", reason = e.reason.name.lowercase())
        }
        val expires = MetWeather.httpDate(r.header("expires"))?.takeIf { it > now } ?: (now + 30 * 60_000L)
        return when {
            r.status == 304 && hit != null -> Entry(hit.body, now, expires, r.header("last-modified") ?: hit.lastModified)
                .also { cache[url] = it }.toForecast(cached = true, stale = false)
            r.status in 200..299 && r.bytes != null -> {
                val text = String(r.bytes, Charsets.UTF_8)
                val parsed = runCatching { JSONObject(text) }.getOrElse {
                    if (hit != null) return backOff(url, hit, now)
                    throw WeatherUnavailable("MET Norway sent a reply that isn't JSON.", r.status)
                }
                Entry(text, now, expires, r.header("last-modified")).also { cache[url] = it }
                Forecast(parsed, now, expires, r.header("last-modified"), cached = false, stale = false)
            }
            hit != null -> backOff(url, hit, now)
            else -> throw WeatherUnavailable("MET Norway answered HTTP ${r.status}.", r.status)
        }
    }

    /** MET failed: serve the old forecast and don't ask MET again for [BACK_OFF_MS] (their guidance on 429/5xx). */
    private fun backOff(url: String, hit: Entry, now: Long): Forecast =
        Entry(hit.body, hit.fetchedAt, now + BACK_OFF_MS, hit.lastModified, stale = true).also { cache[url] = it }.toForecast(cached = true, stale = true)

    companion object { const val BACK_OFF_MS = 5 * 60_000L }

    private fun Entry.toForecast(cached: Boolean, stale: Boolean) = Forecast(JSONObject(body), fetchedAt, expiresAt, lastModified, cached, stale)
}
