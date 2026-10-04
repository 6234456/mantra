package com.xqiou.mantra.consumer

import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageSchemaBinding
import com.xqiou.mantra.packages.SchemaVersionMode
import com.xqiou.mantra.packages.SemanticVersion
import com.xqiou.mantra.packages.VersionRange
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

fun main() {
    val schema = PackageSchemaBinding(SchemaIdentity("consumer/package", "1.0.0"), SchemaVersionMode.SEMVER)
    check(schema.identity.version == "1.0.0")
    val version = SemanticVersion.parse("1.0.0+consumer")
    check(VersionRange.parse(">=1.0.0 <2.0.0").contains(version))
    val file = Files.createTempFile("mantra-pom-package-", ".jar")
    try {
        val manifest = """{"format":"mantra.package/1","id":"consumer","version":"1.0.0","engine":">=0.0.0","resources":[],"schemas":[],"parameters":[],"layouts":[],"cases":[],"dependencies":[]}"""
        JarOutputStream(Files.newOutputStream(file)).use { output ->
            output.putNextEntry(JarEntry("manifest.json"))
            output.write(manifest.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
        URLClassLoader(arrayOf(file.toUri().toURL()), null).use { loader ->
            // Exercises actual manifest JSON parsing; Jackson must be reachable from the package POM.
            val snapshot = PackageLoader.classpath("", loader, version, PackageLimits(4096, 4096, 8192, 16, 16, 16))
            check(snapshot.manifest.identity.id == "consumer")
            check(snapshot.manifestByteLength > 0 && snapshot.manifest.resources.isEmpty())
        }
    } finally {
        Files.deleteIfExists(file)
    }
    println("MANTRA_KOTLIN_PACKAGES_CONSUMER_OK")
}
