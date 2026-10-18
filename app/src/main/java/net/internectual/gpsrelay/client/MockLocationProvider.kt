package net.internectual.gpsrelay.client

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import net.internectual.gpsrelay.protocol.Fix

/**
 * Feeds relayed fixes into the system as test-provider locations. Requires GPS Relay to be chosen
 * under Developer options → "Select mock location app".
 */
class MockLocationProvider(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val active = mutableListOf<String>()

    val isActive: Boolean get() = active.isNotEmpty()

    /** Registers the test providers. Returns null on success or an error message. */
    fun start(): String? {
        if (isActive) return null
        val wanted = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        var error: String? = null
        for (p in wanted) {
            try {
                runCatching { lm.removeTestProvider(p) }
                addProvider(p)
                lm.setTestProviderEnabled(p, true)
                active += p
            } catch (e: SecurityException) {
                error = "Select GPS Relay as the mock location app in Developer options"
                break
            } catch (e: IllegalArgumentException) {
                // Provider not supported as a test provider on this device; skip it.
            }
        }
        return if (active.contains(LocationManager.GPS_PROVIDER)) null else error ?: "Couldn't register the GPS test provider"
    }

    // ProviderProperties constants are compile-time ints equal to the old Criteria values.
    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun addProvider(p: String) {
        val gps = p == LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= 31) {
            lm.addTestProvider(
                p,
                ProviderProperties.Builder()
                    .setHasAltitudeSupport(gps)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setHasSatelliteRequirement(gps)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build(),
            )
        } else {
            lm.addTestProvider(
                p, false, gps, false, false, gps, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE,
            )
        }
    }

    fun push(fix: Fix) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val failed = mutableListOf<String>()
        for (p in active) {
            val loc = Location(p).apply {
                latitude = fix.lat
                longitude = fix.lon
                fix.alt?.let { altitude = it }
                accuracy = fix.acc ?: 5f
                fix.speed?.let { speed = it }
                fix.bearing?.let { bearing = it }
                // Use the local clock: the client may have no network time and drift from the server.
                time = now
                elapsedRealtimeNanos = elapsed
                if (Build.VERSION.SDK_INT >= 26) {
                    verticalAccuracyMeters = (fix.acc ?: 5f) * 1.5f
                    if (fix.speed != null) speedAccuracyMetersPerSecond = 0.5f
                    if (fix.bearing != null) bearingAccuracyDegrees = 10f
                }
            }
            try {
                lm.setTestProviderLocation(p, loc)
            } catch (e: SecurityException) {
                failed += p
            } catch (e: IllegalArgumentException) {
                failed += p
            }
        }
        active.removeAll(failed)
    }

    fun stop() {
        for (p in active) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        active.clear()
    }
}
