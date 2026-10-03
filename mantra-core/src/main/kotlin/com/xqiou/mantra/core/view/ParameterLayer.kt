package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.model.Value

/** One declared value of a parameter, retained even when a later layer overrides it. */
data class ParameterLayer(val layer: String, val value: Value?, val set: String? = null,
    val reference: String? = null, val declared: Boolean = true)
