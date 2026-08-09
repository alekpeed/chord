package com.alekpeed.hearsay.tools.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

/**
 * Hearsay on a desktop.
 *
 * The tablet decides what a chart says; this decides whether it is right, because the only way to
 * judge a chord chart is to hear the recording against it. A command-line tool can print chords and
 * cannot show you the highlight moving, which is the thing that was actually wrong.
 *
 * It runs the identical analysis — :core:audio has been kept free of Android from the start for
 * exactly this — so a defect seen here is the tablet's defect and not this program's.
 */
fun main(args: Array<String>) = application {
    val initial = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Hearsay",
        state = rememberWindowState(size = DpSize(1180.dp, 820.dp)),
    ) {
        HearsayWindow(initialFile = initial)
    }
}
