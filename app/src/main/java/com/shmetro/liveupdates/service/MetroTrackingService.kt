package com.shmetro.liveupdates.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.shmetro.liveupdates.data.MetroLines
import com.shmetro.liveupdates.location.StationLocator
import com.shmetro.liveupdates.location.TrackingStateHolder
import com.shmetro.liveupdates.notification.LiveUpdateNotifier

/**
 * Foreground (location-type) service that turns raw GPS fixes into "current / next station"
 * — auto-detecting the closest line out of [MetroLines.all] — and keeps the Live Update
 * notification in sync while it runs.
 */
class MetroTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isRequestingLocation = false

    /** Whether the current location request is using the tighter near-arrival interval. */
    private var isNearIntervalActive = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val match = StationLocator.locateBest(MetroLines.all, location.latitude, location.longitude)
                ?: return
            TrackingStateHolder.update(match.line, match.result)
            postNotification()
            adjustLocationIntervalIfNeeded()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
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
        requestLocationUpdates()
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

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) return
        if (isRequestingLocation) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        val interval = if (isNearIntervalActive) NEAR_UPDATE_INTERVAL_MILLIS else FAR_UPDATE_INTERVAL_MILLIS
        val minInterval = if (isNearIntervalActive) NEAR_MIN_UPDATE_INTERVAL_MILLIS else FAR_MIN_UPDATE_INTERVAL_MILLIS
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        isRequestingLocation = true
    }

    private fun stopLocationUpdatesIfNeeded() {
        if (!isRequestingLocation) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
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
        private const val FAR_MIN_UPDATE_INTERVAL_MILLIS = 10_000L

        /** Near or at a station: poll more often so arrival/departure is detected promptly. */
        private const val NEAR_UPDATE_INTERVAL_MILLIS = 5_000L
        private const val NEAR_MIN_UPDATE_INTERVAL_MILLIS = 3_000L

        /** Distance to the next station at which we switch from the far to the near interval. */
        private const val NEAR_DISTANCE_THRESHOLD_METERS = 350
    }
}
