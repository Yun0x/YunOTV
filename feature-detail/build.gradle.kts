plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.yuno.tv.feature.detail"
    buildFeatures { compose = true }
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-config"))
}
