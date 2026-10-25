import java.util.Properties
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val releaseVersion = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
val signingPath = providers.environmentVariable("SPARE_KEYSTORE").orNull
android {
    namespace = "io.github.mot1us.spare"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.mot1us.spare"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
        buildConfigField("boolean", "PRERELEASE_UPDATES", (releaseVersion.getProperty("releaseChannel") == "prerelease").toString())
        testInstrumentationRunner = "io.github.mot1us.spare.SpareTestRunner"
    }
    signingConfigs {
        if (signingPath != null) create("distribution") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("SPARE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("SPARE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("SPARE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-dev" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingPath != null) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    lint {
        abortOnError = true; warningsAsErrors = true; checkReleaseBuilds = true
        // These are update suggestions, not defects: the compatible toolchain is deliberately pinned.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        // API 36 is the tested compatibility baseline; adopting API 37 is a separate tested migration.
    }
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/test-fixtures"))
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// Fetch this large test-only media once; pin its bytes and keep it out of release assets.
val streamFixtureHash = "454aa16b73fb477ee971540bff53948749a96117643994df1265e9f6b456b5bb"
val streamFixture = layout.buildDirectory.file("generated/test-fixtures/stall-av.ts")
fun fixtureDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
val prepareStreamFixture by tasks.registering {
    inputs.property("sha256", streamFixtureHash)
    outputs.file(streamFixture)
    outputs.upToDateWhen { streamFixture.get().asFile.let { it.isFile && fixtureDigest(it.readBytes()) == streamFixtureHash } }
    doLast {
        val output = streamFixture.get().asFile
        if (output.isFile && fixtureDigest(output.readBytes()) == streamFixtureHash) return@doLast
        output.parentFile.mkdirs()
        val temporary = output.resolveSibling("stall-av.ts.tmp")
        val connection = URI("https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/test_data/src/test/assets/media/ts/bbb_2500ms.ts").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 30000; connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) { "Could not download the pinned Media3 test fixture" }
            connection.inputStream.use { input -> temporary.outputStream().use { target ->
                val buffer = ByteArray(16384); var total = 0
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; check(total <= 2 * 1024 * 1024) { "Test fixture exceeds its size limit" }
                    target.write(buffer, 0, count)
                }
            } }
            check(fixtureDigest(temporary.readBytes()) == streamFixtureHash) { "Test fixture SHA-256 does not match" }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete(); connection.disconnect() }
    }
}
// Lint models also consume the AndroidTest asset roots.
tasks.matching { it.name.contains("AndroidTest") }.configureEach { dependsOn(prepareStreamFixture) }
