package com.ewslereng.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

object AlarmPlayer {

    private const val SAMPLE_RATE =
        44100

    private const val FREQ_LOW =
        587.0

    private const val FREQ_HIGH =
        880.0

    private const val TONE_DURATION_MS =
        420

    @Volatile
    private var playing =
        false

    private var thread:
        Thread? =
        null

    fun start() {
        if (playing) {
            return
        }

        playing =
            true

        thread =
            Thread {
                try {
                    val minBufferSize =
                        AudioTrack.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )

                    val audioTrack =
                        AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(
                                        AudioAttributes.USAGE_ALARM
                                    )
                                    .setContentType(
                                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                                    )
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(
                                        SAMPLE_RATE
                                    )
                                    .setEncoding(
                                        AudioFormat.ENCODING_PCM_16BIT
                                    )
                                    .setChannelMask(
                                        AudioFormat.CHANNEL_OUT_MONO
                                    )
                                    .build()
                            )
                            .setBufferSizeInBytes(
                                minBufferSize
                            )
                            .setTransferMode(
                                AudioTrack.MODE_STREAM
                            )
                            .build()

                    audioTrack.setVolume(
                        AudioTrack.getMaxVolume()
                    )

                    audioTrack.play()

                    val samplesPerTone =
                        (
                            SAMPLE_RATE *
                                TONE_DURATION_MS /
                                1000.0
                            )
                            .toInt()

                    val buffer =
                        ShortArray(
                            samplesPerTone
                        )

                    var high =
                        true

                    try {
                        while (playing) {
                            val freq =
                                if (high) {
                                    FREQ_HIGH
                                }
                                else {
                                    FREQ_LOW
                                }

                            for (
                                i in buffer.indices
                            ) {
                                val angle =
                                    2.0 *
                                        PI *
                                        i *
                                        freq /
                                        SAMPLE_RATE

                                buffer[i] =
                                    if (
                                        sin(angle) >= 0
                                    ) {
                                        Short.MAX_VALUE
                                    }
                                    else {
                                        Short.MIN_VALUE
                                    }
                            }

                            var offset =
                                0

                            while (
                                offset <
                                buffer.size &&
                                playing
                            ) {
                                val written =
                                    audioTrack.write(
                                        buffer,
                                        offset,
                                        buffer.size -
                                            offset
                                    )

                                if (
                                    written <= 0
                                ) {
                                    break
                                }

                                offset +=
                                    written
                            }

                            high =
                                !high
                        }
                    }
                    finally {
                        runCatching {
                            audioTrack.stop()
                        }

                        audioTrack.release()
                    }
                }
                catch (_: Exception) {
                    playing =
                        false
                }
            }

        thread?.start()
    }

    fun stop() {
        playing =
            false

        thread
            ?.join(
                500
            )

        thread =
            null
    }

    fun isPlaying():
        Boolean =
        playing
}
