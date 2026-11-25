package dev.stealthcompanion

internal object SensitiveNotification {
    private val codeLabel = Regex(
        """\b(?:otp|passcode|one[ -]time|verification code|security code|codice di (?:verifica|sicurezza)|codice monouso|autenticazione a due fattori)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneNumber = Regex("""(?<![\p{L}\d])\d{4,10}(?![\p{L}\d])""")

    fun containsCode(value: String): Boolean =
        codeLabel.containsMatchIn(value) || (value.length <= 12 && standaloneNumber.matches(value.trim()))
}
