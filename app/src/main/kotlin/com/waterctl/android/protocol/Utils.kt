package com.waterctl.android.protocol

// Utility functions mirroring
// https://github.com/celesWuff/waterctl/blob/2.x/src/utils.ts.

// Explanation: 42 => 0x42 (decimal digit -> BCD nibble)
// Bug-for-bug compatible with the TypeScript implementation, including the
// truncation behaviour that occurs when large values are stored into a
// `Uint8Array` (values above `0xff` wrap around).
internal fun decAsHex(n: Long): Int {
    return decAsHexWide(n).toInt() and 0xff
}

private fun decAsHexWide(n: Long): Long {
    return if (n == 0L) {
        0L
    } else {
        (n % 10) or (decAsHexWide(n / 10) shl 4)
    }
}

// Convert a byte array to a hex string (uppercase).
// explanation: [0x12, 0x34, 0xAB, 0xCD] => "1234ABCD"
internal fun bufferToHexString(data: ByteArray): String {
    val sb = StringBuilder(data.size * 2)
    for (b in data) {
        val v = b.toInt() and 0xff
        if (v < 0x10) sb.append('0')
        sb.append(Integer.toHexString(v).uppercase())
    }
    return sb.toString()
}