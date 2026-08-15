package com.alekpeed.hearsay.tools.desktop.capture

import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/** A keyboard the app can listen to, named the way the operating system names it. */
data class MidiSource(val info: MidiDevice.Info) {
    val name: String get() = "${info.name} — ${info.description}".trim().removeSuffix("—").trim()
}

/**
 * Keys in, nothing out.
 *
 * The instrument makes its own sound; this reads what was played and never sends anything back, so
 * plugging in cannot change how the piano behaves. Devices are enumerated fresh on every look
 * because a USB keyboard turned on after the app started is the normal case, not the exception.
 */
class MidiInput {

    private var device: MidiDevice? = null

    fun sources(): List<MidiSource> = MidiSystem.getMidiDeviceInfo()
        .mapNotNull { info ->
            val candidate = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: return@mapNotNull null
            // A device that cannot transmit is an output — a synth to play through, not a keyboard.
            val transmits = candidate.maxTransmitters != 0
            if (!transmits) null else MidiSource(info)
        }

    /**
     * Opens [source] and reports every key press to [onNote].
     *
     * Times are milliseconds from the moment the device opened, which is all the corpus needs: a
     * take is scored on which notes sounded together and for how long, never on when in the day.
     */
    fun open(source: MidiSource, onNote: (pitch: Int, velocity: Int, timeMs: Long) -> Unit) {
        close()
        val opened = MidiSystem.getMidiDevice(source.info)
        opened.open()
        val startedNanos = System.nanoTime()
        opened.transmitter.receiver = object : Receiver {
            override fun send(message: MidiMessage, timeStamp: Long) {
                if (message !is ShortMessage) return
                val elapsed = (System.nanoTime() - startedNanos) / 1_000_000
                when (message.command) {
                    ShortMessage.NOTE_ON -> onNote(message.data1, message.data2, elapsed)
                    // A release is either an explicit note-off or a note-on at zero velocity, and
                    // instruments disagree about which they send.
                    ShortMessage.NOTE_OFF -> onNote(message.data1, 0, elapsed)
                    else -> Unit
                }
            }

            override fun close() = Unit
        }
        device = opened
    }

    fun close() {
        device?.let { runCatching { it.close() } }
        device = null
    }
}
