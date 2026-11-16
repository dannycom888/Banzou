package com.dannycom888.banzou.audio

/**
 * JNI bridge to libmp3lame (LAME 3.100, vendored under src/main/cpp/lame).
 * Single-encoder-at-a-time design: the processing queue is strictly
 * sequential, so one global encoder instance is enough.
 */
object LameNative {
    init {
        System.loadLibrary("banzou_lame")
    }

    external fun initEncoder(channels: Int, sampleRate: Int, bitRateKbps: Int): Boolean
    external fun encodeInterleaved(pcm: ShortArray, samplesPerChannel: Int): ByteArray?
    external fun flushEncoder(): ByteArray?
    external fun closeEncoder()
}
