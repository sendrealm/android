package com.sendrealm.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SendrealmConfigTest {
    @Test
    fun defaultsMatchAndroidSdkBehavior() {
        val config = SendrealmConfig()

        assertNull(config.baseUrl)
        assertNull(config.externalUserId)
        assertNull(config.userEmail)
        assertEquals("android", config.platform)
        assertEquals("production", config.environment)
        assertTrue(config.autoRequestPermission)
        assertFalse(config.forceRefreshRegistrationToken)
    }

    @Test
    fun settersAreChainableAndNormalizeValues() {
        val config = SendrealmConfig()

        val returned = config
            .setBaseUrl("https://push.example.com///")
            .setExternalUserId("user_123")
            .setUserEmail("person@example.com")
            .setPlatform("react-native")
            .setEnvironment("development")
            .setAutoRequestPermission(false)
            .setForceRefreshRegistrationToken(true)

        assertSame(config, returned)
        assertEquals("https://push.example.com", config.baseUrl)
        assertEquals("user_123", config.externalUserId)
        assertEquals("person@example.com", config.userEmail)
        assertEquals("react-native", config.platform)
        assertEquals("development", config.environment)
        assertFalse(config.autoRequestPermission)
        assertTrue(config.forceRefreshRegistrationToken)
    }

    @Test
    fun blankPlatformFallsBackToAndroid() {
        val config = SendrealmConfig().setPlatform("")

        assertEquals("android", config.platform)
    }

    @Test
    fun unknownEnvironmentFallsBackToProduction() {
        val config = SendrealmConfig().setEnvironment("staging")

        assertEquals("production", config.environment)
    }
}
