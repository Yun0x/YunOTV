plugins { id("com.android.library") }
android {
    namespace = "com.yuno.tv.core.config"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    api(project(":core"))
    api(project(":core-network"))
    api(project(":core-database"))
    implementation("androidx.datastore:datastore-preferences:1.1.7")
}

tasks.withType<Test>().configureEach {
    systemProperty("yuno.project.root", rootProject.projectDir.absolutePath)
    systemProperty("yuno.remote.integration", System.getenv("YUNO_RUN_REMOTE_INTEGRATION") ?: "0")
    inputs.property("remoteIntegration", System.getenv("YUNO_RUN_REMOTE_INTEGRATION") ?: "0")
}
