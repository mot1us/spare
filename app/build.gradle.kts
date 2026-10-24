import java.util.Properties
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
