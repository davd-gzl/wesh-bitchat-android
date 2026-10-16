package com.bitchat.android.geohash

import android.content.Context
import android.location.Address
import android.location.Geocoder

/**
 * Factory to provide the best available geocoder.
 *
 * Wesh sends no request outside the future Tor-only Internet mode: both the platform geocoder
 * (backed by a network service) and the former OpenStreetMap reverse-geocoding client are excluded.
 */
object GeocoderFactory {
    fun get(context: Context, weshLocalOnly: Boolean = com.bitchat.android.BuildConfig.WESH_LOCAL_ONLY): GeocoderProvider {
        return if (!weshLocalOnly && Geocoder.isPresent()) AndroidGeocoderProvider(context) else NoGeocoderProvider
    }
}

/** Reverse geocoding disabled: callers fall back to the raw geohash. */
object NoGeocoderProvider : GeocoderProvider {
    override suspend fun getFromLocation(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        liveLocationToken: Long?
    ): List<Address> = emptyList()
}
