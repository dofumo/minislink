package com.ptit.slinkmini

data class ParsedQr(
    val raw: String,
    val module: String,
    val action: String,
    val data: String,
    val otp: String?
)

object QrParser {
    /**
     * Hỗ trợ 2 định dạng:
     *   1. `PTIT|MODULE|ACTION|DATA[|OTP]`   (có prefix)
     *   2. `MODULE|ACTION|DATA[|OTP]`         (không prefix)
     */
    fun parse(qr: String): ParsedQr? {
        val parts = qr.split("|").map { it.trim() }
        if (parts.size < 3) return null

        val hasPrefix = parts[0].equals("PTIT", ignoreCase = true)

        return if (hasPrefix) {
            if (parts.size < 4) return null
            ParsedQr(
                raw = qr,
                module = parts[1].uppercase(),
                action = parts[2].uppercase(),
                data = parts[3],
                otp = parts.getOrNull(4)
            )
        } else {
            ParsedQr(
                raw = qr,
                module = parts[0].uppercase(),
                action = parts[1].uppercase(),
                data = parts[2],
                otp = parts.getOrNull(3)
            )
        }
    }
}
