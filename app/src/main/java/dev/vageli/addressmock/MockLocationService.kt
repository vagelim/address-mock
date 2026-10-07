package dev.vageli.addressmock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.cos
import kotlin.random.Random

sealed interface MockState {
    data object Idle : MockState
    data class Running(val place: Place) : MockState
    data class Error(val message: String) : MockState
}

class MockLocationService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val JITTER_METERS = 1.5

        private val _state = MutableStateFlow<MockState>(MockState.Idle)
        val state: StateFlow<MockState> = _state

        fun start(context: Context, place: Place) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, place.label)
                .putExtra(EXTRA_LAT, place.lat)
                .putExtra(EXTRA_LNG, place.lng)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MockLocationService::class.java).setAction(ACTION_STOP))
        }

        fun clearError() {
            if (_state.value is MockState.Error) _state.value = MockState.Idle
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private lateinit var lm: LocationManager
    private lateinit var fused: FusedLocationProviderClient
    private val activeProviders = mutableSetOf<String>()
    private var fusedMockEnabled = false

    private val providers: List<String>
        get() = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val place = Place(
                    intent.getStringExtra(EXTRA_LABEL) ?: "",
                    intent.getDoubleExtra(EXTRA_LAT, 0.0),
                    intent.getDoubleExtra(EXTRA_LNG, 0.0),
                )
                begin(place)
            }
            else -> {
                teardown()
                _state.value = MockState.Idle
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(place: Place) {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(place),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            fail("Could not start foreground service. Grant location permission and try again. (${e.message})")
            return
        }

        loop?.cancel()
        try {
            setupTestProviders()
        } catch (e: SecurityException) {
            fail("This app is not selected as the mock location app. Open Developer options → \"Select mock location app\" → Address Mock.")
            return
        }

        _state.value = MockState.Running(place)
        loop = scope.launch {
            enableFusedMock()
            while (isActive) {
                push(place)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setupTestProviders() {
        for (name in providers) {
            if (name in activeProviders) continue
            runCatching { lm.removeTestProvider(name) }
            try {
                lm.addTestProvider(
                    name,
                    name == LocationManager.NETWORK_PROVIDER,
                    name == LocationManager.GPS_PROVIDER,
                    false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(name, true)
                activeProviders += name
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
                // Some OEMs reject certain providers (often "fused"); the rest still work.
            }
        }
        if (activeProviders.isEmpty()) throw SecurityException("no providers")
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableFusedMock() {
        fusedMockEnabled = runCatching { fused.setMockMode(true).await(); true }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun push(place: Place) {
        val (lat, lng) = jitter(place.lat, place.lng)
        for (name in activeProviders.toList()) {
            try {
                lm.setTestProviderLocation(name, buildLocation(name, lat, lng))
            } catch (e: SecurityException) {
                fail("Mock location permission was revoked.")
                return
            } catch (_: Exception) {
            }
        }
        if (fusedMockEnabled) {
            runCatching { fused.setMockLocation(buildLocation(FUSED_PROVIDER_NAME, lat, lng)) }
        }
    }

    private fun buildLocation(provider: String, lat: Double, lng: Double) = Location(provider).apply {
        latitude = lat
        longitude = lng
        altitude = 10.0
        accuracy = 3f + Random.nextFloat() * 2f
        speed = 0f
        bearing = 0f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        verticalAccuracyMeters = 3f
        speedAccuracyMetersPerSecond = 0.1f
        bearingAccuracyDegrees = 1f
    }

    /** Tiny random offset so the position doesn't look perfectly frozen. */
    private fun jitter(lat: Double, lng: Double): Pair<Double, Double> {
        val dLat = (Random.nextDouble() * 2 - 1) * JITTER_METERS / 111_320.0
        val dLng = (Random.nextDouble() * 2 - 1) * JITTER_METERS / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return lat + dLat to lng + dLng
    }

    private fun fail(message: String) {
        teardown()
        _state.value = MockState.Error(message)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        loop?.cancel()
        loop = null
        for (name in activeProviders) {
            runCatching { lm.setTestProviderEnabled(name, false) }
            runCatching { lm.removeTestProvider(name) }
        }
        activeProviders.clear()
        if (fusedMockEnabled) {
            runCatching { fused.setMockMode(false) }
            fusedMockEnabled = false
        }
    }

    override fun onDestroy() {
        teardown()
        if (_state.value is MockState.Running) _state.value = MockState.Idle
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(place: Place): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Mocking location")
            .setContentText(place.label.ifBlank { place.coords })
            .setStyle(NotificationCompat.BigTextStyle().bigText("${place.label}\n${place.coords}"))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }
}

private const val FUSED_PROVIDER_NAME = "fused"
