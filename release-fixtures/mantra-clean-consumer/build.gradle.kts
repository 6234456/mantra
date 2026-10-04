plugins {
    kotlin("jvm") version "2.2.20" apply false
}

val mantraVersion = providers.gradleProperty("mantraVersion").get()
val mantraRepository = providers.gradleProperty("mantraRepository").get()
val normeinRepository = providers.gradleProperty("normeinRepository").get()

subprojects {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(mantraRepository)
                    metadataSources {
                        mavenPom()
                        ignoreGradleMetadataRedirection()
                        artifact()
                    }
                }
            }
            filter { includeGroup("com.xqiou.mantra") }
        }
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(normeinRepository)
                    metadataSources {
                        mavenPom()
                        ignoreGradleMetadataRedirection()
                        artifact()
                    }
                }
            }
            filter { includeGroup("com.xqiou") }
        }
        mavenCentral()
    }
    extra["mantraVersion"] = mantraVersion
}

tasks.register("smoke") {
    dependsOn(subprojects.map { "${it.path}:run" })
}
