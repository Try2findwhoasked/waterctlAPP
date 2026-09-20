package com.waterctl.android.protocol

// Fixed BLE payloads mirroring `src/payloads.ts` (and
// waterctlEle/crates/protocol/src/payloads.rs).

// send this first, then wait for B0, B1, or AE
internal val START_PROLOGUE = byteArrayOf(
    0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB0.toByte(), 0x01, 0x01, 0x00, 0x00,
)

// when ending the session, send this then wait for B3
internal val END_PROLOGUE = byteArrayOf(
    0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB3.toByte(), 0x00, 0x00,
)

// after receiving B3, send this then disconnect
internal val END_EPILOGUE = byteArrayOf(
    0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB4.toByte(), 0x00, 0x00,
)

// send before startEpilogueOfflinebomb and endEpilogue, or if BC is received
internal val OFFLINEBOMB_FIX = byteArrayOf(
    0xFE.toByte(), 0xFE.toByte(), 0x09, 0xBC.toByte(), 0x00, 0x00,
)

// send this if BA is received
internal val BA_ACK = byteArrayOf(
    0xFE.toByte(), 0xFE.toByte(), 0x09, 0xBA.toByte(), 0x00, 0x00,
)