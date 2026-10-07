plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
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
