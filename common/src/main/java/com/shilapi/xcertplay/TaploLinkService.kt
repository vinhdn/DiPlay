package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * DiPlay's end of the taplo link: the taplo companion app (samples/taplo), which a launcher or
 * cluster service embeds on the instrument cluster, binds here and hands over the Surface of its
 * view. DiPlay then decodes the dashboard map (CarPlay stream 111) straight into it with its own
 * mirror decoder, across the process boundary, as it does for the centre card.
 *
 * Unlike [MapEmbedService] this needs no SurfaceControlViewHost, so it works from Android 9.
 * Binding needs [PERMISSION], a signature permission: the companion must be signed with DiPlay's
 * key. The picture is only shared while the driver allows launcher map sharing in DiPlay.
 *
 * Protocol (Messenger, every message from the companion carries replyTo):
 * - [MSG_REGISTER] / [MSG_UNREGISTER]: start/stop receiving [MSG_STATE].
 * - [MSG_SURFACE]: [KEY_SURFACE], [KEY_WIDTH], [KEY_HEIGHT]; draw here. Optionally the part of the
 *   view the host app covers, in percent per edge ([KEY_OBSCURED_TOP], [KEY_OBSCURED_BOTTOM],
 *   [KEY_OBSCURED_LEFT], [KEY_OBSCURED_RIGHT]): the map stream's safe area leaves it out, so the
 *   iPhone keeps the car and the guidance in the visible part.
 * - [MSG_SURFACE_GONE]: the surface is about to be destroyed.
 * - [MSG_STATE] (DiPlay to companion): [KEY_ENABLED], [KEY_STREAM_ACTIVE], [KEY_STREAM_ASPECT].
 *
 * Main thread only.
 */
class TaploLinkService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private val clients = LinkedHashMap<IBinder, Messenger>()
    private var surface: Surface? = null
    private var surfaceOwner: IBinder? = null
    private var stopObservingSharing: (() -> Unit)? = null
    private val ownerDeath = IBinder.DeathRecipient { main.post { release("the taplo app died") } }
    private val streamListener: (Boolean) -> Unit = { pushState() }
    private val aspectListener: () -> Unit = { pushState() }
    /** Last CarPlay guidance sent, to send [MSG_NAV] only when it changes. */
    private var lastNavSig: String? = null
    private val navTick = object : Runnable {
        override fun run() {
            pushNav()
            main.postDelayed(this, NAV_POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MapMirrors.addStreamListener(streamListener)
        MapMirrors.addAspectListener(aspectListener)
        stopObservingSharing = AirPlayPersistence.observeLauncherMapSharing(this) { enabled ->
            main.post {
                if (!enabled) release("launcher map sharing was turned off")
                pushState()
            }
        }
        // CarPlay guidance + ETA is not in the dashboard video; poll it and push it to the taplo
        // companion (the cluster) over MSG_NAV, which draws its own guidance band. snapshot() also
        // refreshes time-dependent guidance for the navigation widget.
        main.post(navTick)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        clients.clear()
        release("the taplo app unbound")
        return false
    }

    override fun onDestroy() {
        MapMirrors.removeStreamListener(streamListener)
        MapMirrors.removeAspectListener(aspectListener)
        main.removeCallbacks(navTick)
        stopObservingSharing?.invoke()
        stopObservingSharing = null
        clients.clear()
        release("the taplo link closed")
        super.onDestroy()
    }

    private fun handle(message: Message) {
        when (message.what) {
            MSG_REGISTER -> message.replyTo?.let { client ->
                clients[client.binder] = client
                send(client, state())
                navState()?.let { client.send(Message.obtain(null, MSG_NAV).apply { data = it }) }
            }
            MSG_UNREGISTER -> message.replyTo?.let { clients.remove(it.binder) }
            MSG_SURFACE -> {
                val data = message.data ?: return
                data.classLoader = Surface::class.java.classLoader
                @Suppress("DEPRECATION")
                val handed = data.getParcelable<Surface>(KEY_SURFACE) ?: return
                obscured(data)?.let { declared ->
                    if (declared != AirPlayPersistence.loadTaploAppObscured(this)) {
                        AirPlayPersistence.saveTaploAppObscured(this, declared)
                        Log.i(TAG, "taplo app covers $declared percent; the map's safe area follows")
                    }
                }
                attach(handed, data.getInt(KEY_WIDTH), data.getInt(KEY_HEIGHT), message.replyTo?.binder)
            }
            MSG_SURFACE_GONE -> release("the taplo app's surface is going away")
        }
    }

    private fun attach(handed: Surface, width: Int, height: Int, owner: IBinder?) {
        release("the taplo app handed over a new surface")
        if (width > 0 && height > 0 && AirPlayPersistence.loadTaploAppSize(this) != (width to height)) {
            // The iPhone is told the stream size when CarPlay connects and keeps it for the session.
            AirPlayPersistence.saveTaploAppSize(this, width, height)
            Log.i(TAG, "taplo app is ${width}x$height; the virtual map stream follows from the next connection")
        }
        if (!AirPlayPersistence.loadLauncherMapSharing(this)) {
            Log.i(TAG, "taplo app attached, but launcher map sharing is off")
            handed.release()
            pushState()
            return
        }
        surface = handed
        surfaceOwner = owner
        runCatching { owner?.linkToDeath(ownerDeath, 0) }
        MapMirrors.set(MapMirrors.TAPLO, handed)
        Log.i(TAG, "dashboard map draws into the taplo app (${width}x$height)")
        pushState()
    }

    /** A surface loss, never the CarPlay session ending. */
    private fun release(reason: String) {
        val current = surface ?: return
        surface = null
        surfaceOwner?.let { runCatching { it.unlinkToDeath(ownerDeath, 0) } }
        surfaceOwner = null
        Log.i(TAG, "releasing the taplo app's surface because $reason")
        MapMirrors.set(MapMirrors.TAPLO, null)
        current.release()
    }

    private fun state(): Bundle = Bundle().apply {
        putBoolean(KEY_ENABLED, AirPlayPersistence.loadLauncherMapSharing(this@TaploLinkService))
        putBoolean(KEY_STREAM_ACTIVE, MapMirrors.streamActive)
        putFloat(KEY_STREAM_ASPECT, MapMirrors.streamAspect.toFloat())
    }

    private fun pushState() {
        if (clients.isEmpty()) return
        val state = state()
        clients.values.toList().forEach { send(it, state) }
    }

    private fun send(client: Messenger, state: Bundle) {
        try {
            client.send(Message.obtain(null, MSG_STATE).apply { data = Bundle(state) })
        } catch (_: RemoteException) {
            clients.remove(client.binder)
        }
    }

    /** The current CarPlay guidance as a [MSG_NAV] bundle, or null when no route is active. */
    private fun navState(): Bundle? {
        val s = CarPlayGlance.snapshot()
        return if (s.connected && s.maneuverType != null) navBundle(s) else null
    }

    private fun navBundle(s: CarPlayGlance.Snapshot): Bundle = Bundle().apply {
        putBoolean(KEY_NAV_ACTIVE, true)
        putInt(KEY_MANEUVER_TYPE, s.maneuverType ?: -1)
        putInt(KEY_DRIVING_SIDE, s.drivingSide)
        putInt(KEY_DISTANCE_M, s.distanceMeters)
        putString(KEY_ROAD, s.road)
        putLong(KEY_ARRIVAL, s.arrivalEpochSeconds ?: -1L)
        putLong(KEY_REMAINING_S, s.remainingSeconds ?: -1L)
        putLong(KEY_REMAINING_M, s.remainingMeters ?: -1L)
    }

    /** Sends [MSG_NAV] to the companions, only when the guidance changed. */
    private fun pushNav() {
        if (clients.isEmpty()) { lastNavSig = null; return }
        val s = CarPlayGlance.snapshot()
        val active = s.connected && s.maneuverType != null
        val sig = if (active)
            "${s.maneuverType}|${s.drivingSide}|${s.distanceMeters}|${s.road}|${s.arrivalEpochSeconds}|${s.remainingSeconds}|${s.remainingMeters}"
            else "off"
        if (sig == lastNavSig) return
        lastNavSig = sig
        val data = if (active) navBundle(s) else Bundle().apply { putBoolean(KEY_NAV_ACTIVE, false) }
        clients.values.toList().forEach { client ->
            try {
                client.send(Message.obtain(null, MSG_NAV).apply { this.data = Bundle(data) })
            } catch (_: RemoteException) {
                clients.remove(client.binder)
            }
        }
    }

    companion object {
        private const val TAG = "DiPlay-Taplo"

        /** Bind with this action; the package differs between release and debug builds. */
        const val ACTION = "com.shihab.diplay.action.TAPLO_LINK"

        // Companion -> DiPlay.
        const val MSG_REGISTER = 1
        const val MSG_UNREGISTER = 2
        const val MSG_SURFACE = 3 // KEY_SURFACE, KEY_WIDTH, KEY_HEIGHT
        const val MSG_SURFACE_GONE = 4

        // DiPlay -> companion.
        const val MSG_STATE = 10 // KEY_ENABLED, KEY_STREAM_ACTIVE, KEY_STREAM_ASPECT
        const val MSG_NAV = 11 // CarPlay guidance + ETA (KEY_NAV_* below)

        /** How often CarPlay guidance is polled and pushed. */
        private const val NAV_POLL_MS = 500L

        const val KEY_SURFACE = "surface"
        const val KEY_OBSCURED_TOP = "obscuredTopPercent"
        const val KEY_OBSCURED_BOTTOM = "obscuredBottomPercent"
        const val KEY_OBSCURED_LEFT = "obscuredLeftPercent"
        const val KEY_OBSCURED_RIGHT = "obscuredRightPercent"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_ENABLED = "enabled"
        const val KEY_STREAM_ACTIVE = "streamActive"
        const val KEY_STREAM_ASPECT = "streamAspect"

        // MSG_NAV payload: CarPlay guidance from CarPlayGlance (Apple RouteGuidanceManeuverType).
        const val KEY_NAV_ACTIVE = "navActive"
        const val KEY_MANEUVER_TYPE = "maneuverType"
        const val KEY_DRIVING_SIDE = "drivingSide"
        const val KEY_DISTANCE_M = "distanceMeters"
        const val KEY_ROAD = "road"
        const val KEY_ARRIVAL = "arrivalEpochSeconds"
        const val KEY_REMAINING_S = "remainingSeconds"
        const val KEY_REMAINING_M = "remainingMeters"

        /**
         * The covered edges a [MSG_SURFACE] declares, or null when it declares none. Each edge is
         * 0..45 % and opposite edges leave at least 20 % visible, so a bad value cannot hide the map.
         */
        fun obscured(data: Bundle): AirPlayInsets? {
            val keys = listOf(KEY_OBSCURED_TOP, KEY_OBSCURED_BOTTOM, KEY_OBSCURED_LEFT, KEY_OBSCURED_RIGHT)
            if (keys.none { data.containsKey(it) }) return null
            val (top, bottom, left, right) = keys.map { data.getInt(it, 0).coerceIn(0, 45) }
            if (top + bottom > 80 || left + right > 80) return null
            return AirPlayInsets(top = top, bottom = bottom, left = left, right = right)
        }

        /** The virtual stream size for a taplo view of [width]x[height]; see [SecondDisplayPolicy.streamSize]. */
        fun streamSize(width: Int, height: Int): Pair<Int, Int>? = SecondDisplayPolicy.streamSize(width, height)
    }
}
