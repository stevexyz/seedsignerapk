package com.seedsigner.app

import com.seedsigner.app.models.SettingsQr
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Unit checks for the 1:1 SettingsQR parser (upstream Settings.parse_settingsqr). */
class SettingsQrTest {

    @Test
    fun `parses basic v1 payload`() {
        val (name, updates) = SettingsQr.parse("settings::v1 name=My_Config network=T denom=btc qr_density=L")
        assertEquals("My Config", name)
        assertEquals("T", updates["network"])
        assertEquals("btc", updates["denomination"])
        assertEquals("L", updates["qr_density"])
    }

    @Test
    fun `multi-value and int values`() {
        val (_, updates) = SettingsQr.parse("settings::v1 sigs=ss,ms scripts=nat")
        assertEquals(listOf("ss", "ms"), updates["sig_types"])
    }

    @Test
    fun `unrecognized attrs ignored, invalid values raise`() {
        val (_, updates) = SettingsQr.parse("settings::v1 bogus=1 network=M")
        assertEquals(mapOf("network" to "M"), updates)
        try {
            SettingsQr.parse("settings::v1 network=X")
            fail("expected invalid value exception")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun `rejects bad version and prefix`() {
        try { SettingsQr.parse("settings::v2 network=M"); fail() } catch (e: Exception) {}
        try { SettingsQr.parse("nonsense"); fail() } catch (e: Exception) {}
        try { SettingsQr.parse("settings::v1 network="); fail() } catch (e: Exception) {}
    }
}
