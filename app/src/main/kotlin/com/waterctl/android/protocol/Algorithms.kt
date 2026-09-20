package com.waterctl.android.protocol

// CRC-16 implementations mirroring
// https://github.com/celesWuff/waterctl/blob/2.x/src/algorithms.ts.

// CRC-16/ChangGong
// width=16 poly=0x8005 init=0xe808 refin=true refout=true xorout=0x0000
internal fun crc16Changgong(data: String): Int {
    var crc = 0x1017
    for (b in data.toByteArray(Charsets.UTF_8)) {
        crc = crc xor (b.toInt() and 0xff)
        for (i in 0 until 8) {
            crc = if ((crc and 0x0001) == 1) {
                (crc shr 1) xor 0xa001
            } else {
                crc shr 1
            }
        }
    }
    return crc and 0xffff
}

// CRC-16/CGAEAF (abbrev for ChangGong AE/AF)
// width=16 poly=0x8005 init=0xf856 refin=true refout=true xorout=0x0075
// truncated to lower 8 bits
internal fun crc16Cgaeaf(data: ByteArray): Int {
    var crc = 0x6a1f
    for (byte in data) {
        crc = crc xor (byte.toInt() and 0xff)
        for (i in 0 until 8) {
            crc = if ((crc and 0x0001) == 1) {
                (crc shr 1) xor 0xa001
            } else {
                crc shr 1
            }
        }
    }
    return (crc xor 0x75) and 0xff
}