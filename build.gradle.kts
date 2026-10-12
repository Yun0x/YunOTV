plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}

val downloadYunoSources = tasks.register<Exec>("downloadYunoSources") {
    group = "yuno sources"
    description = "Downloads source documents into staging; never modifies packaged assets."
    commandLine("python3", rootProject.file("scripts/yuno-source-compiler.py"), "fetch", "--root", rootProject.projectDir)
}
tasks.register<Exec>("syncYunoSources") {
    group = "yuno sources"
    description = "Explicitly compile and atomically publish the offline source bundle and JS/JAR assets."
    dependsOn(":core-config:compileYunoSourceModels")
    commandLine("python3", rootProject.file("scripts/yuno-source-compiler.py"), "publish", "--root", rootProject.projectDir)
}

// A project-wide compatibility invariant, checked during every Gradle configuration.
subprojects {
    afterEvaluate {
        extensions.findByType(com.android.build.api.dsl.ApplicationExtension::class.java)?.let {
            check(it.defaultConfig.minSdk == 26) { "$path must keep minSdk = 26" }
        }
        extensions.findByType(com.android.build.api.dsl.LibraryExtension::class.java)?.let {
            check(it.defaultConfig.minSdk == 26) { "$path must keep minSdk = 26" }
        }
    }
}
