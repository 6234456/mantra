package com.xqiou.abi.consumer

import com.xqiou.abi.model.PublicRecord

fun main() {
    val record = PublicRecord(7)
    check(record.component1() == 7)
    check(record.copy(value = 9).value == 9)
    println("KOTLIN_BINARY_OK")
}
