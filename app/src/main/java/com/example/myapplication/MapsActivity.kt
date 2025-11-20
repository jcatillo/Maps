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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMapsBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val LOCATION_PERMISSION_REQUEST_CODE = 1

    private val markedMarkers = ArrayList<Marker>()
    private var isMarkingEnabled = false
    private var lastKnownLocation: Location? = null
    private var currentPolyline: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
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
                isMarkingEnabled = !isMarkingEnabled
                val message = if (isMarkingEnabled) "Marking enabled. Tap map to add, tap marker to remove." else "Marking disabled."
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

        mMap.setOnMapClickListener { latLng ->
            if (isMarkingEnabled) {
                val marker = mMap.addMarker(MarkerOptions().position(latLng).title("Marked Location"))
                marker?.let { markedMarkers.add(it) }
            }
        }

        mMap.setOnMarkerClickListener { marker ->
            if (isMarkingEnabled) {
                if (markedMarkers.contains(marker)) {
                    markedMarkers.remove(marker)
                    marker.remove()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        enableMyLocation()
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val locationResult = fusedLocationClient.lastLocation
                locationResult.addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        lastKnownLocation = task.result
                        if (lastKnownLocation != null) {
                            mMap.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(
                                        lastKnownLocation!!.latitude,
                                        lastKnownLocation!!.longitude
                                    ), 15f
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun drawPath() {
        // Remove the previous path if it exists
        currentPolyline?.remove()
        currentPolyline = null

        if (lastKnownLocation == null) {
            Toast.makeText(this, "Current location unknown", Toast.LENGTH_SHORT).show()
            getDeviceLocation() // Try to fetch it
            return
        }
        if (markedMarkers.isEmpty()) {
            Toast.makeText(this, "No locations marked", Toast.LENGTH_SHORT).show()
            return
        }

        // Identify the destination.
        // For simplicity and robustness, let's just pick the last marker added as the "final destination"
        // and let Google optimize the visit order of all other markers as waypoints.
        // Alternatively, finding the furthest marker is a good heuristic.
        
        var furthestMarker: LatLng? = null
        var maxDist = 0f
        val startLatLng = LatLng(lastKnownLocation!!.latitude, lastKnownLocation!!.longitude)

        for (marker in markedMarkers) {
            val results = FloatArray(1)
            Location.distanceBetween(startLatLng.latitude, startLatLng.longitude, marker.position.latitude, marker.position.longitude, results)
            if (results[0] > maxDist) {
                maxDist = results[0]
                furthestMarker = marker.position
            }
        }

        // Destination is the furthest marker to give the optimization algorithm a good "direction"
        val destination = furthestMarker!!
        
        // All other markers are waypoints
        val waypoints = ArrayList<LatLng>()
        for (marker in markedMarkers) {
            if (marker.position != destination) {
                waypoints.add(marker.position)
            }
        }

        // Construct API Request
        val origin = startLatLng
        val url = getDirectionsUrl(origin, destination, waypoints)

        // Fetch and Draw Route on Background Thread
        thread {
            try {
                val result = downloadUrl(url)
                val directionsData = JSONObject(result)
                val routes = directionsData.getJSONArray("routes")

                if (routes.length() > 0) {
                    val route = routes.getJSONObject(0)
                    val overviewPolyline = route.getJSONObject("overview_polyline")
                    val encodedString = overviewPolyline.getString("points")
                    val decodedPath = decodePoly(encodedString)

                    runOnUiThread {
                        // Double check and remove previous before adding new one to avoid race conditions
                        currentPolyline?.remove()
                        currentPolyline = mMap.addPolyline(
                            PolylineOptions()
                                .addAll(decodedPath)
                                .color(Color.BLUE)
                                .width(10f)
                        )
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "No route found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error fetching route: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getDirectionsUrl(origin: LatLng, dest: LatLng, waypoints: List<LatLng>): String {
        val str_origin = "origin=${origin.latitude},${origin.longitude}"
        val str_dest = "destination=${dest.latitude},${dest.longitude}"
        val sensor = "sensor=false"
        // Changed mode to walking
        val mode = "mode=walking"

        val parameters = StringBuilder("$str_origin&$str_dest&$sensor&$mode")
        
        if (waypoints.isNotEmpty()) {
            // Ensure optimize:true is enabled for walking waypoints
            parameters.append("&waypoints=optimize:true|")
            for (i in waypoints.indices) {
                val point = waypoints[i]
                parameters.append("${point.latitude},${point.longitude}")
                if (i < waypoints.size - 1) {
                    parameters.append("|")
                }
            }
        }

        val apiKey = getApiKey()
        parameters.append("&key=$apiKey")

        return "https://maps.googleapis.com/maps/api/directions/json?$parameters"
    }

    private fun getApiKey(): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            appInfo.metaData.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun downloadUrl(strUrl: String): String {
        var data = ""
        var iStream: java.io.InputStream? = null
        var urlConnection: HttpURLConnection? = null
        try {
            val url = URL(strUrl)
            urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.connect()

            iStream = urlConnection.inputStream
            val br = BufferedReader(InputStreamReader(iStream))
            val sb = StringBuilder()
            var line = br.readLine()
            while (line != null) {
                sb.append(line)
                line = br.readLine()
            }
            data = sb.toString()
            br.close()
        } catch (e: Exception) {
            throw e
        } finally {
            iStream?.close()
            urlConnection?.disconnect()
        }
        return data
    }

    private fun decodePoly(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }
        return poly
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
            }
        }
    }
}
