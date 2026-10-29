package com.matthew.playhome

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ConnectException
import java.net.Socket

/**
 * Talks raw HTTP to a real server on localhost. (Image decoding is Android-only, so on the JVM
 * every upload counts as "not a photo"; the happy path is covered on a device.)
 */
class UploadServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: UploadServer
    private var offered = 0

    @Before fun start() {
        server = UploadServer(tmp.root) { offered++; true }
        server.start()
    }

    @After fun stop() = server.stop()

    /** Sends [request] verbatim and returns the status code and body. */
    private fun send(request: ByteArray): Pair<Int, String> = Socket("127.0.0.1", server.port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().apply { write(request); flush() }
        val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
        text.substringAfter(' ').substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n")
    }

    private fun get(path: String) = send("GET $path HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())

    private fun post(path: String, body: ByteArray, length: Long = body.size.toLong()) =
        send("POST $path HTTP/1.1\r\nHost: tv\r\nContent-Length: $length\r\n\r\n".toByteArray() + body)

    private val path get() = "/u/${server.token}"

    @Test fun tokenIs128RandomBits() {
        assertEquals(22, server.token.length) // 16 bytes, URL-safe base64, no padding
        assertTrue(server.token.matches(Regex("[A-Za-z0-9_-]+")))
        val other = UploadServer(tmp.root) { true }
        assertTrue(other.token != server.token)
        other.stop()
    }

    @Test fun pageNeedsTheToken() {
        assertEquals(200, get(path).first)
        assertTrue(get(path).second.contains("Send to TV"))
        for (p in listOf("/", "/u/", "/u/wrong", "$path/", "$path?x=1", "/u/${server.token.dropLast(1)}", "/../$path")) {
            assertEquals("GET $p", 404, get(p).first)
        }
    }

    @Test fun pageIsLockedDown() {
        val head = Socket("127.0.0.1", server.port).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\n\r\n".toByteArray())
            s.getInputStream().readBytes().toString(Charsets.UTF_8).substringBefore("\r\n\r\n")
        }
        assertTrue(head.contains("Content-Security-Policy: default-src 'none'"))
        assertTrue(head.contains("Cache-Control: no-store"))
        assertTrue(head.contains("Referrer-Policy: no-referrer"))
    }

    @Test fun uploadsNeedTheTokenToo() = assertEquals(404, post("/u/wrong", byteArrayOf(1, 2, 3)).first)

    @Test fun tooBigIsRefusedBeforeReading() {
        assertEquals(413, post(path, ByteArray(0), length = UploadServer.MAX_UPLOAD + 1).first)
        assertEquals(0, offered)
    }

    @Test fun emptyAndMissingLengthAreRefused() {
        assertEquals(400, post(path, ByteArray(0)).first)
        assertEquals(411, send("POST $path HTTP/1.1\r\n\r\n".toByteArray()).first)
    }

    @Test fun nonImagesAreRefusedAndNothingIsKept() {
        assertEquals(415, post(path, "not a photo".toByteArray()).first)
        assertEquals(0, offered)
        assertEquals(emptyList<File>(), tmp.root.listFiles().orEmpty().toList())
    }

    @Test fun otherMethodsAreRefused() = assertEquals(405, send("DELETE $path HTTP/1.1\r\n\r\n".toByteArray()).first)

    /** The server stops reading at 8 KB and hangs up; the client sees a 400 or a reset, never the page. */
    @Test fun oversizedHeadersAreRefused() {
        val result = runCatching { send(("GET $path HTTP/1.1\r\nX: " + "a".repeat(9000) + "\r\n\r\n").toByteArray()) }
        assertTrue("got ${result.getOrNull()}", result.isFailure || result.getOrNull()?.first == 400)
    }

    /** A browser's idle "preconnect" socket mustn't hold up the real request. */
    @Test fun idleConnectionsDontBlockOthers() {
        val idle = (1..2).map { Socket("127.0.0.1", server.port) }
        val started = System.currentTimeMillis()
        assertEquals(200, get(path).first)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 2000)
        idle.forEach { it.close() }
    }

    /**
     * Idle sockets holding every handler only delay a real request, never time it out: its header
     * clock starts when a handler picks it up, not while it waits in line.
     */
    @Test fun aRequestQueuedBehindIdleSocketsStillGetsThrough() {
        val idle = (1..3).map { Socket("127.0.0.1", server.port) }
        Thread.sleep(50) // all three handlers taken; ours queues right behind them, as the old 5 s-from-accept clock ran out with theirs
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 15_000
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
            val reply = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(reply.take(40), reply.startsWith("HTTP/1.1 200"))
        }
        idle.forEach { it.close() }
    }

    /** A client trickling its headers is cut off at the deadline, however slowly it keeps sending. */
    @Test fun slowClientsAreCutOff() {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write("GET $path HTTP/1.1\r\n".toByteArray())
            val started = System.currentTimeMillis()
            val closed = runCatching { s.getInputStream().read() }.getOrDefault(-1)
            assertEquals(-1, closed)
            assertTrue("cut off after ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 7000)
        }
    }

    @Test fun stopClosesOpenConnections() {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 5000
            Thread.sleep(200) // accepted and waiting for a request line
            server.stop()
            assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
        }
    }

    @Test fun stoppedMeansGone() {
        server.stop()
        Thread.sleep(200)
        val refused = runCatching { Socket("127.0.0.1", server.port).close() }.exceptionOrNull()
        assertTrue("expected the port to be closed, got $refused", refused is ConnectException)
    }
}
