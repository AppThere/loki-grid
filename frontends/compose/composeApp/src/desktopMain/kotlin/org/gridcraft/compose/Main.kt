package org.gridcraft.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File

/** Token as the Rust app stores it: env var, else `$XDG_RUNTIME_DIR/gridcraft/control-<port>.token`. */
internal fun readToken(port: Int): String? {
    System.getenv("GRIDCRAFT_CONTROL_TOKEN")?.takeIf { it.length >= 16 }?.let { return it }
    val home = System.getProperty("user.home")
    val dirs = listOfNotNull(System.getenv("XDG_RUNTIME_DIR")?.let { "$it/gridcraft" }, "$home/.cache/gridcraft", "$home/.gridcraft")
    return dirs.map { File(it, "control-$port.token") }.firstOrNull { it.isFile }?.readText()?.trim()
}

fun main(args: Array<String>) = application {
    val port = args.indexOf("--port").let { if (it >= 0) args.getOrNull(it + 1)?.toIntOrNull() else null } ?: 7979
    var vm by remember { mutableStateOf<GridViewModel?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(port) {
        val token = readToken(port)
        if (token == null) {
            failure = "No control token for port $port. Set GRIDCRAFT_CONTROL_TOKEN or start gridcraft --control $port."
            return@LaunchedEffect
        }
        try {
            vm = GridViewModel(TcpEngineClient.connect(port, token))
        } catch (e: EngineException) {
            failure = e.message
        }
    }

    Window(onCloseRequest = ::exitApplication, title = "GridCraft") {
        val model = vm
        if (model != null) App(model) else Text(failure ?: "Connecting to GridCraft on port $port…")
    }
}
