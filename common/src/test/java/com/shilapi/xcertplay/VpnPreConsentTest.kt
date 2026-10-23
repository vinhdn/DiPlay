package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VpnPreConsentTest {
    @Test fun anOrdinaryBuildLeavesConsentToTheDialog() {
        assertFalse(VpnPreConsent.grantSelf(RuntimeEnvironment.getApplication()))
    }
}
