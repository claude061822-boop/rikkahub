package me.rerere.rikkahub.di

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthorizationHeaderLoggingTest {
    private data class Observation(val logs: List<String>, val wireHeaders: Map<String, String>)

    // Exercise the production logging factory in a real client and capture HTTP on loopback.
    // No Gateway, external service, configuration, or actual credential is used.
    private fun observe(headers: Map<String, String>): Observation {
        val logs = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val received = executor.submit<Map<String, String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        check(reader.readLine().startsWith("GET /redaction HTTP/1.1"))
                        val wireHeaders = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: error("Incomplete request")
                            if (line.isEmpty()) break
                            wireHeaders[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        wireHeaders
                    }
                }
                val logging = createHeaderLoggingInterceptor { logs.add(it) }
                assertEquals(HttpLoggingInterceptor.Level.HEADERS, logging.level)
                val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                    .callTimeout(5, TimeUnit.SECONDS).addInterceptor(logging).build()
                try {
                    val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/redaction")
                        .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
                    client.newCall(request).execute().use { assertEquals(200, it.code) }
                    return Observation(logs.toList(), received.get(5, TimeUnit.SECONDS))
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        } finally { executor.shutdownNow() }
    }

    private fun assertRedactedAndUnchanged(headers: Map<String, String>) {
        val observation = observe(headers)
        headers.forEach { (name, value) ->
            assertEquals("Wire credential must be preserved", value, observation.wireHeaders[name.lowercase()])
            assertFalse("Credential must not be logged", observation.logs.any { it.contains(value) })
            assertFalse("Token must not be logged even without its scheme",
                observation.logs.any { it.contains(value.substringAfter(' ')) })
            assertTrue("Header must be explicitly marked redacted", observation.logs.contains("$name: ██"))
        }
    }

    @Test fun authorizationIsRedactedAndOriginalBearerReachesWire() {
        assertRedactedAndUnchanged(mapOf("Authorization" to "Bearer SUPER_SECRET_TEST_TOKEN"))
    }

    @Test fun proxyAuthorizationRedactionIsPreserved() {
        assertRedactedAndUnchanged(mapOf("Proxy-Authorization" to "Basic SUPER_SECRET_PROXY_TEST_TOKEN"))
    }

    @Test fun equivalentCredentialHeadersAndMixedCaseAreRedacted() {
        assertRedactedAndUnchanged(mapOf(
            "aUtHoRiZaTiOn" to "Bearer SUPER_SECRET_MIXED_CASE_TOKEN",
            "pRoXy-AuThOrIzAtIoN" to "Basic SUPER_SECRET_MIXED_PROXY_TOKEN",
            "X-API-Key" to "SUPER_SECRET_CLAUDE_OR_SEARCH_TEST_KEY",
            "API-KEY" to "SUPER_SECRET_MIMO_TEST_KEY",
            "X-Goog-Api-Key" to "SUPER_SECRET_GOOGLE_TEST_KEY",
        ))
    }
}
