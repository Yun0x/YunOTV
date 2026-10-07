plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.yuno.tv.core.ui"
    buildFeatures { compose = true }
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core-model"))
    api("androidx.compose.ui:ui:1.9.0")
    api("androidx.compose.foundation:foundation:1.9.0")
    api("androidx.tv:tv-material:1.0.1")
    api("androidx.compose.material3:material3:1.3.2")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    api("io.coil-kt:coil-compose:2.7.0")
}
