package org.avmedia.gshockapi.io

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.ble.Connection
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.model.LifeLogFile
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * Life-log file transfer used by the DW-H5600 (decoded from the official app's traffic).
 *
 * Two characteristics are involved:
 *  - CTRL (26eb0023, write with response + notify): file announcements and acks.
 *  - DATA (26eb0024, write without response + notify): session setup and file contents.
 *
 * Flow for a category `c` (0x2e = health files incl. sleep):
 * ```
 * CTRL > 00 c ff ff ff ff ff          request all new files of category c
 * DATA > 00 00 00                     ping, repeated until the watch answers 00 00 00 (00 01 07 = busy)
 * DATA > 04 00..  /  04 01 18 00 18 00 00 00 dc 05   connection-parameter negotiation (fast)
 * DATA > 06 00..  < 06 <params>  > 06 <params>       transfer parameters, echoed back
 * CTRL > 00 c ff ff ff ff ff          start
 * per file:  CTRL < 00 c id len24 00   (echo it)
 *            DATA < 05 <payload>...    blocks of <=512 bytes, each followed by CRC-16/KERMIT (LE)
 *            CTRL < 07 c id len24 00   (echo it, then DATA > 07 00..)
 * CTRL < 04 c ff ff ff ff ff          no more files
 * DATA > 04 00..  /  04 01 30 01 40 01 04 00 dc 05   back to slow connection parameters
 * ```
 * File contents are bit-inverted (XOR 0xFF). The watch only sends files it has not sent before.
 */
class LifeLogTransferSession(private val category: Int) {
    enum class Channel { CTRL, DATA }

    sealed class Action {
        data class Write(val channel: Channel, val bytes: ByteArray, val delayMs: Long = 0) : Action()
        object Done : Action()
        data class Failed(val reason: String) : Action()
    }

    private enum class State { PING, SETUP_FAST, SETUP_PARAMS_REQ, SETUP_PARAMS_ECHO, WAIT_FILE, RECEIVING, CLOSE_1, CLOSE_2, DONE }

    private var state = State.PING
    private var pingAttempts = 0
    private var paramsEchoed = false
    private var current: Pending? = null

    val files = mutableListOf<LifeLogFile>()
    val warnings = mutableListOf<String>()

    private class Pending(val id: Int, val length: Int) {
        val raw = java.io.ByteArrayOutputStream()
    }

    private val c = category.toByte()
    private val requestAll = bytes(0x00, category, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)

    fun start(): List<Action> = listOf(Action.Write(Channel.CTRL, requestAll), Action.Write(Channel.DATA, PING))

    val isDone: Boolean get() = state == State.DONE

    fun onNotification(channel: Channel, v: ByteArray): List<Action> {
        if (v.isEmpty()) return emptyList()
        val op = v[0].toInt() and 0xFF
        return when (channel) {
            Channel.DATA -> onData(op, v)
            Channel.CTRL -> onCtrl(op, v)
        }
    }

    private fun onData(op: Int, v: ByteArray): List<Action> =
        when (state) {
            State.PING ->
                when {
                    v.contentEquals(PING) -> {
                        state = State.SETUP_FAST
                        listOf(Action.Write(Channel.DATA, CONN_QUERY))
                    }
                    op == 0x00 && ++pingAttempts < MAX_PINGS -> listOf(Action.Write(Channel.DATA, PING, delayMs = 2000))
                    op == 0x00 -> listOf(Action.Failed("watch stayed busy (${v.toHex()})"))
                    else -> emptyList()
                }
            State.SETUP_FAST ->
                if (op == 0x04) {
                    state = State.SETUP_PARAMS_REQ
                    listOf(Action.Write(Channel.DATA, CONN_FAST))
                } else {
                    emptyList()
                }
            State.SETUP_PARAMS_REQ ->
                if (op == 0x04) {
                    state = State.SETUP_PARAMS_ECHO
                    listOf(Action.Write(Channel.DATA, PARAMS_QUERY))
                } else {
                    emptyList()
                }
            State.SETUP_PARAMS_ECHO ->
                if (op == 0x06 && !paramsEchoed) {
                    // First 06 carries the watch's parameters: echo them. The second 06 is the watch's echo.
                    paramsEchoed = true
                    listOf(Action.Write(Channel.DATA, v))
                } else if (op == 0x06) {
                    state = State.WAIT_FILE
                    listOf(Action.Write(Channel.CTRL, requestAll))
                } else {
                    emptyList()
                }
            State.RECEIVING -> {
                if (op == 0x05) current?.raw?.write(v, 1, v.size - 1)
                emptyList()
            }
            State.CLOSE_1 ->
                if (op == 0x04) {
                    state = State.CLOSE_2
                    listOf(Action.Write(Channel.DATA, CONN_SLOW))
                } else {
                    emptyList()
                }
            State.CLOSE_2 ->
                if (op == 0x04) {
                    state = State.DONE
                    listOf(Action.Done)
                } else {
                    emptyList()
                }
            else -> emptyList() // e.g. the watch's echo of our 07 ack
        }

    private fun onCtrl(op: Int, v: ByteArray): List<Action> {
        if (v.size < 7 || v[1] != c) return emptyList()
        val isAll = (2..6).all { v[it] == 0xFF.toByte() }
        return when {
            op == 0x00 && !isAll && (state == State.WAIT_FILE || state == State.RECEIVING) -> {
                val length = (v[3].toInt() and 0xFF) or ((v[4].toInt() and 0xFF) shl 8) or ((v[5].toInt() and 0xFF) shl 16)
                current = Pending(v[2].toInt() and 0xFF, length)
                state = State.RECEIVING
                listOf(Action.Write(Channel.CTRL, v))
            }
            op == 0x07 && state == State.RECEIVING -> {
                current?.let { finish(it) }
                current = null
                state = State.WAIT_FILE
                listOf(Action.Write(Channel.CTRL, v), Action.Write(Channel.DATA, FILE_ACK))
            }
            op == 0x04 && isAll && (state == State.WAIT_FILE || state == State.RECEIVING) -> {
                state = State.CLOSE_1
                listOf(Action.Write(Channel.DATA, CONN_QUERY))
            }
            else -> emptyList() // echo of our own request
        }
    }

    private fun finish(p: Pending) {
        val (data, crcErrors) = reassemble(p.raw.toByteArray(), p.length)
        if (crcErrors > 0) warnings += "file 0x%02x: %d block(s) failed CRC".format(p.id, crcErrors)
        if (data.size != p.length) warnings += "file 0x%02x: expected ${p.length}B, got ${data.size}B".format(p.id)
        files += LifeLogFile(category, p.id, SleepIOFunctional.invert(data))
    }

    companion object {
        private const val MAX_PINGS = 10
        private const val BLOCK = 512

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        val PING = bytes(0x00, 0x00, 0x00)
        val CONN_QUERY = ByteArray(10).also { it[0] = 0x04 }
        val CONN_FAST = bytes(0x04, 0x01, 0x18, 0x00, 0x18, 0x00, 0x00, 0x00, 0xDC, 0x05)
        val CONN_SLOW = bytes(0x04, 0x01, 0x30, 0x01, 0x40, 0x01, 0x04, 0x00, 0xDC, 0x05)
        val PARAMS_QUERY = ByteArray(14).also { it[0] = 0x06 }
        val FILE_ACK = ByteArray(15).also { it[0] = 0x07 }

        /** Strips the per-block CRCs. Returns the on-wire (still inverted) bytes and the number of bad blocks. */
        fun reassemble(raw: ByteArray, length: Int): Pair<ByteArray, Int> {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            var bad = 0
            while (i < raw.size && out.size() < length) {
                val take = minOf(BLOCK, length - out.size(), raw.size - i)
                val crcAt = i + take
                if (crcAt + 2 <= raw.size) {
                    val expected = (raw[crcAt].toInt() and 0xFF) or ((raw[crcAt + 1].toInt() and 0xFF) shl 8)
                    if (SleepIOFunctional.crc16Kermit(raw, i, crcAt) != expected) bad++
                } else {
                    bad++
                }
                out.write(raw, i, take)
                i = crcAt + 2
            }
            return out.toByteArray() to bad
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

/** Runs a [LifeLogTransferSession] over the BLE connection. */
object LifeLogTransferIO {
    const val CATEGORY_HEALTH = 0x2E

    private val mutex = Mutex()

    data class Result(val files: List<LifeLogFile>, val warnings: List<String>, val completed: Boolean)

    suspend fun fetch(category: Int, timeoutSeconds: Int = 180): Result =
        mutex.withLock {
            if (!Connection.isServiceSupported(GetSetMode.DATA_REQUEST) || !Connection.isServiceSupported(GetSetMode.CONVOY)) {
                return Result(emptyList(), listOf("life-log transfer characteristics not available"), false)
            }

            val session = LifeLogTransferSession(category)
            val events = Channel<Pair<LifeLogTransferSession.Channel, ByteArray>>(Channel.UNLIMITED)
            Connection.setRawNotificationListener { uuid, bytes ->
                val ch =
                    if (uuid == CasioConstants.CASIO_DATA_REQUEST_CHARACTERISTIC_UUID) {
                        LifeLogTransferSession.Channel.CTRL
                    } else {
                        LifeLogTransferSession.Channel.DATA
                    }
                events.trySend(ch to bytes)
            }

            var failure: String? = null
            try {
                val finished =
                    withTimeoutOrNull(timeoutSeconds.seconds) {
                        perform(session.start())
                        while (!session.isDone) {
                            val (ch, bytes) =
                                withTimeoutOrNull(IDLE_TIMEOUT) { events.receive() }
                                    ?: run {
                                        failure = "watch stopped responding"
                                        return@withTimeoutOrNull false
                                    }
                            val actions = session.onNotification(ch, bytes)
                            actions.filterIsInstance<LifeLogTransferSession.Action.Failed>().firstOrNull()?.let {
                                failure = it.reason
                                return@withTimeoutOrNull false
                            }
                            perform(actions)
                        }
                        true
                    }
                if (finished == null) failure = "timed out"
            } finally {
                Connection.setRawNotificationListener(null)
                events.close()
            }

            val warnings = session.warnings + listOfNotNull(failure?.let { "transfer incomplete: $it" })
            Timber.i("Life-log transfer 0x%02x: %d files, warnings=%s", category, session.files.size, warnings)
            Result(session.files.toList(), warnings, failure == null)
        }

    private suspend fun perform(actions: List<LifeLogTransferSession.Action>) {
        for (a in actions) {
            if (a !is LifeLogTransferSession.Action.Write) continue
            if (a.delayMs > 0) delay(a.delayMs)
            val handle = if (a.channel == LifeLogTransferSession.Channel.CTRL) GetSetMode.DATA_REQUEST else GetSetMode.CONVOY
            Connection.writeInOrder(handle, a.bytes)
        }
    }

    private val IDLE_TIMEOUT = 15.seconds
}
