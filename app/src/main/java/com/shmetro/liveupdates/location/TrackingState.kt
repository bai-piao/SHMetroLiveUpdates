package com.shmetro.liveupdates.location

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

    fun reset() {
        lastLineId = null
        lastProgress = null
        lastDirectionForward = true
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
