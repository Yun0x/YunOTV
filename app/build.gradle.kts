import java.util.zip.ZipFile
import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.yuno.tv"
    buildFeatures { compose = true; buildConfig = true }
    compileSdk = 37
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.yuno.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
    }
    signingConfigs {
        create("localRelease") {
            storeFile = rootProject.file(".signing/local-release.p12")
            val credentials = Properties().apply {
                rootProject.file(".signing/local-release.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
            }
            storePassword = credentials.getProperty("storePassword")
            keyAlias = "yunotv"
            keyPassword = credentials.getProperty("keyPassword")
        }
        if (System.getenv("YUNO_KEYSTORE") != null) {
            create("distribution") {
                storeFile = file(System.getenv("YUNO_KEYSTORE"))
                storePassword = System.getenv("YUNO_STORE_PASSWORD")
                keyAlias = System.getenv("YUNO_KEY_ALIAS")
                keyPassword = System.getenv("YUNO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        if (providers.gradleProperty("yunoQaReleaseSignature").orNull == "true") {
            debug { signingConfig = signingConfigs.getByName("localRelease") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("distribution") ?: signingConfigs.getByName("localRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { abortOnError = true; checkDependencies = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.register("verifyApi26Dependencies") {
    group = "verification"
    description = "Rejects every runtime AAR that requires Android API above 26."
    doLast {
        check(android.defaultConfig.minSdk == 26) { "Yuno TV must keep minSdk = 26" }
        val report = layout.buildDirectory.file("reports/api26-dependencies.txt").get().asFile
        report.parentFile.mkdirs()
        val lines = configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
            attributes.attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), "aar")
            componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            isLenient = true
        }.artifacts.artifacts
            .filter { it.file.extension == "aar" }.map { artifact ->
                val manifest = ZipFile(artifact.file).use { zip ->
                    zip.getInputStream(zip.getEntry("AndroidManifest.xml")).bufferedReader().use { it.readText() }
                }
                val minimum = Regex("minSdkVersion=\"(\\d+)\"").find(manifest)?.groupValues?.get(1)?.toInt() ?: 1
                check(minimum <= 26) { "${artifact.id.componentIdentifier.displayName} requires API $minimum" }
                "${artifact.id.componentIdentifier.displayName} minSdk=$minimum"
            }.sorted()
        report.writeText(lines.joinToString("\n") + "\n")
        logger.lifecycle("Verified ${lines.size} runtime AARs: every minSdk <= 26")
    }
}

dependencies {
    androidTestImplementation("androidx.room:room-runtime:2.7.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(project(":core-runtime"))
    implementation(project(":core-ui"))
    implementation(project(":core-config"))
    implementation(project(":core-player"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(project(":feature-home"))
    implementation(project(":feature-search"))
    implementation(project(":feature-detail"))
    implementation(project(":feature-player"))
    implementation(project(":feature-history"))
    implementation(project(":feature-favorite"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-source"))
    implementation(project(":feature-live"))
}

// The root bundle is copied by the Variant API as a generated, read-only asset.
abstract class PrepareBundleAssets : DefaultTask() {
    @get:InputFile abstract val bundle: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun prepare() {
        val target = outputDirectory.get().asFile.resolve("sources.txt.json")
        target.parentFile.mkdirs()
        bundle.get().asFile.copyTo(target, overwrite = true)
    }
}
val bundleAssets = tasks.register<PrepareBundleAssets>("prepareBundleAssets") {
    bundle.set(rootProject.layout.projectDirectory.file("sources.txt.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sourceBundle/assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleAssets, PrepareBundleAssets::outputDirectory)
}
