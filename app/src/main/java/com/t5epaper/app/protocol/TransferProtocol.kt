package com.t5epaper.app.protocol

import java.util.zip.CRC32

/**
 * 与固件 (src/bt_image_server.cpp) 一致的传输协议
 *
 * 帧:  MAGIC(2) | TYPE(1) | SEQ(1) | LEN(2) | PAYLOAD(LEN) | CRC16(2)
 *      0xAA 0x55
 * TYPE 0x01 START: payload = totalBytes(2) + totalFrames(2)
 * TYPE 0x02 DATA : payload = 位图分片
 * TYPE 0x03 END  : payload = CRC32(4)
 */
object TransferProtocol {
    const val MAGIC1 = 0xAA.toByte()
    const val MAGIC2 = 0x55.toByte()
    const val TYPE_START = 0x01.toByte()
    const val TYPE_DATA = 0x02.toByte()
    const val TYPE_END = 0x03.toByte()

    const val MAX_PAYLOAD = 236
    const val IMG_SIZE = 4000

    /** Modbus CRC16 */
    fun crc16(data: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (b in 0 until 8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    private fun frame(type: Byte, seq: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(6 + payload.size + 2)
        out[0] = MAGIC1
        out[1] = MAGIC2
        out[2] = type
        out[3] = seq.toByte()
        out[4] = ((payload.size ushr 8) and 0xFF).toByte()
        out[5] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 6, payload.size)
        val crc = crc16(out, 6 + payload.size)
        out[out.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
    }

    fun startFrame(imgSize: Int, totalFrames: Int): ByteArray {
        val p = ByteArray(4)
        p[0] = ((imgSize ushr 8) and 0xFF).toByte()
        p[1] = (imgSize and 0xFF).toByte()
        p[2] = ((totalFrames ushr 8) and 0xFF).toByte()
        p[3] = (totalFrames and 0xFF).toByte()
        return frame(TYPE_START, 0, p)
    }

    fun dataFrame(seq: Int, chunk: ByteArray): ByteArray = frame(TYPE_DATA, seq, chunk)

    fun endFrame(data: ByteArray): ByteArray {
        val crc = crc32(data)
        val p = ByteArray(4)
        p[0] = ((crc ushr 24) and 0xFF).toByte()
        p[1] = ((crc ushr 16) and 0xFF).toByte()
        p[2] = ((crc ushr 8) and 0xFF).toByte()
        p[3] = (crc and 0xFF).toByte()
        return frame(TYPE_END, 0, p)
    }

    /** 把整图切成分片列表 */
    fun splitImage(data: ByteArray): List<ByteArray> {
        val frames = (data.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD
        return (0 until frames).map { i ->
            val from = i * MAX_PAYLOAD
            val to = minOf(from + MAX_PAYLOAD, data.size)
            data.copyOfRange(from, to)
        }
    }
}
