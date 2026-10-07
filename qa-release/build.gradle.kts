import java.util.Properties

plugins { id("com.android.application") }
android {
    namespace = "com.yuno.tv.releaseqa"
    compileSdk = 37
    defaultConfig { applicationId = "com.yuno.tv.releaseqa"; minSdk = 26; targetSdk = 37; versionCode = 1; versionName = "1.0" }
    signingConfigs {
        create("releaseQa") {
            val credentials=Properties().apply { rootProject.file(".signing/local-release.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
            storeFile=rootProject.file(".signing/local-release.p12")
            storePassword=credentials.getProperty("storePassword");keyPassword=credentials.getProperty("keyPassword");keyAlias="yunotv"
        }
    }
    buildTypes { release { signingConfig=signingConfigs.getByName("releaseQa") } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    // Deliberately independent of the application's debug classpath and obfuscated Kotlin classes.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
