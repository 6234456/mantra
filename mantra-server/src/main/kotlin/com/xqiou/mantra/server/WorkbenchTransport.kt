package com.xqiou.mantra.server

/** JDK 21 HTTP transport defaults, installed before this service creates its first HttpServer.
 * These provider settings are process-wide and read once by the JDK. Embedded hosts that already
 * created another HttpServer must set equivalent JVM startup properties themselves.
 */
object WorkbenchTransport {
    @Synchronized
    fun installDefaults() {
        val settings = mapOf(
            "jdk.httpserver.maxConnections" to "128",
            "sun.net.httpserver.maxReqHeaders" to "100",
            "sun.net.httpserver.maxReqHeaderSize" to "32768",
            "sun.net.httpserver.maxReqTime" to "30",
            "sun.net.httpserver.maxRspTime" to "300",
            "sun.net.httpserver.timerMillis" to "1000",
        )
        settings.forEach { (key, value) -> if (System.getProperty(key) == null) System.setProperty(key, value) }
    }
}
