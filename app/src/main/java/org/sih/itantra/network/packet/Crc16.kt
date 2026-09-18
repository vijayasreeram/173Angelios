package org.sih.itantra.network.packet

/**
 * Standard CRC16-CCITT (Poly: 0x1021, Init: 0xFFFF)
 * Used to verify packet transmission integrity over lossy BLE/Wi-Fi/LoRa links.
 */
object Crc16 {
    private const val POLYNOMIAL = 0x1021
    private const val PRESET_VALUE = 0xFFFF

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var currentCrc = PRESET_VALUE
        for (i in offset until (offset + length)) {
            currentCrc = currentCrc xor ((bytes[i].toInt() and 0xFF) shl 8)
            for (j in 0 until 8) {
                currentCrc = if ((currentCrc and 0x8000) != 0) {
                    (currentCrc shl 1) xor POLYNOMIAL
                } else {
                    currentCrc shl 1
                }
                currentCrc = currentCrc and 0xFFFF
            }
        }
        return currentCrc
    }

    fun verify(bytes: ByteArray, expectedCrc: Int): Boolean {
        return compute(bytes) == (expectedCrc and 0xFFFF)
    }
}
