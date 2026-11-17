package com.wusper.findorientation.nearby

object Utf8 {
    /** Truncate [text] to at most [maxBytes] without splitting a UTF-8 sequence. */
    fun truncate(text: String, maxBytes: Int): ByteArray {
        require(maxBytes >= 0)
        val raw = text.encodeToByteArray()
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return raw.copyOf(end)
    }
}
