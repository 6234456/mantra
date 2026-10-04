package com.xqiou.mantra.cli

import java.security.MessageDigest

/** Ordered comparison of the two actual graph revisions, including participating link/data resources. */
internal object DiffRevision {
    fun calculate(baseRevision: String, variantRevision: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("base" to baseRevision, "variant" to variantRevision).forEach { (label, revision) ->
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(revision.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
