package com.alekpeed.hearsay.feature.capture

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/** A keyboard the tablet can listen to, named the way the instrument names itself. */
data class MidiSource(internal val info: MidiDeviceInfo) {
    val name: String
        get() = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI device"
}

private const val NoteOn = 0x90
private const val NoteOff = 0x80
private const val StatusMask = 0xF0

/**
 * Keys in, nothing out.
 *
 * Reads a keyboard's output port and never opens an input one, so connecting the tablet cannot
 * change how the instrument behaves. Devices are enumerated on every look rather than cached: a
 * piano switched on after the screen opened is the normal case.
 */
class MidiInput(private val context: Context) {

    private var device: MidiDevice? = null

    /** Instruments that can send. A device with no output port is a synth to play into, not a piano. */
    fun sources(): List<MidiSource> {
        val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return emptyList()
        @Suppress("DEPRECATION")
        return manager.devices.filter { it.outputPortCount > 0 }.map(::MidiSource)
    }

    /**
     * Opens [source] and reports every key press to [onNote].
     *
     * Times are milliseconds from the moment the port opened, which is all the corpus needs: a take
     * is judged on which notes sounded together, never on when in the day it happened.
     */
    fun open(source: MidiSource, onNote: (pitch: Int, velocity: Int, timeMs: Long) -> Unit) {
        close()
        val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        val startedNanos = System.nanoTime()
        manager.openDevice(
            source.info,
            { opened ->
                device = opened
                val port = opened?.openOutputPort(0) ?: return@openDevice
                port.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val elapsed = (System.nanoTime() - startedNanos) / 1_000_000
                        var index = offset
                        val end = offset + count
                        while (index + 2 < end) {
                            val status = data[index].toInt() and 0xFF
                            val pitch = data[index + 1].toInt() and 0x7F
                            val velocity = data[index + 2].toInt() and 0x7F
                            when (status and StatusMask) {
                                // A release is either an explicit note-off or a note-on at zero
                                // velocity, and instruments disagree about which they send.
                                NoteOn -> onNote(pitch, velocity, elapsed)
                                NoteOff -> onNote(pitch, 0, elapsed)
                                else -> Unit
                            }
                            index += 3
                        }
                    }
                })
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun close() {
        device?.let { runCatching { it.close() } }
        device = null
    }
}
