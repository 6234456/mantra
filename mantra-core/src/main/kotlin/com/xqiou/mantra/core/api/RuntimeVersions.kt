package com.xqiou.mantra.core.api

/** Versions embedded by the actual artifact build, without searching the caller's filesystem. */
object RuntimeVersions {
    val mantra: String get() = BuildVersions.MANTRA
    val normein: String get() = BuildVersions.NORMEIN
}
