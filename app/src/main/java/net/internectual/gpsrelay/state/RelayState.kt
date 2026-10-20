package net.internectual.gpsrelay.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.internectual.gpsrelay.protocol.Destination
import net.internectual.gpsrelay.protocol.Fix

enum class ConnState { STOPPED, SEARCHING, CONNECTING, CONNECTED, ERROR }

/** Process-wide live state shared by the services and the UI. */
object RelayState {
    private const val TRAIL_MAX = 600

    val serverRunning = MutableStateFlow(false)
    val serverError = MutableStateFlow<String?>(null)
    val clients = MutableStateFlow<List<String>>(emptyList())
    /** Addresses of TCP clients currently reading the server's NMEA stream. */
    val nmeaClients = MutableStateFlow<List<String>>(emptyList())

    val clientRunning = MutableStateFlow(false)
    val conn = MutableStateFlow(ConnState.STOPPED)
    val connTarget = MutableStateFlow<String?>(null)
    val connError = MutableStateFlow<String?>(null)
    val mockError = MutableStateFlow<String?>(null)

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    /** Local receive time of [fix], for "age" display. */
    val fixReceivedAt = MutableStateFlow(0L)

    private val _trail = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val trail: StateFlow<List<Pair<Double, Double>>> = _trail

    val lastDestination = MutableStateFlow<Destination?>(null)

    /** Set by the Log tab to centre the map on an entry. */
    val mapFocus = MutableStateFlow<Destination?>(null)

    /** Asks an open MainActivity to close (automation). Dropped when no UI is open, so it can't close a later one. */
    val closeUi = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** True while MainActivity is resumed; activities may then be started without restriction. */
    @Volatile var uiVisible = false

    fun onFix(f: Fix) {
        _fix.value = f
        fixReceivedAt.value = System.currentTimeMillis()
        _trail.update { (it + (f.lat to f.lon)).takeLast(TRAIL_MAX) }
    }

    fun clearTrail() {
        _trail.value = emptyList()
    }
}
