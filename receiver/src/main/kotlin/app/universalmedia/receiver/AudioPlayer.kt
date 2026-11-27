package app.universalmedia.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import app.universalmedia.mirror.protocol.MirrorProtocol

/**
 * Plays the phone's mirrored sound: decodes AAC frames and writes them to an
 * AudioTrack as soon as they arrive (lowest delay; no buffering beyond the track's own).
 */
internal class AudioPlayer(config: MirrorProtocol.AudioConfig) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val track: AudioTrack
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(config.asc))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        codec.configure(format, null, null, 0)
        codec.start()
        val mask = if (config.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(config.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(config.sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, 16_384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
    }

    /** Called on the network thread for each AAC frame. */
    fun feed(frame: ByteArray, timeUs: Long) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index)
            if (buffer != null && frame.size <= buffer.capacity()) {
                buffer.clear()
                buffer.put(frame)
                codec.queueInputBuffer(index, 0, frame.size, timeUs, 0)
            } else {
                codec.queueInputBuffer(index, 0, 0, timeUs, 0)
            }
        }
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out < 0) break
            val buffer = codec.getOutputBuffer(out)
            if (buffer != null && info.size > 0) {
                val pcm = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(pcm)
                // Non-blocking so a slow speaker never holds up the picture.
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            codec.releaseOutputBuffer(out, false)
        }
    }

    fun release() {
        runCatching { track.stop() }
        runCatching { track.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
