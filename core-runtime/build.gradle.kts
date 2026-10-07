plugins { id("com.android.library") }
android {
    namespace = "com.yuno.tv.core.runtime"
    compileSdk = 37
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":core-network"))
    implementation("org.mozilla:rhino:1.7.15")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.withType<Test>().configureEach {
    inputs.property("drpyEngine",System.getenv("YUNO_DRPY_ENGINE") ?: "")
    System.getenv("YUNO_DRPY_ENGINE")?.let { inputs.file(it) }
}
