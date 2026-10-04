plugins {
    application
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }
dependencies { implementation("com.xqiou.mantra:mantra-server:${project.extra["mantraVersion"]}") }
application { mainClass.set("com.xqiou.mantra.consumer.ServerConsumerKt") }
