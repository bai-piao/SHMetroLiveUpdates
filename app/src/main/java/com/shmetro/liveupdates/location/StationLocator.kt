package com.shmetro.liveupdates.location

import com.shmetro.liveupdates.data.MetroLine
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private const val EARTH_RADIUS_METERS = 6_371_000.0

private data class Vec2(val x: Double, val y: Double)

/**
 * Result of matching a raw GPS fix against a [MetroLine]'s station sequence.
 *
 * [progress] is a continuous coordinate along the line: the integer part is the index of the
 * station the train has most recently departed (in ascending-index direction) and the
 * fractional part is how far along the segment to the next station it is.
 */
data class LocateResult(
    val progress: Double,
    val nearestStationIndex: Int,
    val segmentIndex: Int,
    val segmentFraction: Double,
    val perpendicularDistanceMeters: Double,
)

/** A [LocateResult] together with the [MetroLine] it was matched against. */
data class LineMatch(val line: MetroLine, val result: LocateResult)

/**
 * Snaps a GPS fix onto a metro line by projecting it onto every inter-station segment and
 * keeping the closest one. This is a plain nearest-segment match (no filtering/smoothing),
 * which is enough for "which station is the rider closest to" but will jitter a bit if the
 * rider is standing still near the midpoint between two stations.
 */
object StationLocator {

    fun locate(line: MetroLine, lat: Double, lng: Double): LocateResult {
        val stations = line.stations
        require(stations.size >= 2) { "A line needs at least 2 stations to locate along" }

        // Local flat-earth projection anchored at the line's average latitude — accurate
        // enough over the ~60km span of a single metro line.
        val refLat = stations[stations.size / 2].lat
        val refLatRad = Math.toRadians(refLat)
        fun project(pLat: Double, pLng: Double): Vec2 {
            val x = Math.toRadians(pLng) * cos(refLatRad) * EARTH_RADIUS_METERS
            val y = Math.toRadians(pLat) * EARTH_RADIUS_METERS
            return Vec2(x, y)
        }

        val points = stations.map { project(it.lat, it.lng) }
        val fix = project(lat, lng)

        var bestSegment = 0
        var bestFraction = 0.0
        var bestDistSq = Double.MAX_VALUE

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val abx = b.x - a.x
            val aby = b.y - a.y
            val lenSq = abx * abx + aby * aby
            val t = if (lenSq == 0.0) {
                0.0
            } else {
                val apx = fix.x - a.x
                val apy = fix.y - a.y
                max(0.0, min(1.0, (apx * abx + apy * aby) / lenSq))
            }
            val projX = a.x + abx * t
            val projY = a.y + aby * t
            val dx = fix.x - projX
            val dy = fix.y - projY
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestSegment = i
                bestFraction = t
            }
        }

        val nearestStationIndex = if (bestFraction < 0.5) bestSegment else bestSegment + 1
        return LocateResult(
            progress = bestSegment + bestFraction,
            nearestStationIndex = nearestStationIndex,
            segmentIndex = bestSegment,
            segmentFraction = bestFraction,
            perpendicularDistanceMeters = kotlin.math.sqrt(bestDistSq),
        )
    }

    /** Locates a fix against every line in [lines] and returns the closest match, if any. */
    fun locateBest(lines: List<MetroLine>, lat: Double, lng: Double): LineMatch? =
        lines.map { line -> LineMatch(line, locate(line, lat, lng)) }
            .minByOrNull { it.result.perpendicularDistanceMeters }
}
