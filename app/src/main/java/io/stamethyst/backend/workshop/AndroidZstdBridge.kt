package io.stamethyst.backend.workshop

object AndroidZstdBridge {
    init {
        System.loadLibrary("workshop_zstd")
    }

    @JvmStatic
    fun decompress(destination: ByteArray, compressed: ByteArray): Int =
        decompressNative(destination, compressed)

    private external fun decompressNative(destination: ByteArray, compressed: ByteArray): Int
}
