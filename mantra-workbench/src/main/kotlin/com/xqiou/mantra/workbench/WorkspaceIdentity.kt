package com.xqiou.mantra.workbench

import java.security.MessageDigest

/** Stable browser-recovery namespace for a canonical root, never a credential or file capability. */
internal fun WorkspaceCatalog.workspaceIdentity(): String = MessageDigest.getInstance("SHA-256")
    .digest("mantra.workspace/1\u0000$root".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
