package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.example.myapplication.databinding.ActivityMapsBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.BitmapDescriptorFactory

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMapsBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val LOCATION_PERMISSION_REQUEST_CODE = 1

    private val markedMarkers = ArrayList<Marker>()
    private var lastKnownLocation: Location? = null
    private var currentPolyline: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.maps_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_mark -> {
                markCurrentLocation()
                true
            }
            R.id.action_view_path -> {
                drawPath()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Long press clears all markers + path
        mMap.setOnMapLongClickListener {
            for (marker in markedMarkers) marker.remove()
            markedMarkers.clear()

            currentPolyline?.remove()
            currentPolyline = null

            Toast.makeText(this, "All markers and paths cleared", Toast.LENGTH_SHORT).show()
        }

        // Tap marker to remove it (but do NOT redraw automatically)
        mMap.setOnMarkerClickListener { marker ->
            if (markedMarkers.contains(marker)) {
                markedMarkers.remove(marker)
                marker.remove()
                true
            } else false
        }

        enableMyLocation()
    }

    private fun markCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = location
                    val latLng = LatLng(location.latitude, location.longitude)

                    val marker = mMap.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("Marked Location")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
                    )

                    if (marker != null) {
                        markedMarkers.add(marker)
                        Toast.makeText(this, "Location marked", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Current location unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            enableMyLocation()
        }
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
            getDeviceLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun getDeviceLocation() {
        try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.lastLocation.addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        lastKnownLocation = task.result
                        val loc = task.result!!
                        mMap.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(loc.latitude, loc.longitude), 15f
                            )
                        )
                    } else fallbackLocation()
                }
            }
        } catch (e: SecurityException) {
            fallbackLocation()
        }
    }

    private fun fallbackLocation() {
        val fallback = LatLng(10.2970, 123.8967)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fallback, 15f))
    }

    // Draw a clockwise ordered straight path
    private fun drawPath() {
        currentPolyline?.remove()
        currentPolyline = null

        if (markedMarkers.size < 2) {
            Toast.makeText(this, "Need at least 2 markers", Toast.LENGTH_SHORT).show()
            return
        }

        // Extract LatLng list
        val points = markedMarkers.map { it.position }.toMutableList()

        // Calculate center of all points
        val centerLat = points.map { it.latitude }.average()
        val centerLng = points.map { it.longitude }.average()
        val center = LatLng(centerLat, centerLng)

        // Sort clockwise by angle from center (removes criss-cross)
        points.sortBy { p ->
            Math.atan2(p.longitude - centerLng, p.latitude - centerLat)
        }

        // Draw final polyline
        currentPolyline = mMap.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(8f)
                .color(Color.BLUE)
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        }
    }
}
