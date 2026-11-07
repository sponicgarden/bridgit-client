package cc.bridgit.app.weather

import cc.bridgit.app.core.SafeHttp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class MetWeatherTest {
    private val fixture = javaClass.getResource("/met-locationforecast-compact.json")!!.readText()
    private val iso: (Long) -> String = { Instant.ofEpochMilli(it).toString() }

    @Test fun roundsToTwoDecimalsInTheUrl() {
        assertEquals("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=52.23&lon=21.01", MetWeather.url(52.229676, 21.012229))
        assertEquals("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=-33.87&lon=151.2", MetWeather.url(-33.8688, 151.1979))
        assertEquals(52.23, MetWeather.round(52.2349), 0.0)
    }

    @Test fun symbolSummaries() {
        assertEquals("Clear sky", MetWeather.summary("clearsky_day"))
        assertEquals("Partly cloudy", MetWeather.summary("partlycloudy_night"))
        assertEquals("Cloudy", MetWeather.summary("cloudy"))
        assertEquals("Light rain showers", MetWeather.summary("lightrainshowers_day"))
        assertEquals("Heavy rain and thunder", MetWeather.summary("heavyrainandthunder"))
        assertEquals("Light sleet showers and thunder", MetWeather.summary("lightssleetshowersandthunder_polartwilight"))
        assertEquals("Snow showers", MetWeather.summary("snowshowers_day"))
        assertEquals("Fog", MetWeather.summary("fog"))
        assertNull(MetWeather.summary(""))
    }

    @Test fun compass() {
        assertEquals("N", MetWeather.compass(355.0))
        assertEquals("NE", MetWeather.compass(20.8 + 30))
        assertEquals("W", MetWeather.compass(270.0))
    }

    @Test fun replyFromRealForecast() {
        val body = JSONObject(fixture)
        val series = body.getJSONObject("properties").getJSONArray("timeseries")
        val first = Instant.parse(series.getJSONObject(0).getString("time")).toEpochMilli()
        val now = first + 20 * 60_000L // 20 minutes into the first hour
        val r = MetWeather.reply(body, now, 3, iso)
        val cur = r.getJSONObject("now")
        assertEquals(iso(first), cur.getString("time"))
        assertTrue(cur.get("temperature_c") is Number)
        assertTrue(cur.getString("summary").isNotEmpty())
        assertEquals("next_1_hours", cur.getString("precipitation_period"))
        val next = r.getJSONArray("next_hours")
        assertEquals(3, next.length())
        assertEquals(iso(first + 3_600_000L), next.getJSONObject(0).getString("time"))
        val day = r.getJSONObject("next_24_hours")
        assertTrue(day.getDouble("min_temperature_c") <= day.getDouble("max_temperature_c"))
        assertTrue(r.has("forecast_updated_at"))
        assertTrue(r.getJSONObject("next_12_hours").getString("summary").isNotEmpty())
        // Small enough to arrive inside schedule.ran (8 KB) even at the maximum hours.
        assertTrue(MetWeather.reply(body, now, MetWeather.MAX_HOURS, iso).toString().length < 7000)
    }

    @Test fun zeroHoursAndAPointLaterInTheSeries() {
        val body = JSONObject(fixture)
        val series = body.getJSONObject("properties").getJSONArray("timeseries")
        val t5 = Instant.parse(series.getJSONObject(5).getString("time")).toEpochMilli()
        val r = MetWeather.reply(body, t5 + 1, 0, iso)
        assertEquals(iso(t5), r.getJSONObject("now").getString("time"))
        assertEquals(0, r.getJSONArray("next_hours").length())
    }

    private fun result(status: Int, body: String?, headers: Map<String, String> = emptyMap()) =
        SafeHttp.Result(status, headers.mapKeys { it.key.lowercase() }, body?.toByteArray(), body?.length?.toLong() ?: 0, "https://api.met.no/")

    @Test fun cachesUntilExpiresThenRevalidates() {
        var now = 1_800_000_000_000L
        val sent = mutableListOf<Map<String, String>>()
        val replies = ArrayDeque(listOf(
            result(200, fixture, mapOf("Expires" to java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 600_000).atZone(java.time.ZoneOffset.UTC)),
                "Last-Modified" to "Mon, 02 Nov 2026 03:32:12 GMT")),
            result(304, null, mapOf("Expires" to java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 1_800_000).atZone(java.time.ZoneOffset.UTC)))),
        ))
        val c = MetClient("Bridgit/test", { now }) { _, h -> sent += h; replies.removeFirst() }
        val a = c.forecast(52.2297, 21.0122)
        assertFalse(a.cached)
        assertEquals("Bridgit/test", sent[0]["User-Agent"])
        assertNull(sent[0]["If-Modified-Since"])
        // Same rounded spot, before Expires: no request.
        now += 300_000
        assertTrue(c.forecast(52.2301, 21.0099).cached)
        assertEquals(1, c.requests)
        // After Expires: If-Modified-Since, 304 reuses the body with the new Expires.
        now += 400_000
        val b = c.forecast(52.23, 21.01)
        assertEquals(2, c.requests)
        assertEquals("Mon, 02 Nov 2026 03:32:12 GMT", sent[1]["If-Modified-Since"])
        assertTrue(b.cached); assertFalse(b.stale)
        assertTrue(b.body.has("properties"))
    }

    @Test fun staleCacheWhenMetFailsAndErrorWhenNothingCached() {
        var now = 1_800_000_000_000L
        var fail = false
        val c = MetClient("Bridgit/test", { now }) { _, _ ->
            if (fail) throw SafeHttp.Failure(SafeHttp.Reason.TIMEOUT, "timed out") else result(200, fixture)
        }
        c.forecast(10.0, 10.0)
        now += 3_600_000 // past the default 30 min
        fail = true
        val s = c.forecast(10.0, 10.0)
        assertTrue(s.stale)
        // Backs off: no new request for 5 minutes, still marked stale; then tries MET again.
        val before = c.requests
        now += 60_000
        assertTrue(c.forecast(10.0, 10.0).stale)
        assertEquals(before, c.requests)
        now += MetClient.BACK_OFF_MS
        fail = false
        assertFalse(c.forecast(10.0, 10.0).stale)
        assertEquals(before + 1, c.requests)
        fail = true
        try { c.forecast(11.0, 11.0); fail("expected WeatherUnavailable") } catch (e: WeatherUnavailable) { assertEquals("timeout", e.reason) }
        val c2 = MetClient("Bridgit/test", { now }) { _, _ -> result(429, "slow down") }
        try { c2.forecast(1.0, 1.0); fail("expected WeatherUnavailable") } catch (e: WeatherUnavailable) { assertEquals(429, e.status) }
    }
}
