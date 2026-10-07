package com.yuno.tv.core

/** Avoid per-byte Formatter allocations while processing thousands of source fingerprints. */
fun hexDigest(value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    val result = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte -> val number = byte.toInt() and 255
        result[index * 2] = digits[number ushr 4]; result[index * 2 + 1] = digits[number and 15]
    }
    return String(result)
}
