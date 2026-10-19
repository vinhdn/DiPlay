package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The dashboard map, its second-display output and launcher sharing are on until the driver turns them off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MapSharingDefaultsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun fresh() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun mapOutputsAreOnForAFreshInstall() {
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
        assertTrue(AirPlayPersistence.loadSecondDisplayEnabled(app))
        assertTrue(AirPlayPersistence.loadLauncherMapSharing(app))
        assertEquals(SecondDisplayPolicy.AUTO, AirPlayPersistence.loadSecondDisplayId(app))
    }

    @Test fun anExplicitOffIsKept() {
        AirPlayPersistence.saveClusterMapEnabled(app, false)
        AirPlayPersistence.saveSecondDisplayEnabled(app, false)
        AirPlayPersistence.saveLauncherMapSharing(app, false)
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        assertFalse(AirPlayPersistence.loadSecondDisplayEnabled(app))
        assertFalse(AirPlayPersistence.loadLauncherMapSharing(app))
    }
}
