package net.internectual.gpsrelay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.internectual.gpsrelay.R
import net.internectual.gpsrelay.protocol.Destination
import net.internectual.gpsrelay.protocol.Fix
import net.internectual.gpsrelay.state.RelayState
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/** Live OpenStreetMap view: current position, accuracy, trail and destination. */
@Composable
fun MapScreen() {
    val ctx = LocalContext.current
    val fix by RelayState.fix.collectAsStateWithLifecycle()
    val trail by RelayState.trail.collectAsStateWithLifecycle()
    val lastDest by RelayState.lastDestination.collectAsStateWithLifecycle()
    val focus by RelayState.mapFocus.collectAsStateWithLifecycle()
    var follow by rememberSaveable { mutableStateOf(true) }
    var shownDest by remember { mutableStateOf<Destination?>(null) }

    val map = remember { MapLayers(ctx) }
    map.onUserPan = { follow = false }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.view.onResume()
                Lifecycle.Event.ON_PAUSE -> map.view.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.view.onDetach()
        }
    }

    LaunchedEffect(lastDest) { if (focus == null) shownDest = lastDest }
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        shownDest = f
        if (f.hasCoords) {
            follow = false
            map.view.controller.setZoom(16.0)
            map.view.controller.animateTo(GeoPoint(f.lat!!, f.lon!!))
        }
        RelayState.mapFocus.value = null
    }
    LaunchedEffect(fix, trail, shownDest, follow) {
        map.update(fix, trail, shownDest)
        val f = fix
        if (f != null && (follow || !map.centeredOnce)) {
            if (!map.centeredOnce) map.view.controller.setZoom(16.0)
            map.view.controller.animateTo(GeoPoint(f.lat, f.lon))
            map.centeredOnce = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map.view }, modifier = Modifier.fillMaxSize())

        fix?.let { f ->
            Card(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Text(
                    listOfNotNull(
                        f.speed?.let { "%.0f km/h".format(Locale.US, it * 3.6f) },
                        f.acc?.let { "±%.0f m".format(Locale.US, it) },
                        f.sats?.let { "$it sats" },
                    ).joinToString(" · ").ifEmpty { "GPS" },
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        shownDest?.let { d ->
            Card(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    "Destination: " + d.describe(),
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { follow = !follow },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = 40.dp),
            icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
            text = { Text(if (follow) "Following" else "Follow me") },
            containerColor = if (follow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** Holds the osmdroid MapView and its overlays across recompositions. */
private class MapLayers(ctx: Context) {
    var centeredOnce = false
    var onUserPan: () -> Unit = {}

    @SuppressLint("ClickableViewAccessibility")
    val view = MapView(ctx).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        minZoomLevel = 3.0
        controller.setZoom(4.0)
        isTilesScaledToDpi = true
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_MOVE) onUserPan()
            false
        }
    }

    private val trailLine = Polyline(view).apply {
        outlinePaint.color = Color.argb(200, 25, 118, 210)
        outlinePaint.strokeWidth = 8f
        setOnClickListener { _, _, _ -> false }
    }
    private val accuracy = Polygon(view).apply {
        fillPaint.color = Color.argb(40, 25, 118, 210)
        outlinePaint.color = Color.argb(120, 25, 118, 210)
        outlinePaint.strokeWidth = 2f
        setOnClickListener { _, _, _ -> false }
    }
    private val position = Marker(view).apply {
        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_nav_arrow)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        isFlat = true
        setInfoWindow(null)
        title = "Current position"
    }
    private val destination = Marker(view).apply {
        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_destination)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }

    init {
        view.overlays.add(CopyrightOverlay(ctx))
        view.overlays.add(trailLine)
        view.overlays.add(accuracy)
    }

    fun update(fix: Fix?, trail: List<Pair<Double, Double>>, dest: Destination?) {
        trailLine.setPoints(trail.map { GeoPoint(it.first, it.second) })

        view.overlays.remove(position)
        if (fix != null) {
            val p = GeoPoint(fix.lat, fix.lon)
            accuracy.points = Polygon.pointsAsCircle(p, (fix.acc ?: 0f).toDouble().coerceAtLeast(1.0))
            position.position = p
            // osmdroid rotates markers counter-clockwise; GPS bearing is clockwise from north.
            position.rotation = -(fix.bearing ?: 0f)
            view.overlays.add(position)
        } else {
            accuracy.points = emptyList()
        }

        view.overlays.remove(destination)
        if (dest?.hasCoords == true) {
            destination.position = GeoPoint(dest.lat!!, dest.lon!!)
            destination.title = dest.label ?: dest.query ?: "Destination"
            destination.snippet = dest.describe()
            view.overlays.add(destination)
        }
        view.invalidate()
    }
}
