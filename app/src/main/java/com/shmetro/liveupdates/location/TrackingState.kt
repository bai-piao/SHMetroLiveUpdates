package com.shmetro.liveupdates.location

import android.os.SystemClock
import com.shmetro.liveupdates.data.MetroLine
import com.shmetro.liveupdates.data.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How far off the line's path a fix can be before we consider the rider "not on this line". */
const val OFF_LINE_THRESHOLD_METERS = 800.0

/** Minimum change in line-progress between fixes needed to (re)decide travel direction. */
const val DIRECTION_HYSTERESIS = 0.03

/** Distance to the next station at or below which we consider the train to have arrived. */
const val ARRIVAL_THRESHOLD_METERS = 150.0

/**
 * Assumed average speed used to interpolate progress between real fixes (e.g. underground,
 * where GPS/BeiDou fixes can be sparse, delayed or entirely absent). There's no per-segment
 * schedule data to calibrate against, so this is a single rough constant — enough to animate
 * progress smoothly rather than have it jump only when a real fix lands.
 */
private const val ASSUMED_SPEED_METERS_PER_SECOND = 11.0

/** Cap on how far [TrackingStateHolder.tick] will interpolate a segment: never claim arrival
 *  from elapsed time alone — that's left to a real fix (or the distance-based [ARRIVAL_THRESHOLD_METERS] check). */
private const val MAX_TIME_INTERPOLATED_FRACTION = 0.95

data class TrackingState(
    val isTracking: Boolean = false,
    val currentLine: MetroLine? = null,
    val currentStation: Station? = null,
    val nextStation: Station? = null,
    val directionForward: Boolean = true,
    val segmentProgressPercent: Int = 0,
    val distanceToNextMeters: Int? = null,
    val offLine: Boolean = false,
    val arriving: Boolean = false,
)

/**
 * Process-wide holder for the latest tracking result, so both the foreground service
 * (producer) and the UI (consumer) observe the same state without binding to the service.
 */
object TrackingStateHolder {
    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private var lastLineId: String? = null
    private var lastProgress: Double? = null
    private var lastDirectionForward: Boolean = true

    /**
     * Anchors the time-based interpolation [tick] does between real fixes: "as of
     * [anchorElapsedRealtimeMillis], we were [anchorFraction] of the way across a
     * [segmentMeters]-long segment". Re-anchored to the latest real fix on every [update], and
     * to a fresh (0-progress) segment whenever [segmentIndex] changes.
     */
    private data class SegmentAnchor(
        val lineId: String,
        val segmentIndex: Int,
        val anchorElapsedRealtimeMillis: Long,
        val anchorFraction: Double,
        val segmentMeters: Double,
    )
    private var segmentAnchor: SegmentAnchor? = null

    fun reset() {
        lastLineId = null
        lastProgress = null
        lastDirectionForward = true
        segmentAnchor = null
        _state.value = TrackingState()
    }

    fun setTracking(isTracking: Boolean) {
        _state.value = _state.value.copy(isTracking = isTracking)
    }

    /**
     * Folds a new [LocateResult] against [line] into the running direction estimate and
     * publishes it.
     *
     * The very first fix — or the first fix after the matched line changes — only seeds
     * [lastProgress] and returns without publishing a current/next station: a single fix on
     * a line has no notion of travel direction, so there's nothing trustworthy to show until
     * a second fix on the *same* line reveals which way progress is moving.
     */
    fun update(line: MetroLine, result: LocateResult) {
        val stations = line.stations
        val previous = if (lastLineId == line.id) lastProgress else null
        lastLineId = line.id
        lastProgress = result.progress
        if (previous == null) {
            _state.value = _state.value.copy(isTracking = true, currentLine = line)
            return
        }

        if (kotlin.math.abs(result.progress - previous) >= DIRECTION_HYSTERESIS) {
            lastDirectionForward = result.progress > previous
        }

        val forward = lastDirectionForward
        val currentIndex: Int
        val nextIndex: Int
        val fractionTowardNext: Double
        if (forward) {
            currentIndex = result.segmentIndex
            nextIndex = (result.segmentIndex + 1).coerceAtMost(stations.lastIndex)
            fractionTowardNext = result.segmentFraction
        } else {
            currentIndex = (result.segmentIndex + 1).coerceAtMost(stations.lastIndex)
            nextIndex = result.segmentIndex
            fractionTowardNext = 1.0 - result.segmentFraction
        }

        val segLine = LineDistance.metersBetween(stations[result.segmentIndex], stations[result.segmentIndex + 1])
        val distanceToNext = when {
            forward -> segLine * (1.0 - result.segmentFraction)
            else -> segLine * result.segmentFraction
        }

        segmentAnchor = SegmentAnchor(
            lineId = line.id,
            segmentIndex = result.segmentIndex,
            anchorElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            anchorFraction = fractionTowardNext,
            segmentMeters = segLine,
        )

        _state.value = _state.value.copy(
            isTracking = true,
            currentLine = line,
            currentStation = stations[currentIndex],
            nextStation = stations[nextIndex],
            directionForward = forward,
            segmentProgressPercent = (fractionTowardNext * 100).toInt().coerceIn(0, 100),
            distanceToNextMeters = distanceToNext.toInt(),
            offLine = result.perpendicularDistanceMeters > OFF_LINE_THRESHOLD_METERS,
            arriving = distanceToNext <= ARRIVAL_THRESHOLD_METERS,
        )
    }

    /**
     * Advances [TrackingState.segmentProgressPercent]/[TrackingState.distanceToNextMeters] by
     * elapsed time since the last real fix, assuming [ASSUMED_SPEED_METERS_PER_SECOND]. Meant to
     * be called on a short timer while tracking so the UI keeps moving between fixes instead of
     * only jumping when one arrives — the common case underground, where satellite fixes are
     * sparse or delayed. Never overwrites a fix-derived state with something less advanced than
     * it, and never claims arrival by itself (capped at [MAX_TIME_INTERPOLATED_FRACTION]).
     */
    fun tick() {
        val anchor = segmentAnchor ?: return
        val current = _state.value
        if (!current.isTracking || current.currentStation == null || current.nextStation == null) return
        if (anchor.segmentMeters <= 0.0) return

        val elapsedSeconds = (SystemClock.elapsedRealtime() - anchor.anchorElapsedRealtimeMillis) / 1000.0
        if (elapsedSeconds <= 0.0) return

        val extraFraction = (ASSUMED_SPEED_METERS_PER_SECOND * elapsedSeconds) / anchor.segmentMeters
        val interpolatedFraction = (anchor.anchorFraction + extraFraction).coerceIn(0.0, MAX_TIME_INTERPOLATED_FRACTION)
        if (interpolatedFraction <= anchor.anchorFraction) return

        val interpolatedDistance = anchor.segmentMeters * (1.0 - interpolatedFraction)
        _state.value = current.copy(
            segmentProgressPercent = (interpolatedFraction * 100).toInt().coerceIn(0, 100),
            distanceToNextMeters = interpolatedDistance.toInt().coerceAtLeast(0),
        )
    }
}

private object LineDistance {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun metersBetween(a: Station, b: Station): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
        return EARTH_RADIUS_METERS * c
    }
}
