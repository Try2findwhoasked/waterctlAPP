package com.waterctl.android.protocol

import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.random.Random

// Session payload builders mirroring `src/solvers.ts` (and
// waterctlEle/crates/protocol/src/solvers.rs).

private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

// explanation: XYZW (decimal) => [0xXY, 0xZW]
private fun makeRandomUserId(): ByteArray {
    val randomIdNumber = Random.nextInt(1, 10000) // 1..=9999
    return byteArrayOf(
        decAsHex((randomIdNumber shr 8).toLong()).toByte(),
        decAsHex((randomIdNumber and 0xff).toLong()).toByte(),
    )
}

// explanation: 2013/1/11 12:34:56 => [0x13, 0x01, 0x11, 0x12, 0x34, 0x56]
private fun makeDatetimeArray(): ByteArray {
    val now = ZonedDateTime.now(SHANGHAI)
    val decimal = longArrayOf(
        (now.year % 100).toLong(),
        now.monthValue.toLong(),
        now.dayOfMonth.toLong(),
        now.hour.toLong(),
        now.minute.toLong(),
        now.second.toLong(),
    )
    return ByteArray(6) { i -> decAsHex(decimal[i]).toByte() }
}

// Build the AF unlock response for a received AE request.
internal fun makeUnlockResponse(unlockRequest: ByteArray, deviceName: String): ByteArray {
    if (unlockRequest.size < 10) {
        throw IllegalArgumentException("WATERCTL INTERNAL Bad unlock request")
    }

    val unknownByte = unlockRequest[5]
    val nonce0 = unlockRequest[6].toInt() and 0xff
    val nonce1 = unlockRequest[7].toInt() and 0xff
    val mac0 = unlockRequest[8].toInt() and 0xff
    val mac1 = unlockRequest[9].toInt() and 0xff

    // [0x00, 0x01] => 0x0001 => (add 1) => 0x0002 => [0x00, 0x02]
    val nonce = (nonce0 shl 8) or nonce1
    val newNonce0: Int
    val newNonce1: Int
    if (nonce == 0xffff) {
        newNonce0 = 0x01
        newNonce1 = 0x00 // bug-for-bug compatible
    } else {
        val newNonce = nonce + 1
        newNonce0 = (newNonce shr 8) and 0xff
        newNonce1 = newNonce and 0xff
    }

    // makeKey(a, b, c, d) where [a, b, c, d] = [nonce0, nonce1, mac0, mac1]
    val rawKey = makeKey(nonce0, nonce1, mac0, mac1)

    // mask comes from the last 4 chars of the device name
    val mask = deviceName.takeLast(4).map { c -> c.code - 0x30 }
    if (mask.size != 4) {
        throw IllegalArgumentException("WATERCTL INTERNAL Bad device name")
    }
    if (mask.any { it < 0 || it > 255 }) {
        throw IllegalArgumentException("WATERCTL INTERNAL Bad device name")
    }

    val key = IntArray(4) { i -> (rawKey[i] xor mask[i]) and 0xff }

    val checksumInput = byteArrayOf(
        unknownByte,
        newNonce0.toByte(),
        newNonce1.toByte(),
        key[0].toByte(),
        key[1].toByte(),
        key[2].toByte(),
        key[3].toByte(),
        0xFE.toByte(),
        0x87.toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
    )
    val checksum = crc16Cgaeaf(checksumInput)

    val out = ArrayList<Byte>(20)
    out.add(0xFE.toByte())
    out.add(0xFE.toByte())
    out.add(0x09)
    out.add(0xAF.toByte())
    out.add(checksum.toByte())
    for (b in checksumInput) out.add(b)
    return out.toByteArray()
}

// The one true command to begin a session.
internal fun makeStartEpilogue(deviceName: String, isKeyAuthPresent: Boolean): ByteArray {
    val last5 = deviceName.takeLast(5)
    val checksum = crc16Changgong(last5)
    val mn = if (isKeyAuthPresent) 0x0b else 0xff // magic number
    val ri = makeRandomUserId()
    val dt = makeDatetimeArray()

    val out = ArrayList<Byte>(20)
    out.add(0xFE.toByte())
    out.add(0xFE.toByte())
    out.add(0x09)
    out.add(0xB2.toByte())
    out.add(0x01)
    out.add((checksum and 0xff).toByte())
    out.add((checksum shr 8).toByte())
    out.add(mn.toByte())
    out.add(0x00)
    for (b in ri) out.add(b) // 2 bytes of random user id
    for (b in dt) out.add(b) // 6 bytes of datetime
    out.add(0x0F)
    out.add(0x27)
    out.add(0x00)
    return out.toByteArray()
}

// Offlinebomb exploit (deprecated, for reference only).
internal fun makeStartEpilogueOfflinebomb(): ByteArray {
    val ri = makeRandomUserId()
    val dt = makeDatetimeArray()

    val out = ArrayList<Byte>(20)
    out.add(0xFE.toByte())
    out.add(0xFE.toByte())
    out.add(0x09)
    out.add(0xBB.toByte())
    out.add(0x01)
    out.add(0x01)
    out.add(0x0D)
    out.add(0x00)
    out.add(0x50.toByte())
    for (b in ri) out.add(b)
    for (b in dt) out.add(b)
    out.add(0x00)
    out.add(0x20)
    out.add(0x00)
    return out.toByteArray()
}