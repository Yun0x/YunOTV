plugins { id("com.android.library") }
android {
    namespace = "com.yuno.tv.core.player"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core-model"))
    implementation(project(":core-network"))
    api("androidx.media3:media3-exoplayer:1.8.0")
    api("androidx.media3:media3-exoplayer-hls:1.8.0")
    api("androidx.media3:media3-ui:1.8.0")
    api("androidx.media3:media3-datasource-okhttp:1.8.0")
}
