package uk.zjd.cloudpass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class KdbxTest {
    private fun u32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    private fun file(blocks: Int = 2): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(), 0, 0, 4, 0))
        write(2); write(u32(16)); write(ByteArray(16))
        write(0); write(u32(4)); write(byteArrayOf(13, 10, 13, 10))
        write(MessageDigest.getInstance("SHA-256").digest(toByteArray()))
        write(ByteArray(32))
        repeat(blocks) { write(ByteArray(32)); write(u32(300)); write(ByteArray(300)) }
        write(ByteArray(32)); write(u32(0))
    }.toByteArray()

    @Test fun wholeFile() {
        assertTrue(Kdbx.complete(file()))
        assertTrue(Kdbx.complete(file(0)))
    }

    @Test fun anyTruncationIsCaught() {
        val f = file()
        for (n in 0 until f.size) assertFalse("обрезан до $n", Kdbx.complete(f.copyOf(n)))
        assertTrue("хвост программы для Windows", Kdbx.complete(f + ByteArray(36)))
        assertFalse(Kdbx.complete(f + ByteArray(37)))
        assertFalse(Kdbx.complete(f + byteArrayOf(1)))
        assertFalse(Kdbx.complete(f.also { it[20] = 1 }))
    }

    @Test fun notKdbx() {
        assertFalse(Kdbx.complete(ByteArray(0)))
        assertFalse(Kdbx.complete("not a vault, just text".toByteArray()))
    }

    @Test fun versions() {
        assertTrue(Updater.newer("0.1.2", "0.1.1"))
        assertTrue(Updater.newer("0.10.0", "0.9.9"))
        assertFalse(Updater.newer("0.1.1", "0.1.1"))
        assertFalse(Updater.newer("0.1.0", "0.1.1"))
    }
}
