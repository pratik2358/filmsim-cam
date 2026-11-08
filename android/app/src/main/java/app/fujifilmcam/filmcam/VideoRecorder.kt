package app.fujifilmcam.filmcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Records the filtered viewfinder to Movies/FilmCam as an MP4: H.264 video fed by the renderer through
 * [inputSurface] (frames carry System.nanoTime() timestamps), plus AAC sound from the microphone.
 *
 * `aspect` is width / height of the viewfinder; `rotation` (0/90/180/270, clockwise) tells players how
 * to turn the picture upright when the phone was held sideways.
 */
class VideoRecorder(context: Context, aspect: Float, rotation: Int, withAudio: Boolean) {
    val width: Int
    val height: Int
    val inputSurface: Surface
    val uri: Uri

    private val resolver = context.contentResolver
    private var pfd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private val video: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var audio: MediaCodec? = null
    private var mic: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var failure: Throwable? = null

    // Muxer: starts once every track has its format; samples that arrive earlier wait in memory.
    private val lock = Any()
    private var expectedTracks = 1
    private var addedTracks = 0
    private var muxing = false
    private val pending = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

    init {
        // Size: 1080 px across (long side at most 1920), rounded to what the encoder takes.
        val caps = video.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        fun sized(base: Int): Pair<Int, Int> {
            var w = base.toFloat(); var h = base / aspect
            if (h > base * 16f / 9f) { h = base * 16f / 9f; w = h * aspect }
            fun even16(v: Float) = max(16, (v / 16f).roundToInt() * 16)
            return even16(w) to even16(h)
        }
        val (w, h) = listOf(1080, 720, 480).map(::sized).firstOrNull { (w, h) -> caps.isSizeSupported(w, h) } ?: sized(480)
        width = w; height = h
        val bitrate = caps.bitrateRange.clamp(min(16_000_000, w * h * 6))
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = video.createInputSurface()

        if (withAudio) runCatching { setUpAudio() }.onFailure { releaseAudio() }   // no microphone: silent video

        val name = "FilmCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FilmCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run { release(); error("no storage") }
        try {
            val fd = resolver.openFileDescriptor(uri, "rw") ?: error("can't open the file")
            pfd = fd
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }
        } catch (t: Throwable) { release(); resolver.delete(uri, null, null); throw t }
    }

    @SuppressLint("MissingPermission")   // only called when RECORD_AUDIO was granted
    private fun setUpAudio() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 8192))
        mic = rec
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        expectedTracks = 2
    }

    fun start() {
        video.start()
        videoThread = thread(name = "video-encoder") { guard { drain(video, block = true) } }
        val enc = audio; val rec = mic
        if (enc != null && rec != null) {
            enc.start()
            audioThread = thread(name = "audio-encoder") { guard { recordAudio(rec, enc) } }
        }
    }

    /** Ends the file. Call after the renderer stopped drawing into [inputSurface]. Throws if nothing usable was recorded. */
    fun finish() {
        try {
            stopping = true
            runCatching { video.signalEndOfInputStream() }
            videoThread?.join(5000); audioThread?.join(5000)
            failure?.let { throw it }
            synchronized(lock) { check(muxing) { "the recording was too short" } }
            muxer!!.stop()
        } catch (t: Throwable) {
            release(); resolver.delete(uri, null, null); throw t
        }
        release()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }

    /** Throws the recording away (e.g. the renderer couldn't draw into the encoder). */
    fun cancel() {
        stopping = true
        runCatching { video.signalEndOfInputStream() }
        videoThread?.join(2000); audioThread?.join(2000)
        release(); resolver.delete(uri, null, null)
    }

    private fun recordAudio(rec: AudioRecord, enc: MediaCodec) {
        rec.startRecording()
        var startUs = -1L; var samples = 0L; var eosQueued = false
        val info = MediaCodec.BufferInfo(); var track = -1
        while (true) {
            if (!eosQueued) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = enc.getInputBuffer(i)!!; buf.clear()
                    if (startUs < 0) startUs = System.nanoTime() / 1000
                    val pts = startUs + samples * 1_000_000 / SAMPLE_RATE
                    if (stopping) {
                        enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosQueued = true
                    } else {
                        val n = rec.read(buf, min(buf.capacity(), 4096)).coerceAtLeast(0)
                        enc.queueInputBuffer(i, 0, n, pts, 0)
                        samples += n / 2
                    }
                }
            }
            // Drain whatever is ready (without waiting, unless we're finishing).
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (eosQueued) 10_000 else 0)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = addTrack(enc.outputFormat); continue }
                if (o < 0) break
                if (writeOutput(enc, o, info, track)) { rec.stop(); return }
            }
        }
    }

    private fun drain(codec: MediaCodec, block: Boolean) {
        val info = MediaCodec.BufferInfo(); var track = -1
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (block) 10_000 else 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = addTrack(codec.outputFormat)
                o >= 0 -> if (writeOutput(codec, o, info, track)) return
            }
        }
    }

    /** Writes one encoded buffer; returns true at end of stream. */
    private fun writeOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo, track: Int): Boolean {
        val buf = codec.getOutputBuffer(index)!!
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0   // already in the track format
        if (info.size > 0 && track >= 0) {
            buf.position(info.offset); buf.limit(info.offset + info.size)
            write(track, buf, info)
        }
        codec.releaseOutputBuffer(index, false)
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        val m = muxer!!
        val t = m.addTrack(format)
        if (++addedTracks == expectedTracks) {
            m.start(); muxing = true
            pending.forEach { (tr, b, i) -> m.writeSampleData(tr, b, i) }
            pending.clear()
        }
        t
    }

    private fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (muxing) muxer!!.writeSampleData(track, buf, info)
        else {
            val copy = ByteBuffer.allocate(info.size).put(buf).apply { flip() }
            val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending += Triple(track, copy, i)
        }
    }

    private inline fun guard(block: () -> Unit) { try { block() } catch (t: Throwable) { if (failure == null) failure = t } }

    private fun releaseAudio() {
        runCatching { audio?.stop() }; runCatching { audio?.release() }; audio = null
        runCatching { mic?.release() }; mic = null
        expectedTracks = 1
    }

    private fun release() {
        runCatching { video.stop() }; runCatching { video.release() }; runCatching { inputSurface.release() }
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        runCatching { mic?.release() }
        runCatching { muxer?.release() }; muxer = null
        runCatching { pfd?.close() }; pfd = null
    }

    companion object { private const val SAMPLE_RATE = 48_000 }
}
