package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Crypto
import io.github.kickoman.qiyaa.spotify.protocol.ProtoReader
import io.github.kickoman.qiyaa.spotify.protocol.ProtoWriter
import io.github.kickoman.qiyaa.spotify.protocol.ServerKey
import io.github.kickoman.qiyaa.spotify.protocol.Shannon
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class ApPacket(val command: Int, val payload: ByteArray)

object FakeAccessPointKeys {
    private val case = SpotifyVectors.cases("handshake").first()
    private val server = case.obj("server")
    val dhPrime: ByteArray = case.hex("dhPrime")
    val dhPrivate: ByteArray = server.hex("dhPrivate")
    val dhPublic: ByteArray = server.hex("dhPublic")
    val signature: ByteArray = server.hex("signature")
    val serverKey = ServerKey(Bytes.of(server.hex("rsaModulus")), server.int("rsaExponent"))
}

class FakeAccessPoint {
    private enum class Stage { HELLO, ANSWER, PACKETS }

    private var stage = Stage.HELLO
    private var incoming = ByteArray(0)
    private val outgoing = ByteArrayOutputStream()
    private var transcript = ByteArray(0)
    private var material = ByteArray(0)
    private var sendCipher: Shannon? = null
    private var receiveCipher: Shannon? = null
    private var sendNonce = 0
    private var receiveNonce = 0
    private var pendingCommand = NO_COMMAND
    private var pendingSize = 0

    var challengeAnswered = false
        private set
    val packets = mutableListOf<ApPacket>()

    fun receive(bytes: ByteArray) {
        incoming += bytes
        var progressed = true
        while (progressed) progressed = step()
    }

    fun takeOutgoing(): ByteArray = outgoing.toByteArray().also { outgoing.reset() }

    fun send(command: Int, payload: ByteArray) {
        val cipher = checkNotNull(sendCipher) { "no packets before the handshake" }
        val packet =
            byteArrayOf(command.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        cipher.nonce(sendNonce++)
        cipher.encrypt(packet)
        outgoing.write(packet)
        outgoing.write(cipher.finish())
    }

    fun welcome(username: String, blob: ByteArray) {
        val message = ProtoWriter().string(
            10,
            username,
        ).varint(20, 1).varint(25, 1).varint(30, 1).bytes(40, blob)
        send(AP_WELCOME, message.toByteArray())
    }

    private fun step(): Boolean = when (stage) {
        Stage.HELLO -> readHello()
        Stage.ANSWER -> readAnswer()
        Stage.PACKETS -> readPacket()
    }

    private fun readHello(): Boolean {
        if (incoming.size < 6) return false
        val size = u32(incoming, 2)
        if (incoming.size < size) return false
        transcript = incoming.copyOf(size)
        val clientKey = fieldOf(fieldOf(fieldOf(incoming.copyOfRange(6, size), 50), 10), 10)
        consume(size)
        val dh = ProtoWriter()
            .bytes(10, FakeAccessPointKeys.dhPublic)
            .varint(20, 1)
            .bytes(30, FakeAccessPointKeys.signature)
        val response = ProtoWriter().message(10, ProtoWriter().message(10, ProtoWriter().message(10, dh)))
        val body = response.toByteArray()
        val reply = be32(4 + body.size) + body
        transcript += reply
        outgoing.write(reply)
        val shared = Crypto.modPow(clientKey, FakeAccessPointKeys.dhPrivate, FakeAccessPointKeys.dhPrime)!!
        val secret = shared.dropWhile { it == 0.toByte() }.toByteArray()
        material = (1..5).map { Crypto.hmacSha1(secret, transcript, byteArrayOf(it.toByte())) }
            .reduce(ByteArray::plus)
        receiveCipher = Shannon(material.copyOfRange(20, 52))
        sendCipher = Shannon(material.copyOfRange(52, 84))
        stage = Stage.ANSWER
        return true
    }

    private fun readAnswer(): Boolean {
        if (incoming.size < 4) return false
        val size = u32(incoming, 0)
        if (incoming.size < size) return false
        val hmac = fieldOf(fieldOf(fieldOf(incoming.copyOfRange(4, size), 10), 10), 10)
        challengeAnswered = hmac.contentEquals(Crypto.hmacSha1(material.copyOfRange(0, 20), transcript))
        consume(size)
        stage = Stage.PACKETS
        return true
    }

    private fun readPacket(): Boolean {
        val cipher = checkNotNull(receiveCipher)
        if (pendingCommand == NO_COMMAND) {
            if (incoming.size < 3) return false
            val header = incoming.copyOfRange(0, 3)
            cipher.nonce(receiveNonce++)
            cipher.decrypt(header)
            pendingCommand = header[0].toInt() and 0xff
            pendingSize = ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
            consume(3)
        }
        if (incoming.size < pendingSize + Shannon.MAC_SIZE) return false
        val payload = incoming.copyOfRange(0, pendingSize)
        cipher.decrypt(payload)
        val authentic = cipher.finish().contentEquals(
            incoming.copyOfRange(
                pendingSize,
                pendingSize + Shannon.MAC_SIZE,
            ),
        )
        consume(pendingSize + Shannon.MAC_SIZE)
        if (authentic) packets += ApPacket(pendingCommand, payload)
        pendingCommand = NO_COMMAND
        return true
    }

    private fun consume(count: Int) {
        incoming = incoming.copyOfRange(count, incoming.size)
    }

    companion object {
        const val PING = 0x04
        const val REQUEST_KEY = 0x0c
        const val AES_KEY = 0x0d
        const val AES_KEY_ERROR = 0x0e
        const val COUNTRY_CODE = 0x1b
        const val PRODUCT_INFO = 0x50
        const val LOGIN = 0xab
        const val AP_WELCOME = 0xac
        const val AUTH_FAILURE = 0xad
        private const val NO_COMMAND = -1
    }
}

class FakeAccessPointServer : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val received = CopyOnWriteArrayList<ApPacket>()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var accepted = 0
    private var closed = 0
    private var keyRequests = 0

    val address: String = "127.0.0.1:${server.localPort}"

    @Volatile
    var welcomeBlob = byteArrayOf(9, 9, 9)

    @Volatile
    var country = "BY"

    @Volatile
    var product = "premium"

    @Volatile
    var loginRefusal: Int? = null

    @Volatile
    var key = ByteArray(16) { 0x5a }

    @Volatile
    var keyRefusal: (Int) -> Int? = { null }

    @Volatile
    var answerKeys = true

    @Volatile
    var loginGate = CountDownLatch(0)

    val connectionCount: Int get() = lock.withLock { accepted }

    val closedCount: Int get() = lock.withLock { closed }

    val packets: List<ApPacket> get() = received.toList()

    init {
        thread(isDaemon = true, name = "fake-access-point") { acceptAll() }
    }

    fun logins(): List<ApPacket> = packets.filter { it.command == FakeAccessPoint.LOGIN }

    fun keyPackets(): List<ApPacket> = packets.filter { it.command == FakeAccessPoint.REQUEST_KEY }

    fun awaitPackets(command: Int, count: Int = 1) = await {
        received.count { it.command == command } >= count
    }

    fun awaitClosed(count: Int) = await { closed >= count }

    fun awaitConnections(count: Int) = await { accepted >= count }

    fun dropConnections() {
        for (socket in sockets) closeQuietly(socket)
        sockets.clear()
    }

    override fun close() {
        closeQuietly(server)
        dropConnections()
        loginGate.countDown()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        lock.withLock {
            while (!condition()) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "the fake access point waited $WAIT_MS ms in vain" }
                changed.awaitNanos(left)
            }
        }
    }

    private fun acceptAll() {
        while (true) {
            val socket =
                try {
                    server.accept()
                } catch (ignored: IOException) {
                    return
                }
            sockets += socket
            lock.withLock {
                accepted++
                changed.signalAll()
            }
            thread(isDaemon = true, name = "fake-access-point-connection") { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        val accessPoint = FakeAccessPoint()
        var handled = 0
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                accessPoint.receive(buffer.copyOf(count))
                while (handled < accessPoint.packets.size) {
                    val packet = accessPoint.packets[handled++]
                    lock.withLock {
                        received += packet
                        changed.signalAll()
                    }
                    respond(accessPoint, packet)
                }
                val out = accessPoint.takeOutgoing()
                if (out.isNotEmpty()) output.write(out)
            }
        } catch (ignored: IOException) {
            // The client or the test closed the connection.
        } finally {
            closeQuietly(socket)
            lock.withLock {
                closed++
                changed.signalAll()
            }
        }
    }

    private fun respond(accessPoint: FakeAccessPoint, packet: ApPacket) {
        when (packet.command) {
            FakeAccessPoint.LOGIN -> {
                loginGate.await(WAIT_MS, TimeUnit.MILLISECONDS)
                val refusal = loginRefusal
                if (refusal != null) {
                    accessPoint.send(
                        FakeAccessPoint.AUTH_FAILURE,
                        ProtoWriter().varint(10, refusal.toLong()).toByteArray(),
                    )
                    return
                }
                val credentials = fieldOf(packet.payload, 10)
                val username = fieldOf(credentials, 10).toString(Charsets.UTF_8).ifEmpty { "owner" }
                accessPoint.welcome(username, welcomeBlob)
                accessPoint.send(FakeAccessPoint.COUNTRY_CODE, country.toByteArray())
                val info = "<products><product><type>$product</type></product></products>"
                accessPoint.send(FakeAccessPoint.PRODUCT_INFO, info.toByteArray())
            }

            FakeAccessPoint.REQUEST_KEY -> if (answerKeys && packet.payload.size >= 40) {
                val attempt = lock.withLock { ++keyRequests }
                val sequence = packet.payload.copyOfRange(36, 40)
                val refusal = keyRefusal(attempt)
                if (refusal != null) {
                    val code = byteArrayOf((refusal ushr 8).toByte(), refusal.toByte())
                    accessPoint.send(FakeAccessPoint.AES_KEY_ERROR, sequence + code)
                } else {
                    accessPoint.send(FakeAccessPoint.AES_KEY, sequence + key)
                }
            }
        }
    }

    private companion object {
        const val BACKLOG = 50
        const val WAIT_MS = 10_000L
    }
}

fun fieldOf(message: ByteArray, field: Int): ByteArray {
    val reader = ProtoReader(message)
    while (reader.next()) {
        if (reader.field == field) return reader.bytes()
    }
    return ByteArray(0)
}

fun varintOf(message: ByteArray, field: Int): Long? {
    val reader = ProtoReader(message)
    while (reader.next()) {
        if (reader.field == field) return reader.varint
    }
    return null
}

private fun u32(data: ByteArray, at: Int): Int =
    (0 until 4).fold(0) { value, i -> (value shl 8) or (data[at + i].toInt() and 0xff) }

private fun be32(value: Int): ByteArray = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

private fun closeQuietly(resource: Closeable) {
    try {
        resource.close()
    } catch (ignored: IOException) {
        // Already closed.
    }
}
