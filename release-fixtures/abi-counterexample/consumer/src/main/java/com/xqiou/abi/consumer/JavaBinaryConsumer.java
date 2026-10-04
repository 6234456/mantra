package com.xqiou.abi.consumer;

import com.xqiou.abi.model.PublicRecord;

public final class JavaBinaryConsumer {
    public static void main(String[] args) {
        PublicRecord record = new PublicRecord(7);
        if (record.getValue() != 7) throw new AssertionError("Current value differs");
        System.out.println("JAVA_BINARY_OK");
    }
}
