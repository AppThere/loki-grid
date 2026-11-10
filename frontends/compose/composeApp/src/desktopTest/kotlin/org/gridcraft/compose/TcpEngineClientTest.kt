package org.gridcraft.compose

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TcpEngineClientTest {
    /** Minimal stand-in for the control port: auth line first, then echo-style replies. */
    private fun server(token: String): Pair<ServerSocket, Thread> {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val t = thread(isDaemon = true) {
            runCatching {
                ss.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val w = s.getOutputStream().bufferedWriter()
                    fun reply(s: String) { w.write(s + "\n"); w.flush() }
                    val auth = Json.parseToJsonElement(r.readLine()).jsonObject
                    if (auth["params"]?.jsonObject?.get("token")?.jsonPrimitive?.content != token) {
                        reply("""{"ok":false,"error":"authentication required"}"""); return@use
                    }
                    reply("""{"id":0,"ok":true,"result":{"authenticated":true}}""")
                    while (true) {
                        val req = Json.parseToJsonElement(r.readLine() ?: break).jsonObject
                        val id = req["id"]!!.jsonPrimitive.content
                        if (req["method"]!!.jsonPrimitive.content == "bad") reply("""{"id":$id,"ok":false,"error":"nope"}""")
                        else reply("""{"id":$id,"ok":true,"result":{"echo":"${req["method"]!!.jsonPrimitive.content}"}}""")
                    }
                }
            }
        }
        return ss to t
    }

    @Test fun authenticatesAndCalls() = runBlocking {
        val (ss, _) = server("0123456789abcdef")
        val c = TcpEngineClient.connect(ss.localPort, "0123456789abcdef")
        assertEquals("hi", c.call("hi").jsonObject["echo"]!!.jsonPrimitive.content)
        assertFailsWith<EngineException> { c.call("bad") }
        c.close(); ss.close()
    }

    @Test fun wrongTokenFails() = runBlocking {
        val (ss, _) = server("0123456789abcdef")
        assertFailsWith<EngineException> { TcpEngineClient.connect(ss.localPort, "wrong-wrong-wrong!") }
        ss.close()
    }

    @Test fun unreachablePortFails() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertFailsWith<EngineException> { TcpEngineClient.connect(port, "0123456789abcdef") }
        Unit
    }
}
