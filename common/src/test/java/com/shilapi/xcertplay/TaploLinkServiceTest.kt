package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class TaploLinkServiceTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun surfaceMessage(width: Int, height: Int, client: Messenger) =
        Message.obtain(null, TaploLinkService.MSG_SURFACE).apply {
            replyTo = client
            data = Bundle().apply {
                putParcelable(TaploLinkService.KEY_SURFACE, Surface(SurfaceTexture(0)))
                putInt(TaploLinkService.KEY_WIDTH, width)
                putInt(TaploLinkService.KEY_HEIGHT, height)
            }
        }

    @Test fun surfaceIsMirroredOnlyWhileSharingIsAllowed() {
        val mirrored = mutableListOf<Pair<String, Surface?>>()
        val previousSink = MapMirrors.sink
        MapMirrors.sink = { key, surface -> mirrored += key to surface }
        val controller = Robolectric.buildService(TaploLinkService::class.java).create()
        val service = controller.get()
        try {
            val states = mutableListOf<Bundle>()
            val client = Messenger(Handler(Looper.getMainLooper()) {
                if (it.what == TaploLinkService.MSG_STATE) states += Bundle(it.data)
                true
            })
            val endpoint = Messenger(service.onBind(Intent(TaploLinkService.ACTION)))

            AirPlayPersistence.saveLauncherMapSharing(service, false)
            idle()
            endpoint.send(Message.obtain(null, TaploLinkService.MSG_REGISTER).apply { replyTo = client })
            idle()
            assertFalse(states.last().getBoolean(TaploLinkService.KEY_ENABLED))

            // Without consent the surface is not taken, but its size is remembered for the stream.
            endpoint.send(surfaceMessage(1920, 720, client))
            idle()
            assertTrue(mirrored.none { it.first == MapMirrors.TAPLO })
            assertEquals(1920 to 720, AirPlayPersistence.loadTaploAppSize(service))

            AirPlayPersistence.saveLauncherMapSharing(service, true)
            idle()
            assertTrue(states.last().getBoolean(TaploLinkService.KEY_ENABLED))
            endpoint.send(surfaceMessage(1920, 720, client))
            idle()
            assertEquals(MapMirrors.TAPLO, mirrored.last().first)
            assertNotNull(mirrored.last().second)
            assertTrue(MapMirrors.any)
            assertFalse("The cluster app must not stand in for the centre card", MapMirrors.launcherShowsMap)

            // Revoking consent detaches the mirror at once.
            AirPlayPersistence.saveLauncherMapSharing(service, false)
            idle()
            assertEquals(MapMirrors.TAPLO to null, mirrored.last())
            assertFalse(MapMirrors.any)

            AirPlayPersistence.saveLauncherMapSharing(service, true)
            idle()
            endpoint.send(surfaceMessage(1920, 720, client))
            idle()
            endpoint.send(Message.obtain(null, TaploLinkService.MSG_SURFACE_GONE).apply { replyTo = client })
            idle()
            assertEquals(MapMirrors.TAPLO to null, mirrored.last())
        } finally {
            controller.destroy()
            MapMirrors.set(MapMirrors.TAPLO, null)
            MapMirrors.sink = previousSink
            AirPlayPersistence.saveLauncherMapSharing(service, false)
        }
    }

    @Test fun declaredCoverIsStoredAndBoundedAndBecomesTheMapSafeArea() {
        fun bundle(vararg pairs: Pair<String, Int>) = Bundle().apply { pairs.forEach { (k, v) -> putInt(k, v) } }
        assertNull(TaploLinkService.obscured(Bundle()))
        val declared = TaploLinkService.obscured(bundle(
            TaploLinkService.KEY_OBSCURED_TOP to 20, TaploLinkService.KEY_OBSCURED_BOTTOM to 10))
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(top = 20, bottom = 10), declared)
        // Out-of-range edges are clamped; a cover leaving under 20 % visible is refused.
        assertEquals(45, TaploLinkService.obscured(bundle(TaploLinkService.KEY_OBSCURED_TOP to 90))?.top)
        assertNull(TaploLinkService.obscured(bundle(
            TaploLinkService.KEY_OBSCURED_TOP to 45, TaploLinkService.KEY_OBSCURED_BOTTOM to 45)))

        // 960x720 with top 20 % / bottom 10 % covered: safe area 144 px top, 72 px bottom, full width,
        // so the car marker sits at the centre of the visible band (x 50 %, y 55 %).
        val config = com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.config(
            960, 720, scalePercent = 100, baseSafeArea = declared!!)
        assertEquals(960 to 720, config.widthPixels to config.heightPixels)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(top = 144, bottom = 72, left = 0, right = 0), config.safeArea)
        assertEquals(50.0 to 55.0, com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.markerPercent(0, 0, declared))
        assertEquals(true, config.safeAreaDrawOutside)

        val controller = Robolectric.buildService(TaploLinkService::class.java).create()
        val service = controller.get()
        try {
            AirPlayPersistence.saveLauncherMapSharing(service, false)
            val client = Messenger(Handler(Looper.getMainLooper()) { true })
            val endpoint = Messenger(service.onBind(Intent(TaploLinkService.ACTION)))
            endpoint.send(surfaceMessage(960, 720, client).apply {
                data.putInt(TaploLinkService.KEY_OBSCURED_TOP, 20)
                data.putInt(TaploLinkService.KEY_OBSCURED_BOTTOM, 10)
            })
            idle()
            assertEquals(declared, AirPlayPersistence.loadTaploAppObscured(service))
            assertEquals(960 to 720, AirPlayPersistence.loadTaploAppSize(service))
        } finally {
            controller.destroy()
        }
    }

    @Test fun streamSizeKeepsTheViewShapeWithinCarPlayLimits() {
        assertEquals(1920 to 720, TaploLinkService.streamSize(1920, 720))
        assertEquals(1920 to 720, TaploLinkService.streamSize(2560, 960))
        assertEquals(600 to 720, TaploLinkService.streamSize(600, 720))
        assertEquals(900 to 1080, TaploLinkService.streamSize(1000, 1200))
        assertNull(TaploLinkService.streamSize(1920, 100))
        assertNull(TaploLinkService.streamSize(0, 720))
    }
}
