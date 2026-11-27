package app.universalmedia.media.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import app.universalmedia.mirror.protocol.MirrorProtocol
import app.universalmedia.mirror.protocol.Packet
import app.universalmedia.mirror.protocol.PacketType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures what apps are playing (Android 10+, with the user's screen-capture and
 * audio permission) and sends it as AAC to the receiver app. Apps that block
 * capture, and calls, stay silent on the TV; that's Android's rule.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class AudioCapture(projection: MediaProjection, private val send: (Packet) -> Unit) {
    private val running = AtomicBoolean(true)
    private val record: AudioRecord
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val thread: Thread

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        @SuppressLint("MissingPermission") // checked by MirrorService before starting
        val r = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 2, 8_192))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = r
        val aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        thread = Thread({ loop() }, "mirror-audio").also { it.start() }
    }

    private fun loop() {
        val pcm = ByteArray(4_096)
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val n = record.read(pcm, 0, pcm.size)
                if (n > 0) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        if (buffer != null) {
                            buffer.clear()
                            buffer.put(pcm, 0, minOf(n, buffer.capacity()))
                            // Same clock as the video encoder, so the receiver can line them up.
                            codec.queueInputBuffer(index, 0, minOf(n, buffer.capacity()), System.nanoTime() / 1000, 0)
                        }
                    }
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    val buffer = codec.getOutputBuffer(out)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            send(Packet(PacketType.AUDIO_CONFIG, 0, MirrorProtocol.audioConfigPayload(SAMPLE_RATE, CHANNELS, bytes)))
                        } else {
                            send(Packet(PacketType.AUDIO_FRAME, info.presentationTimeUs, bytes))
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                }
            }
        } catch (e: Exception) {
            // Stopped, or the TV went away; the picture carries on without sound.
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { thread.join(500) }
        runCatching { record.stop() }
        runCatching { record.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
    }
}
