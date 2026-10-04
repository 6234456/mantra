plugins {
    application
}

dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-render"))
    // Existing repository pin, not a newly selected/downloaded LSP client stack.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
}

application {
    mainClass.set("com.xqiou.mantra.lsp.LanguageServerKt")
}
