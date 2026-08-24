package com.shmetro.liveupdates.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.shmetro.liveupdates.data.MetroLines
import com.shmetro.liveupdates.location.StationLocator
import com.shmetro.liveupdates.location.TrackingStateHolder
import com.shmetro.liveupdates.notification.LiveUpdateNotifier

/**
 * Foreground (location-type) service that turns raw GPS fixes into "current / next station"
 * — auto-detecting the closest line out of [MetroLines.all] — and keeps the Live Update
 * notification in sync while it runs.
 *
 * Uses the plain platform [LocationManager] rather than Google Play Services' fused location
 * provider: on many devices (custom ROMs, or Play Services otherwise not fully functional) the
 * fused provider silently never delivers a callback, leaving tracking stuck. The platform GPS/
 * network providers work regardless of Play Services availability.
 */
class MetroTrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private var isRequestingLocation = false

    /** Whether the current location request is using the tighter near-arrival interval. */
    private var isNearIntervalActive = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in platform API, override required pre-API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * Keeps progress moving between real fixes by interpolating on elapsed time — real fixes
     * underground (weak/absent GPS/BeiDou signal) can be sparse, delayed, or never arrive until
     * the train reaches a station with signal, which otherwise leaves the UI static for the
     * whole ride. See [TrackingStateHolder.tick].
     */
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            TrackingStateHolder.tick()
            postNotification()
            tickHandler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    private fun handleLocation(location: Location) {
        val match = StationLocator.locateBest(MetroLines.all, location.latitude, location.longitude)
            ?: return
        TrackingStateHolder.update(match.line, match.result)
        postNotification()
        adjustLocationIntervalIfNeeded()
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        LiveUpdateNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LiveUpdateNotifier.ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        TrackingStateHolder.reset()
        TrackingStateHolder.setTracking(true)
        startForegroundWithNotification()
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdatesIfNeeded()
        TrackingStateHolder.setTracking(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopTracking() {
        stopLocationUpdatesIfNeeded()
        TrackingStateHolder.setTracking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        val notification = LiveUpdateNotifier.build(this, TrackingStateHolder.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                LiveUpdateNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(LiveUpdateNotifier.NOTIFICATION_ID, notification)
        }
    }

    private fun postNotification() {
        if (!hasNotificationPermission()) return
        val notification = LiveUpdateNotifier.build(this, TrackingStateHolder.state.value)
        NotificationManagerCompat.from(this).notify(LiveUpdateNotifier.NOTIFICATION_ID, notification)
    }

    private fun startLocationUpdates() {
        if (isRequestingLocation || !hasLocationPermission()) return
        isNearIntervalActive = false
        useMostRecentLastKnownLocation()
        requestLocationUpdates()
        tickHandler.postDelayed(tickRunnable, TICK_INTERVAL_MILLIS)
    }

    /**
     * Feeds in whatever fix any enabled provider already has cached so the UI leaves "locating"
     * immediately instead of waiting for the first fresh callback, which can otherwise take a
     * while (or, indoors, never arrive) on a cold GPS start.
     */
    private fun useMostRecentLastKnownLocation() {
        if (!hasLocationPermission()) return
        activeProviders()
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
            ?.let(::handleLocation)
    }

    /**
     * Widens the fix interval while cruising mid-segment (nothing station-relevant can happen
     * for a while) and narrows it again once the train is close to — or has reached — the next
     * station, where accurate arrival detection matters. This cuts the number of GPS wake-ups
     * over a typical ride by several times versus polling at the near-arrival cadence throughout,
     * which is the main lever available for reducing this service's power draw.
     */
    private fun adjustLocationIntervalIfNeeded() {
        if (!isRequestingLocation) return
        val state = TrackingStateHolder.state.value
        val distance = state.distanceToNextMeters
        val shouldBeNear = state.arriving || (distance != null && distance <= NEAR_DISTANCE_THRESHOLD_METERS)
        if (shouldBeNear == isNearIntervalActive) return
        isNearIntervalActive = shouldBeNear
        requestLocationUpdates()
    }

    private fun activeProviders(): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { provider ->
                try {
                    locationManager.isProviderEnabled(provider)
                } catch (e: IllegalArgumentException) {
                    false
                }
            }

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) return
        if (isRequestingLocation) {
            locationManager.removeUpdates(locationListener)
            isRequestingLocation = false
        }
        val minTimeMillis = if (isNearIntervalActive) NEAR_UPDATE_INTERVAL_MILLIS else FAR_UPDATE_INTERVAL_MILLIS
        val providers = activeProviders()
        for (provider in providers) {
            // PASSIVE_PROVIDER costs nothing extra to listen on — it only delivers fixes some
            // other app/service already requested (e.g. a map app open at the same time, which
            // may have indoor/underground coverage this service's own GPS+network requests
            // don't) — so there's no reason to throttle it the way the active providers are.
            val minTime = if (provider == LocationManager.PASSIVE_PROVIDER) 0L else minTimeMillis
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    minTime,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
                isRequestingLocation = true
            } catch (e: SecurityException) {
                // Permission revoked between the check above and this call; nothing to recover.
            }
        }
    }

    private fun stopLocationUpdatesIfNeeded() {
        tickHandler.removeCallbacks(tickRunnable)
        if (!isRequestingLocation) return
        locationManager.removeUpdates(locationListener)
        isRequestingLocation = false
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** Cruising mid-segment: no station change is imminent, so poll GPS sparingly. */
        private const val FAR_UPDATE_INTERVAL_MILLIS = 20_000L

        /** Near or at a station: poll more often so arrival/departure is detected promptly. */
        private const val NEAR_UPDATE_INTERVAL_MILLIS = 5_000L

        /** Distance to the next station at which we switch from the far to the near interval. */
        private const val NEAR_DISTANCE_THRESHOLD_METERS = 350

        /** How often to interpolate/republish progress between real fixes. */
        private const val TICK_INTERVAL_MILLIS = 2_000L
    }
}
