package dev.vageli.addressmock

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

object Geocoding {
    private const val TAG = "Geocoding"
    private const val MAX_RESULTS = 5
    private val COORD_REGEX =
        Regex("""(-?\d{1,2}(?:\.\d+)?)\s*[,\s]\s*(-?\d{1,3}(?:\.\d+)?)""")
    private val URL_REGEX = Regex("""https?://\S+""")

    /** Resolves free text (address, place name, or "lat, lng") to candidate places. */
    suspend fun search(context: Context, query: String): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        parseCoordinates(q)?.let { return listOf(it) }

        val native = runCatching { nativeGeocode(context, q) }
            .onFailure { Log.w(TAG, "Native geocoder failed", it) }
            .getOrDefault(emptyList())
        if (native.isNotEmpty()) return native

        // Nominatim is strict about mixed "Place Name, street, city" queries, so relax progressively.
        for ((i, variant) in queryVariants(q).withIndex()) {
            if (i > 0) delay(1000) // Nominatim usage policy: max 1 request/second
            val found = nominatim(variant)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun queryVariants(q: String): List<String> {
        val parts = q.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return buildList {
            add(q)
            for (i in 1 until parts.size - 1) add(parts.drop(i).joinToString(", "))
            if (parts.size > 1) add(parts.first())
        }.distinct()
    }

    /** Accepts "40.7128, -74.0060" or "40.7128 -74.0060" (the whole string must be coordinates). */
    fun parseCoordinates(text: String): Place? {
        val m = COORD_REGEX.matchEntire(text.trim()) ?: return null
        return toPlace(m)
    }

    /**
     * Cleans up text shared from other apps (e.g. Google Maps "Share" which sends
     * "Place Name\nAddress\nhttps://maps.app.goo.gl/...").
     * Returns coordinates if any are embedded, otherwise the text without URLs.
     */
    fun cleanSharedText(text: String): String {
        COORD_REGEX.findAll(text).firstNotNullOfOrNull { m ->
            toPlace(m)?.takeIf { m.value.contains('.') }
        }?.let { return "${it.lat}, ${it.lng}" }
        return text.replace(URL_REGEX, " ")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(", ")
    }

    private fun toPlace(m: MatchResult): Place? {
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return Place("%.6f, %.6f".format(Locale.US, lat, lng), lat, lng)
    }

    private suspend fun nativeGeocode(context: Context, q: String): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(q, MAX_RESULTS, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses)
                    override fun onError(errorMessage: String?) = cont.resume(emptyList())
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(q, MAX_RESULTS).orEmpty()
            }
        }
        return addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
            val label = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }
                .joinToString(", ")
                .ifBlank { a.featureName ?: q }
            Place(label, a.latitude, a.longitude)
        }
    }

    /** OpenStreetMap fallback for devices without a Geocoder backend (e.g. no Play Services). */
    private suspend fun nominatim(q: String): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=$MAX_RESULTS&q=" +
                    URLEncoder.encode(q, "UTF-8")
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "AddressMock/1.0 (Android)")
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Place(o.getString("display_name"), o.getString("lat").toDouble(), o.getString("lon").toDouble())
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "Nominatim lookup failed", it) }.getOrDefault(emptyList())
    }
}
