package dev.stealthcompanion.core.social

import java.nio.ByteBuffer
import java.security.MessageDigest

private const val HEX_DIGITS = "0123456789abcdef"

internal fun socialDigest(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val length = ByteBuffer.allocate(Int.SIZE_BYTES)
    for (field in fields) {
        val value = field.toByteArray(Charsets.UTF_8)
        length.putInt(0, value.size)
        digest.update(length.array())
        digest.update(value)
    }
    return buildString(64) {
        for (byte in digest.digest()) {
            val unsigned = byte.toInt() and 0xFF
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0F])
        }
    }
}
