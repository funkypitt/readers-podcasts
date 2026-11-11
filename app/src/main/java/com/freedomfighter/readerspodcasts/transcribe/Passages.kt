package com.freedomfighter.readerspodcasts.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.freedomfighter.readers.speech.audio.FloatList
import com.freedomfighter.readers.speech.audio.Pcm
import com.freedomfighter.readers.speech.audio.Resample
import java.nio.ByteOrder

/**
 * Short passages of an episode, read where they are rather than from the start.
 *
 * Finding the language spoken listens to a few half-minutes spread through the whole episode; an
 * hour-long talk decoded from its beginning to reach the last of them would cost as much as the
 * transcription's own decoding. So each passage is reached by a seek and decoded on its own.
 * The same passage is asked for twice (once for speech, once for the language): it is kept.
 */
class Passages(private val ctx: Context, private val uri: Uri) {
    private val kept = HashMap<Pair<Long, Int>, FloatArray>()

    /** [n] samples at 16 kHz mono from sample [at]; silence where the file gives nothing. */
    fun read(at: Long, n: Int): FloatArray = kept.getOrPut(at to n) { decode(at, n) }

    private fun decode(at: Long, n: Int): FloatArray {
        val out = FloatArray(n)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return out
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val startUs = at * 1_000_000L / 16_000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            val acc = FloatList(1 shl 16)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            try {
                // A little more than asked, at the file's own rate, for the resampling to be whole.
                while (acc.size < (n.toLong() * rate / 16_000 + rate / 10) && idle < 200) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val s = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (s < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, s, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        o >= 0 -> {
                            idle = 0
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            // The seek lands on the frame before: drop what comes ahead of the passage.
                            var skip = ((startUs - info.presentationTimeUs).coerceAtLeast(0) * rate / 1_000_000L).toInt()
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = buf.asFloatBuffer(); val frames = fb.remaining() / channels
                                for (k in 0 until frames) { var v = 0f; for (c in 0 until channels) v += fb.get(); if (skip > 0) skip-- else acc.add(v / channels) }
                            } else {
                                val sb = buf.asShortBuffer(); val frames = sb.remaining() / channels
                                for (k in 0 until frames) { var v = 0f; for (c in 0 until channels) v += sb.get() / 32768f; if (skip > 0) skip-- else acc.add(v / channels) }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        else -> if (inputDone) idle++
                    }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            val pcm = Resample.to(Pcm(acc.toArray(), rate), 16_000).samples
            pcm.copyInto(out, 0, 0, minOf(n, pcm.size))
        } finally {
            ex.release()
        }
        return out
    }
}
