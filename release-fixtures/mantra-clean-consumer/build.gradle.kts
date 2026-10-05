plugins {
    kotlin("jvm") version "2.2.20" apply false
}

val mantraVersion = providers.gradleProperty("mantraVersion").get()
val mantraRepository = providers.gradleProperty("mantraRepository").get()
val mantraMetadataMode = providers.gradleProperty("mantraMetadataMode").getOrElse("pom")
require(mantraMetadataMode in setOf("pom", "gradle")) { "mantraMetadataMode must be pom or gradle" }

subprojects {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(mantraRepository)
                    if (mantraMetadataMode == "pom") {
                        metadataSources {
                            mavenPom()
                            ignoreGradleMetadataRedirection()
                            artifact()
                        }
                    }
                }
            }
            filter { includeGroup("com.xqiou.mantra") }
        }
        mavenCentral {
            if (mantraMetadataMode == "pom") {
                metadataSources {
                    mavenPom()
                    ignoreGradleMetadataRedirection()
                    artifact()
                }
            }
        }
    }
    extra["mantraVersion"] = mantraVersion
}

tasks.register("smoke") {
    dependsOn(subprojects.map { "${it.path}:run" })
}
