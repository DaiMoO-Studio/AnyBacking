package com.anybacking.app

import com.anybacking.app.net.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 018 自架：設定頁填的伺服器位址。 */
class ServerConfigTest {
    @Test fun normalizeAddsSchemeByHost() {
        assertEquals("http://192.168.1.20:8000", ServerConfig.normalize("192.168.1.20:8000"))
        assertEquals("http://100.101.2.3:8000", ServerConfig.normalize(" 100.101.2.3:8000/ "))
        assertEquals("https://karaoke.example.com", ServerConfig.normalize("karaoke.example.com"))
        assertEquals("https://karaoke.example.com:8443", ServerConfig.normalize("HTTPS://Karaoke.Example.com:8443/"))
    }

    @Test fun normalizeRejectsJunk() {
        assertNull(ServerConfig.normalize(""))
        assertNull(ServerConfig.normalize("http://a b"))
        assertNull(ServerConfig.normalize("ftp://example.com"))
        assertNull(ServerConfig.normalize("https://example.com/api"))
    }

    @Test fun httpOnlyOnPrivateNetworks() {
        assertTrue(ServerConfig.allowed("https://example.com"))
        assertTrue(ServerConfig.allowed("http://192.168.0.5:8000"))
        assertTrue(ServerConfig.allowed("http://10.0.0.2"))
        assertTrue(ServerConfig.allowed("http://172.20.1.1"))
        assertTrue(ServerConfig.allowed("http://100.64.0.1"))
        assertTrue(ServerConfig.allowed("http://box.local:8000"))
        assertTrue(ServerConfig.allowed("http://pc.tail1234.ts.net:8000"))
        assertFalse(ServerConfig.allowed("http://example.com"))
        assertFalse(ServerConfig.allowed("http://8.8.8.8"))
        assertFalse(ServerConfig.allowed("http://172.32.0.1"))
        assertFalse(ServerConfig.allowed("http://100.128.0.1"))
    }
}
