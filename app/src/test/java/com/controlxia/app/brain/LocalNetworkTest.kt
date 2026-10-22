package com.controlxia.app.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** http sin cifrar solo a la red de casa (Ollama, LM Studio). */
class LocalNetworkTest {

    @Test
    fun httpsAlwaysAllowed() {
        assertTrue(LocalNetwork.isAllowed("https://api.groq.com/openai/v1"))
        assertTrue(LocalNetwork.isAllowed("https://openrouter.ai/api/v1"))
    }

    @Test
    fun httpOnlyOnTheLocalNetwork() {
        assertTrue(LocalNetwork.isAllowed("http://192.168.1.10:11434/v1"))
        assertTrue(LocalNetwork.isAllowed("http://10.0.0.5:1234/v1"))
        assertTrue(LocalNetwork.isAllowed("http://172.20.3.4:8080/v1"))
        assertTrue(LocalNetwork.isAllowed("http://localhost:11434/v1"))
        assertTrue(LocalNetwork.isAllowed("http://mi-pc.local:11434/v1"))
        assertTrue("Tailscale", LocalNetwork.isAllowed("http://100.101.2.3:11434/v1"))
        assertFalse(LocalNetwork.isAllowed("http://api.groq.com/openai/v1"))
        assertFalse(LocalNetwork.isAllowed("http://8.8.8.8/v1"))
        assertFalse(LocalNetwork.isAllowed("http://172.40.1.1/v1"))
        assertFalse(LocalNetwork.isAllowed("ftp://192.168.1.10/v1"))
        assertFalse(LocalNetwork.isAllowed("no es una url"))
    }
}
