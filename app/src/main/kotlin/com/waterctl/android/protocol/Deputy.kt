package com.waterctl.android.protocol

// Direct Kotlin port of `deputy.wat`'s `makeKey` function (and the Rust port
// in waterctlEle/crates/protocol/src/deputy.rs).
//
// The original WASM operates on a single 64 KiB memory page. The first 2096
// bytes are initialised from a lookup table (`DEPUTY_DATA`), the rest of the
// page stays zero. Lookups past offset 2096 therefore return `0`.

internal fun load8(addr: Int): Int {
    return if (addr < DEPUTY_DATA.size) DEPUTY_DATA[addr] else 0
}

// Port of `makeKey(a, b, c, d)`.
//
// Returns [k0, k1, k2, k3] (values 0..255), matching the JS `rawKey` order
// (i.e. the little-endian read-out of `Uint32Array(memory.buffer)[524]`).
internal fun makeKey(a0: Int, b0: Int, c0: Int, d0: Int): IntArray {
    var a = a0

    // (if b == 255 && (a & 1) == 1) => a += 2
    if (b0 == 255 && (a and 1) == 1) {
        a += 2
    }
    if (a == 257) {
        a = 0
    }

    // ---- iA ----
    var iA = d0 and 7
    var m = d0 and 7.inv()
    for (k in 3..7) {
        if ((m and (1 shl k)) != 0) {
            iA = iA xor 4
        }
    }
    iA = iA xor load8(b0)

    for (bit in 0..7) {
        if ((a and (1 shl bit)) != 0) {
            iA = iA xor when (bit) {
                0, 1, 4, 5, 6, 7 -> 1
                2 -> 2
                3 -> 7
                else -> 0
            }
        }
    }

    // ---- iC ----
    var iC = c0 and 7
    m = c0 and 7.inv()
    for (k in 3..7) {
        if ((m and (1 shl k)) != 0) {
            iC = iC xor 4
        }
    }
    iC = iC xor load8(768 + (b0 and 15))

    // ---- d, iB ----
    var d = load8(256 + b0) xor d0
    d = load8(512 + a) xor d
    val iB = load8(1296 + d)

    // ---- c, iD ----
    var c = load8(784 + b0) xor c0
    c = load8(1040 + a) xor c
    val iD = load8(1552 + c)

    // ---- final lookup table reads ----
    val rIA = load8(1938 + iA)
    val rIB = load8(1808 + iB)
    val rIC = load8(2082 + iC)
    val rID = load8(1952 + iD)

    return intArrayOf(rIA, rIB, rIC, rID)
}