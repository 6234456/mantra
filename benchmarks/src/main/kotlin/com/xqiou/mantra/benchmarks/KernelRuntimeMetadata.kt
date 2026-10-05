package com.xqiou.mantra.benchmarks

import com.xqiou.mantra.core.api.RuntimeVersions
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Hash the kernel class resource's local JAR without treating a source baseline as runtime provenance. */
internal fun kernelRuntimeJarSha256(): String {
    val classResource = "com/xqiou/normein/dsl/runtime/DslEvaluationEngine.class"
    val resource = RuntimeVersions::class.java.classLoader.getResource(classResource) ?: return "unavailable"
    val connection = resource.openConnection() as? JarURLConnection ?: return "unavailable"
    connection.useCaches = false
    val jar = connection.jarFileURL
    if (jar.protocol != "file") return "unavailable"
    val path = Path.of(jar.toURI())
    if (!Files.isRegularFile(path)) return "unavailable"
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
