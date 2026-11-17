package com.bitzer.rmainspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlAndVersionTest {
    @Test
    fun normalizesWhatPeopleType() {
        assertEquals("http://192.168.1.20:8000/api/v1", ServerUrl.normalize("192.168.1.20:8000"))
        assertEquals("http://192.168.1.20:8000/api/v1", ServerUrl.normalize(" 192.168.1.20:8000/ "))
        assertEquals("http://10.0.2.2:8000/api/v1", ServerUrl.normalize("http://10.0.2.2:8000/api/v1"))
        assertEquals("https://rma-server.example.invalid/api/v1", ServerUrl.normalize("https://rma-server.example.invalid"))
        assertEquals("https://rma-server.example.invalid/api/v1", ServerUrl.normalize("https://rma-server.example.invalid/api/v1/"))
    }

    @Test
    fun rejectsNonsense() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("not a url"))
        assertNull(ServerUrl.normalize("http://"))
    }

    @Test
    fun versionCompare() {
        assertTrue(AppVersion.isAtLeast("0.1.12", "0.1.2"))
        assertTrue(AppVersion.isAtLeast("0.1.0", "0.1"))
        assertFalse(AppVersion.isAtLeast("0.1.9", "0.2.0"))
        assertTrue(AppVersion.isAtLeast("1.0.0", "0.1.0-mock"))
    }
}
