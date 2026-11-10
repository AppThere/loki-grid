package org.gridcraft.compose

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The engine reported `{"ok": false, "error": …}`, or the transport failed. */
class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A connection to a running GridCraft engine. The frontend shares no code with the Rust
 * workspace: everything goes through the control protocol (`docs/control-protocol.md`).
 */
interface EngineClient {
    /** Sends one request and returns its `result`; throws [EngineException] on `ok:false`. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement

    fun close()
}
