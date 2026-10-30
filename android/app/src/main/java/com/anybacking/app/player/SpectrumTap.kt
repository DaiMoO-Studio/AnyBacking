package com.anybacking.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 頻譜（給播放畫面背景用）：聲音原樣通過，另外每 [N] 點做一次 FFT，算成 [BANDS] 個對數頻帶（0～1）。
 * 直接分析播放中的聲音，不需要麥克風權限（系統 Visualizer 要）。在音訊執行緒執行：不配置記憶體。
 */
@UnstableApi
class SpectrumTap : BaseAudioProcessor() {
    companion object {
        const val N = 1024
        const val BANDS = 24
    }

    /** 最新一次的頻帶強度；畫面每幀讀取（寫入是整個陣列替換）。 */
    @Volatile var bands = FloatArray(BANDS)
        private set

    private var channelCount = 2
    private var sampleRate = 44100
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private var fill = 0
    private var edges = IntArray(BANDS + 1)
    private val back = Array(2) { FloatArray(BANDS) }
    private var flip = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        edges = bandEdges(sampleRate)
        return inputAudioFormat   // 原樣通過
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val base = inp.position()
        val frameBytes = channelCount * 2
        var p = base
        while (p + frameBytes <= base + n) {
            var s = 0f
            for (c in 0 until channelCount) s += inp.getShort(p + c * 2)
            re[fill] = s / channelCount / 32768f * window[fill]
            im[fill] = 0f
            if (++fill == N) { analyze(); fill = 0 }
            p += frameBytes
        }
        val out = replaceOutputBuffer(n)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onReset() {
        fill = 0
        bands = FloatArray(BANDS)
    }

    private fun analyze() {
        fft(re, im)
        val dst = back[flip]; flip = 1 - flip
        for (b in 0 until BANDS) {
            var m = 0f
            for (k in edges[b] until max(edges[b + 1], edges[b] + 1)) m = max(m, sqrt(re[k] * re[k] + im[k] * im[k]))
            // 轉成 dB 再壓到 0～1（-60 dB → 0）
            val db = 20f * (ln(m / (N / 4f) + 1e-9f) / ln(10f))
            dst[b] = ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
        bands = dst
    }
}

/** 40 Hz～16 kHz 等比切成 BANDS 段，回傳每段起始的 FFT bin。 */
internal fun bandEdges(sampleRate: Int, n: Int = SpectrumTap.N, bands: Int = SpectrumTap.BANDS): IntArray {
    val lo = 40.0; val hi = minOf(16000.0, sampleRate / 2.0)
    return IntArray(bands + 1) { i ->
        val f = lo * (hi / lo).pow(i.toDouble() / bands)
        (f * n / sampleRate).toInt().coerceIn(1, n / 2)
    }
}

/** 就地 radix-2 FFT（長度須為 2 的次方）。 */
internal fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2 * PI / len
        val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var cr = 1f; var ci = 0f
            for (k in 0 until len / 2) {
                val a = i + k; val b = a + len / 2
                val xr = re[b] * cr - im[b] * ci
                val xi = re[b] * ci + im[b] * cr
                re[b] = re[a] - xr; im[b] = im[a] - xi
                re[a] += xr; im[a] += xi
                val nr = cr * wr - ci * wi
                ci = cr * wi + ci * wr; cr = nr
            }
            i += len
        }
        len = len shl 1
    }
}
