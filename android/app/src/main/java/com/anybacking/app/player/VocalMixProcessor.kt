package com.anybacking.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 4 聲道播放檔（伴奏 L/R＋人聲 L/R，specs/backend/contracts/playback.md）即時混成立體聲：
 * L = 伴奏L + g·人聲L、R = 伴奏R + g·人聲R。
 *
 * g 每個緩衝區都重讀，所以原唱開關／音量不需要 flush 就生效（內建 ChannelMixingAudioProcessor 要重新設定才會換矩陣）。
 * 放在 Sonic（升降 Key、變速）之前，Sonic 只處理 2 聲道。
 * 輸入不是 4 聲道（例如解碼器先降混了）時原樣通過，並記在 [inputChannels] 供診斷。
 */
@UnstableApi
class VocalMixProcessor : BaseAudioProcessor() {
    @Volatile var vocalGain = 0f
    @Volatile var channels = intArrayOf(0, 1, 2, 3)   // instL, instR, vocL, vocR

    /** 最近一次設定時解碼器給的聲道數（診斷用）。 */
    @Volatile var inputChannels = 0
        private set

    /** 診斷：每個輸入聲道的過零次數（正弦波測試檔用來辨認聲道順序）。 */
    val zeroCrossings = LongArray(8)
    @Volatile var framesSeen = 0L
        private set
    @Volatile var diagnostics = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputChannels = inputAudioFormat.channelCount
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        if (inputAudioFormat.channelCount != 4) return AudioProcessor.AudioFormat.NOT_SET   // 不啟用、原樣通過
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / 8
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4)
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val g = vocalGain
        val ch = channels
        val base = inp.position()
        val diag = diagnostics
        for (f in 0 until frames) {
            val p = base + f * 8
            if (diag) diagFrame(inp, p)
            out.putShort(mix(inp.getShort(p + ch[0] * 2), inp.getShort(p + ch[2] * 2), g))
            out.putShort(mix(inp.getShort(p + ch[1] * 2), inp.getShort(p + ch[3] * 2), g))
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private val last = ShortArray(4)
    private fun diagFrame(inp: ByteBuffer, p: Int) {
        for (c in 0 until 4) {
            val s = inp.getShort(p + c * 2)
            if ((s >= 0) != (last[c] >= 0)) zeroCrossings[c]++
            last[c] = s
        }
        framesSeen++
    }

    fun resetDiagnostics() {
        zeroCrossings.fill(0); framesSeen = 0
    }

    companion object {
        /** 伴奏＋g·人聲，超出 16-bit 範圍就限幅。 */
        fun mix(inst: Short, voc: Short, g: Float): Short {
            val v = inst + g * voc
            return v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }
}
