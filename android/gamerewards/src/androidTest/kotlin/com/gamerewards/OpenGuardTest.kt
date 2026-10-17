package com.gamerewards

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HostActivity : Activity()

/** Taps that reach the host after /v1/launch answered, before or while the portal starts, must not open a second portal. */
class OpenGuardTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test
    fun tapsBeforeTheHostIsBackAreIgnored() {
        val launches = AtomicInteger()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) { // stands in for the server: every launch succeeds, every page is a stub
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    if (reader.readLine().orEmpty().startsWith("POST /v1/launch ")) launches.incrementAndGet()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() } // ASCII JSON, so chars == bytes
                    val body = """{"portalUrl":"$base/portal/"}"""
                    it.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                    )
                }
            }
        }
        try {
            GameRewards.init(inst.targetContext, GameRewardsConfig("demo", base, 0, devFakeProof = true))
            val host = inst.startActivitySync(Intent(inst.targetContext, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val portals = inst.addMonitor(GameRewardsActivity::class.java.name, null, false) // counts starts
            val errors = mutableListOf<String>()
            val tap = { inst.runOnMainSync { GameRewards.open(host, "u-1") { errors += it } } }

            tap()
            waitUntil { portals.hits == 1 }
            tap() // the launch answered; the portal isn't up yet
            val portal = portals.waitForActivityWithTimeout(5_000) ?: error("portal never started")
            tap() // queued on the host while the portal started
            SystemClock.sleep(500)
            inst.waitForIdleSync()
            assertEquals(1, launches.get())
            assertEquals(1, portals.hits)
            assertEquals(emptyList<String>(), errors)

            // Closing the portal brings the host back, and the next tap launches again.
            inst.runOnMainSync { portal.finish() }
            waitUntil { tap(); launches.get() == 2 }
            waitUntil { portals.hits == 2 }
            assertEquals(emptyList<String>(), errors)
            val second = portals.waitForActivityWithTimeout(5_000)
            inst.runOnMainSync { second?.finish(); host.finish() }
        } finally {
            server.close()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "timed out" }
            SystemClock.sleep(20)
        }
    }
}
