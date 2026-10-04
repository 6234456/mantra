package com.xqiou.mantra.core.api

/** Reproducible identity strings, detached from kernel implementation types and mutable sessions. */
data class CompiledKernelIdentity(
    val environmentId: String,
    val environmentVersion: String,
    val environmentFingerprint: String,
    val artifactId: String,
    val buildRevision: String,
    val executionContentSha256: String,
)
