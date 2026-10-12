package com.diplay.taplo

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The cluster screen: DiPlay's live dashboard map, or a note saying why there is none.
 *
 * DiPlay decodes the map straight into this view's surface (TaploLinkService); this activity only
 * hands the surface over, crops the picture to fill and shows DiPlay's state.
 *
 * The picture is always a [PREVIEW_WIDTH]x[PREVIEW_HEIGHT] frame, the slot of the taplo host app:
 * DiPlay is told that size (so the iPhone streams it), the decoder draws at that size, and the frame
 * is scaled to fit and centred when this window is larger, so a phone shows the same preview.
 */
class TaploActivity : Activity() {
    private lateinit var frame: FrameLayout
    private lateinit var picture: TextureView
    private lateinit var status: TextView

    private var surface: Surface? = null
    private var sent: Triple<Surface, Int, Int>? = null
    private var service: Messenger? = null
    private var bound = false

    private var stateKnown = false
    private var enabled = false
    private var streamActive = false
    private var streamAspect = 8f / 3

    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_STATE) msg.data?.let(::onState)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            sent = null
            stateKnown = false
            send(Message.obtain(null, MSG_REGISTER))
            sendSurface()
            render()
        }

        // DiPlay's process went away; Android binds again by itself when it comes back.
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stateKnown = false
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildViews())
        render()
    }

    override fun onStart() {
        super.onStart()
        bound = bindDiPlay()
        render()
    }

    override fun onStop() {
        if (bound) {
            send(Message.obtain(null, MSG_UNREGISTER))
            unbindService(connection)
            bound = false
        }
        service = null
        sent = null
        super.onStop()
    }

    private fun bindDiPlay(): Boolean {
        val candidates = packageManager.queryIntentServices(Intent(ACTION), 0)
        // Prefer the release package when several DiPlay builds are installed.
        val info = candidates.map { it.serviceInfo }.sortedBy { if (it.packageName == RELEASE_PACKAGE) 0 else 1 }
            .firstOrNull() ?: run {
            Log.w(TAG, "DiPlay is not installed")
            return false
        }
        val intent = Intent(ACTION).setClassName(info.packageName, info.name)
        return try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            // Signed with another key than DiPlay: the signature permission is not granted.
            Log.w(TAG, "Not allowed to bind to ${info.packageName}: ${e.message}")
            false
        }
    }

    private fun buildViews(): View {
        picture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                    surface = Surface(texture)
                    applyCrop()
                    sendSurface()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                    applyCrop()
                }

                // Told first, and released late, so DiPlay's decoder lets go of a live surface.
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    send(Message.obtain(null, MSG_SURFACE_GONE))
                    val old = surface
                    surface = null
                    sent = null
                    main.postDelayed({ old?.release(); texture.release() }, RELEASE_DELAY_MILLIS)
                    return false
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    // The first frame proves the picture is coming, not the state alone.
                    if (status.visibility == View.VISIBLE && enabled && streamActive) status.visibility = View.GONE
                }
            }
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 18, 21))
        }
        frame = FrameLayout(this).apply {
            addView(picture, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(frame, FrameLayout.LayoutParams(PREVIEW_WIDTH, PREVIEW_HEIGHT, Gravity.CENTER))
            // Fit the fixed-shape frame into whatever the host gives this window.
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
                if (r - l == orr - ol && b - t == ob - ot) return@addOnLayoutChangeListener
                val (w, h) = fitPreview(r - l, b - t)
                post { frame.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER) }
            }
        }
    }

    private fun onState(state: Bundle) {
        val wasEnabled = if (stateKnown) enabled else null
        stateKnown = true
        enabled = state.getBoolean(KEY_ENABLED)
        streamActive = state.getBoolean(KEY_STREAM_ACTIVE)
        streamAspect = state.getFloat(KEY_STREAM_ASPECT, streamAspect).takeIf { it.isFinite() && it > 0f } ?: streamAspect
        applyCrop()
        // DiPlay only takes the surface while sharing is allowed, so offer it again once it is.
        if (enabled && wasEnabled == false) sendSurface(force = true)
        render()
    }

    private fun render() {
        val text = when {
            !bound -> "DiPlay is not installed, or this app is not signed with DiPlay's key"
            service == null || !stateKnown -> "Connecting to DiPlay…"
            !enabled -> "Turn on \"Share the live map with other launchers\" in DiPlay"
            !streamActive -> "DiPlay · waiting for the CarPlay map"
            // Streaming: the note goes away with the first frame (onSurfaceTextureUpdated).
            else -> return
        }
        status.text = text
        status.visibility = View.VISIBLE
    }

    /**
     * Keeps the stream's shape and fits it inside the view, never cutting anything off: turn
     * arrows and street names sit near the stream's edges. A stream of the preview's own shape
     * (DiPlay asks for one) fills it exactly; a wider one, set by a cluster or second display,
     * is letterboxed.
     */
    private fun applyCrop() {
        val width = picture.width
        val height = picture.height
        if (width <= 0 || height <= 0) return
        val (scaleX, scaleY) = fitScale(width, height, streamAspect)
        picture.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }

    private fun sendSurface(force: Boolean = false) {
        val current = surface ?: return
        if (service == null) return
        // Always the preview size, whatever this window measures: that is what DiPlay asks the iPhone for.
        val handed = Triple(current, PREVIEW_WIDTH, PREVIEW_HEIGHT)
        if (!force && handed == sent) return
        sent = handed
        send(Message.obtain(null, MSG_SURFACE).apply {
            data = Bundle().apply {
                putParcelable(KEY_SURFACE, current)
                putInt(KEY_WIDTH, PREVIEW_WIDTH)
                putInt(KEY_HEIGHT, PREVIEW_HEIGHT)
                // What the taplo host app draws over: the iPhone keeps the car and guidance out of it.
                putInt(KEY_OBSCURED_TOP, OBSCURED_TOP_PERCENT)
                putInt(KEY_OBSCURED_BOTTOM, OBSCURED_BOTTOM_PERCENT)
                putInt(KEY_OBSCURED_LEFT, OBSCURED_LEFT_PERCENT)
                putInt(KEY_OBSCURED_RIGHT, OBSCURED_RIGHT_PERCENT)
            }
        })
    }

    private fun send(message: Message) {
        message.replyTo = incoming
        try {
            service?.send(message)
        } catch (e: RemoteException) {
            Log.w(TAG, "DiPlay went away: ${e.message}")
            service = null
        }
    }

    companion object {
        /** The taplo host app's slot; the preview and the requested stream are always this size. */
        const val PREVIEW_WIDTH = 960
        const val PREVIEW_HEIGHT = 720

        /**
         * The share of each edge the taplo host app covers with its own UI (status row at the top,
         * a band at the bottom). Declared to DiPlay as the map's obscured area, so the car marker is
         * centred in the visible 20–90 % band and turn guidance is not drawn under the cover.
         */
        const val OBSCURED_TOP_PERCENT = 20
        const val OBSCURED_BOTTOM_PERCENT = 10
        const val OBSCURED_LEFT_PERCENT = 0
        const val OBSCURED_RIGHT_PERCENT = 0

        /** The largest [PREVIEW_WIDTH]:[PREVIEW_HEIGHT] frame that fits [width]x[height]. */
        fun fitPreview(width: Int, height: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0) return PREVIEW_WIDTH to PREVIEW_HEIGHT
            val scale = minOf(width.toFloat() / PREVIEW_WIDTH, height.toFloat() / PREVIEW_HEIGHT)
            return (PREVIEW_WIDTH * scale).toInt() to (PREVIEW_HEIGHT * scale).toInt()
        }

        /** View-space scale that fits a stream of [streamAspect] inside a [width]x[height] view. */
        fun fitScale(width: Int, height: Int, streamAspect: Float): Pair<Float, Float> {
            val viewAspect = width.toFloat() / height
            return if (streamAspect > viewAspect) 1f to viewAspect / streamAspect
                else streamAspect / viewAspect to 1f
        }

        private const val TAG = "DiPlayTaplo"
        private const val RELEASE_PACKAGE = "com.shihab.diplay"
        private const val RELEASE_DELAY_MILLIS = 1_000L

        // DiPlay's protocol (TaploLinkService); keep in sync.
        private const val ACTION = "com.shihab.diplay.action.TAPLO_LINK"
        private const val MSG_REGISTER = 1
        private const val MSG_UNREGISTER = 2
        private const val MSG_SURFACE = 3
        private const val MSG_SURFACE_GONE = 4
        private const val MSG_STATE = 10
        private const val KEY_SURFACE = "surface"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_OBSCURED_TOP = "obscuredTopPercent"
        private const val KEY_OBSCURED_BOTTOM = "obscuredBottomPercent"
        private const val KEY_OBSCURED_LEFT = "obscuredLeftPercent"
        private const val KEY_OBSCURED_RIGHT = "obscuredRightPercent"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STREAM_ACTIVE = "streamActive"
        private const val KEY_STREAM_ASPECT = "streamAspect"
    }
}
