package org.gridcraft.compose

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Newline-delimited JSON over TCP to `gridcraft --control <port>`; authenticates first. */
class TcpEngineClient private constructor(private val socket: Socket) : EngineClient {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
    private val lock = Mutex()
    private var nextId = 1

    override suspend fun call(method: String, params: JsonObject): JsonElement = lock.withLock {
        withContext(Dispatchers.IO) { roundTrip(nextId++, method, params) }
    }

    private fun roundTrip(id: Int, method: String, params: JsonObject): JsonElement {
        val request = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            writer.write(request.toString() + "\n")
            writer.flush()
            val line = reader.readLine() ?: throw EngineException("engine closed the connection")
            val reply = Json.parseToJsonElement(line) as? JsonObject
                ?: throw EngineException("malformed reply")
            if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                throw EngineException((reply["error"] as? JsonPrimitive)?.contentOrNull ?: "request failed")
            }
            return reply["result"] ?: JsonNull
        } catch (e: java.io.IOException) {
            throw EngineException("connection error: ${e.message}", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw EngineException("malformed reply", e)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        suspend fun connect(port: Int, token: String, host: String = "127.0.0.1"): TcpEngineClient =
            withContext(Dispatchers.IO) {
                val socket = try {
                    Socket(host, port)
                } catch (e: java.io.IOException) {
                    throw EngineException("cannot reach GridCraft on $host:$port (start it with --control $port)", e)
                }
                val client = TcpEngineClient(socket)
                try {
                    client.roundTrip(0, "auth", buildJsonObject { put("token", token) })
                } catch (e: EngineException) {
                    client.close()
                    throw e
                }
                client
            }
    }
}
