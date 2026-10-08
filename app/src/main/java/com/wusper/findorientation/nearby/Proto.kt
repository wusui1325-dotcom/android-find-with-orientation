package com.wusper.findorientation.nearby

import java.nio.ByteBuffer
import java.util.UUID

object Proto {
    const val VERSION: Int = 1
    val SERVICE: UUID = UUID.fromString("f17d0e11-4a6b-4c2e-9f10-0a11b0c1d001")
    val IDENTITY: UUID = UUID.fromString("f17d0e11-4a6b-4c2e-9f10-0a11b0c1d002")
    val SESSION: UUID = UUID.fromString("f17d0e11-4a6b-4c2e-9f10-0a11b0c1d003")
    val LOCATION: UUID = UUID.fromString("f17d0e11-4a6b-4c2e-9f10-0a11b0c1d004")

    const val FLAG_UWB = 1
    const val OFFER = 1
    const val ACCEPT = 2

    data class Advert(val flags: Int, val shortId: ByteArray, val name: String)
    data class Identity(val id: ByteArray, val name: String, val uwb: Boolean)
    data class SessionOffer(
        val sessionId: Int,
        val channel: Int,
        val preamble: Int,
        val controllerAddress: ByteArray,
        val sessionKey: ByteArray
    )
    data class SessionAccept(val id: ByteArray, val controleeAddress: ByteArray)
    data class GeoFix(val lat: Double, val lng: Double, val accuracy: Float, val heading: Float)

    fun advert(flags: Int, id: ByteArray, name: String): ByteArray {
        val nameBytes = name.encodeToByteArray().take(7).toByteArray()
        return byteArrayOf(VERSION.toByte(), flags.toByte()) +
            id.copyOfRange(0, 4) +
            nameBytes
    }

    fun parseAdvert(data: ByteArray): Advert? {
        if (data.size < 6 || data[0].toInt() != VERSION) return null
        val name = if (data.size > 6) data.copyOfRange(6, data.size).toString(Charsets.UTF_8) else ""
        return Advert(data[1].toInt() and 0xff, data.copyOfRange(2, 6), name)
    }

    fun identity(id: ByteArray, name: String, uwb: Boolean): ByteArray {
        val nameBytes = name.encodeToByteArray().take(24).toByteArray()
        return byteArrayOf(VERSION.toByte(), if (uwb) FLAG_UWB.toByte() else 0) + id.copyOf(16) + nameBytes
    }

    fun parseIdentity(data: ByteArray): Identity? {
        if (data.size < 18 || data[0].toInt() != VERSION) return null
        return Identity(
            id = data.copyOfRange(2, 18),
            name = data.copyOfRange(18, data.size).toString(Charsets.UTF_8).ifBlank { "客戶端" },
            uwb = data[1].toInt() and FLAG_UWB != 0
        )
    }

    fun offer(offer: SessionOffer): ByteArray {
        val buf = ByteBuffer.allocate(1 + 4 + 1 + 1 + 2 + 8)
        buf.put(OFFER.toByte())
        buf.putInt(offer.sessionId)
        buf.put(offer.channel.toByte())
        buf.put(offer.preamble.toByte())
        buf.put(offer.controllerAddress.copyOf(2))
        buf.put(offer.sessionKey.copyOf(8))
        return buf.array()
    }

    fun accept(accept: SessionAccept): ByteArray {
        return byteArrayOf(ACCEPT.toByte()) + accept.id.copyOf(16) + accept.controleeAddress.copyOf(2)
    }

    fun parseSession(data: ByteArray): Any? {
        if (data.isEmpty()) return null
        return when (data[0].toInt()) {
            OFFER -> {
                if (data.size < 17) return null
                val buf = ByteBuffer.wrap(data)
                buf.get()
                SessionOffer(
                    sessionId = buf.int,
                    channel = buf.get().toInt() and 0xff,
                    preamble = buf.get().toInt() and 0xff,
                    controllerAddress = ByteArray(2).also { buf.get(it) },
                    sessionKey = ByteArray(8).also { buf.get(it) }
                )
            }
            ACCEPT -> {
                if (data.size < 19) return null
                SessionAccept(data.copyOfRange(1, 17), data.copyOfRange(17, 19))
            }
            else -> null
        }
    }

    fun geo(fix: GeoFix): ByteArray {
        val buf = ByteBuffer.allocate(25)
        buf.put(VERSION.toByte())
        buf.putDouble(fix.lat)
        buf.putDouble(fix.lng)
        buf.putFloat(fix.accuracy)
        buf.putFloat(fix.heading)
        return buf.array()
    }

    fun parseGeo(data: ByteArray): GeoFix? {
        if (data.size < 25 || data[0].toInt() != VERSION) return null
        val buf = ByteBuffer.wrap(data)
        buf.get()
        return GeoFix(buf.double, buf.double, buf.float, buf.float)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}
