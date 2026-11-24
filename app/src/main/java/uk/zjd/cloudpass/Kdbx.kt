package uk.zjd.cloudpass

import java.security.MessageDigest

/**
 * Целостность файла KeePass без ключа (та же проверка, что на сервере, server/store.go Complete): у KDBX 4 за заголовком
 * лежит его SHA-256, а данные — цепочка блоков, которая кончается пустым блоком. Недописанный файл
 * (приложение закрыли посреди сохранения, прочитали во время записи) не отправляем и не ставим на место хорошего.
 */
object Kdbx {
    private val SIG = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())

    private fun u32(b: ByteArray, p: Int): Long = (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
        ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)

    fun isKdbx(b: ByteArray): Boolean = b.size >= 12 && SIG.indices.all { b[it] == SIG[it] } &&
        (b[4] == 0x67.toByte() || b[4] == 0x65.toByte()) && b[5] == 0xFB.toByte() && b[6] == 0x4B.toByte() && b[7] == 0xB5.toByte()

    fun complete(b: ByteArray): Boolean {
        if (!isKdbx(b)) return false
        if (b[4] == 0x65.toByte() || b[10].toInt() < 4) return true // KDB и KDBX 3 без ключа не проверить
        var p = 12
        while (true) {
            if (p + 5 > b.size) return false
            val id = b[p].toInt()
            val n = u32(b, p + 1)
            p += 5
            if (p + n > b.size) return false
            p += n.toInt()
            if (id == 0) break
        }
        if (p + 64 > b.size) return false
        val sum = MessageDigest.getInstance("SHA-256").run { update(b, 0, p); digest() }
        for (i in 0 until 32) if (sum[i] != b[p + i]) return false
        p += 64
        while (true) {
            if (p + 36 > b.size) return false
            val n = u32(b, p + 32)
            p += 36
            if (p + n > b.size) return false
            p += n.toInt()
            if (n == 0L) { // дальше ничего; программа для Windows дописывает ещё 36 нулей — читатели KeePass их пропускают
                return b.size - p <= 36 && (p until b.size).all { b[it] == 0.toByte() }
            }
        }
    }
}
