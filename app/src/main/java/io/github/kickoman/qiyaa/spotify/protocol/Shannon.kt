package io.github.kickoman.qiyaa.spotify.protocol

class Shannon(key: ByteArray) {
    private val r = IntArray(WORDS)
    private val crc = IntArray(WORDS)
    private val initialR: IntArray
    private var konst = 0
    private var streamWord = 0
    private var macWord = 0
    private var pendingBits = 0

    init {
        r[0] = 1
        r[1] = 1
        for (i in 2 until WORDS) r[i] = r[i - 1] + r[i - 2]
        konst = INIT_KONST
        loadKey(key)
        konst = r[0]
        initialR = r.copyOf()
    }

    fun nonce(value: Int) {
        initialR.copyInto(r)
        konst = INIT_KONST
        loadKey(ByteArray(4) { (value ushr (24 - 8 * it)).toByte() })
        konst = r[0]
        pendingBits = 0
    }

    fun encrypt(data: ByteArray, from: Int = 0, length: Int = data.size - from) {
        process(data, from, length, true)
    }

    fun decrypt(data: ByteArray, from: Int = 0, length: Int = data.size - from) {
        process(data, from, length, false)
    }

    fun finish(length: Int = MAC_SIZE): ByteArray {
        if (pendingBits != 0) mac(macWord)
        cycle()
        r[KEY_WORD] = r[KEY_WORD] xor INIT_KONST xor (pendingBits shl 3)
        pendingBits = 0
        for (i in 0 until WORDS) r[i] = r[i] xor crc[i]
        diffuse()
        val output = ByteArray(length)
        for (offset in 0 until length step 4) {
            cycle()
            for (i in 0 until minOf(4, length - offset)) {
                output[offset + i] = (streamWord ushr (8 * i)).toByte()
            }
        }
        return output
    }

    private fun process(data: ByteArray, from: Int, length: Int, encrypt: Boolean) {
        var at = from
        val end = from + length
        if (pendingBits != 0) {
            while (pendingBits != 0 && at < end) at = processByte(data, at, encrypt)
            if (pendingBits != 0) return
            mac(macWord)
        }
        while (end - at >= 4) {
            cycle()
            var word = readLe(data, at)
            if (encrypt) {
                mac(word)
                word = word xor streamWord
            } else {
                word = word xor streamWord
                mac(word)
            }
            writeLe(data, at, word)
            at += 4
        }
        if (at < end) {
            cycle()
            macWord = 0
            pendingBits = 32
            while (at < end) at = processByte(data, at, encrypt)
        }
    }

    private fun processByte(data: ByteArray, at: Int, encrypt: Boolean): Int {
        val shift = 32 - pendingBits
        val key = (streamWord ushr shift).toByte()
        if (encrypt) {
            macWord = macWord xor ((data[at].toInt() and 0xff) shl shift)
            data[at] = (data[at].toInt() xor key.toInt()).toByte()
        } else {
            data[at] = (data[at].toInt() xor key.toInt()).toByte()
            macWord = macWord xor ((data[at].toInt() and 0xff) shl shift)
        }
        pendingBits -= 8
        return at + 1
    }

    private fun cycle() {
        var t = r[12] xor r[13] xor konst
        t = sbox1(t) xor Integer.rotateLeft(r[0], 1)
        for (i in 1 until WORDS) r[i - 1] = r[i]
        r[WORDS - 1] = t
        t = sbox2(r[2] xor r[15])
        r[0] = r[0] xor t
        streamWord = t xor r[8] xor r[12]
    }

    private fun diffuse() {
        repeat(WORDS) { cycle() }
    }

    private fun loadKey(key: ByteArray) {
        for (offset in key.indices step 4) {
            val word = ByteArray(4)
            key.copyInto(word, 0, offset, minOf(offset + 4, key.size))
            r[KEY_WORD] = r[KEY_WORD] xor readLe(word, 0)
            cycle()
        }
        r[KEY_WORD] = r[KEY_WORD] xor key.size
        cycle()
        r.copyInto(crc)
        diffuse()
        for (i in 0 until WORDS) r[i] = r[i] xor crc[i]
    }

    private fun mac(word: Int) {
        val t = crc[0] xor crc[2] xor crc[15] xor word
        for (i in 1 until WORDS) crc[i - 1] = crc[i]
        crc[WORDS - 1] = t
        r[KEY_WORD] = r[KEY_WORD] xor word
    }

    companion object {
        const val MAC_SIZE = 4
        private const val WORDS = 16
        private const val KEY_WORD = 13
        private const val INIT_KONST = 0x6996c53a

        private fun sbox1(word: Int): Int {
            var w = word
            w = w xor (Integer.rotateLeft(w, 5) or Integer.rotateLeft(w, 7))
            w = w xor (Integer.rotateLeft(w, 19) or Integer.rotateLeft(w, 22))
            return w
        }

        private fun sbox2(word: Int): Int {
            var w = word
            w = w xor (Integer.rotateLeft(w, 7) or Integer.rotateLeft(w, 22))
            w = w xor (Integer.rotateLeft(w, 5) or Integer.rotateLeft(w, 19))
            return w
        }

        private fun readLe(data: ByteArray, at: Int): Int = (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)

        private fun writeLe(data: ByteArray, at: Int, value: Int) {
            for (i in 0 until 4) data[at + i] = (value ushr (8 * i)).toByte()
        }
    }
}
