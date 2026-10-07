plugins { id("com.android.library"); id("com.google.devtools.ksp") }
android {
    namespace = "com.yuno.tv.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    testImplementation("junit:junit:4.13.2")
    api(project(":core-model"))
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("com.google.code.gson:gson:2.11.0")
    ksp("androidx.room:room-compiler:2.7.2")
}
