package com.oooshk.witnesscam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Keeps the most recent GPS fix while recording so it can be burned into the video. */
class LocationTracker(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    var last: Location? = null
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { last = it }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        client.lastLocation.addOnSuccessListener { loc -> if (loc != null && last == null) last = loc }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }
}
