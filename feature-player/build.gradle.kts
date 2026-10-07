plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.yuno.tv.feature.player"
    buildFeatures { compose = true }
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-config"))
    implementation(project(":core-player"))
    implementation("androidx.activity:activity-compose:1.10.1")
}
