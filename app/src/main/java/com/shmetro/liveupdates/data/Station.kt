package com.shmetro.liveupdates.data

/**
 * A single metro station.
 *
 * [lat]/[lng] are WGS84 decimal degrees. Coordinates in [MetroLines] come from OpenStreetMap
 * community mapping, not first-party surveyed data — good enough to tell which station a rider
 * is nearest to (stations are typically 1-3km apart), but verify against an authoritative source
 * before relying on them for anything precision-sensitive.
 */
data class Station(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val lat: Double,
    val lng: Double,
)

/** An ordered, directional metro line: [stations] runs from one terminus to the other. */
data class MetroLine(
    val id: String,
    val nameZh: String,
    val colorArgb: Int,
    val stations: List<Station>,
)
