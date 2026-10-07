package dev.vageli.addressmock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Place(val label: String, val lat: Double, val lng: Double) {
    val coords: String get() = "%.6f, %.6f".format(java.util.Locale.US, lat, lng)
}

class RecentPlaces(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun load(): List<Place> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Place(o.getString("label"), o.getDouble("lat"), o.getDouble("lng"))
        }
    }.getOrDefault(emptyList())

    fun add(place: Place): List<Place> {
        val updated = (listOf(place) + load().filterNot { it.lat == place.lat && it.lng == place.lng })
            .take(MAX)
        save(updated)
        return updated
    }

    fun remove(place: Place): List<Place> {
        val updated = load().filterNot { it == place }
        save(updated)
        return updated
    }

    private fun save(list: List<Place>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("label", it.label).put("lat", it.lat).put("lng", it.lng))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "places"
        const val MAX = 15
    }
}
