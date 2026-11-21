package app.roam.feature.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Hears the audio on its way to the speaker, for [LoudnessMeter], and changes
 * none of it.
 *
 * Measuring only. The gain is applied elsewhere (see [VolumeLeveller]), because
 * this sits BEFORE the AudioTrack's buffer: it meets the next track up to a
 * second before anyone hears it, so a gain switched here at a track change
 * would land late, and audibly. A measurement does not care -- half a second
 * of the neighbouring track in four minutes of this one moves nothing.
 */
internal class MeteringAudioProcessor(private val meter: LoudnessMeter) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            // Let past unmeasured rather than refused: an encoding this cannot
            // read is still one the sink can play.
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    /** Every seek and every new format starts the filters clean. */
    override fun onFlush() {
        meter.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }

    override fun onReset() {
        meter.configure(0, 0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // A view for the meter, in the input's byte order -- duplicate() alone
        // would hand it big-endian samples -- so the input itself is untouched
        // until it is copied out below.
        val view = inputBuffer.duplicate().order(inputBuffer.order())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> meter.feedPcm16(view)
            C.ENCODING_PCM_FLOAT -> meter.feedFloat(view)
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }
}

/**
 * ExoPlayer's own renderers, with the meter in the audio sink's chain. The rest
 * of the chain is the default one, so nothing else about playback changes.
 */
internal class MeteringRenderersFactory(
    context: Context,
    private val meter: LoudnessMeter,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf<AudioProcessor>(MeteringAudioProcessor(meter)))
        .build()
}
