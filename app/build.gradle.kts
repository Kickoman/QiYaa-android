plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

val releaseTag: String? = providers.gradleProperty("qiyaaVersion").orNull
val releaseVersion: List<Int>? =
    releaseTag?.let { tag ->
        val match =
            Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag)
                ?: throw GradleException("qiyaaVersion must look like v1.2.3, got \"$tag\"")
        val parts = match.destructured.toList().map(String::toInt)
        if (parts[1] >= 100 || parts[2] >= 100) {
            throw GradleException("qiyaaVersion $tag: minor and patch must be below 100 to fit versionCode")
        }
        parts
    }

val signingEnvironment: Map<String, String?> =
    listOf("QIYAA_KEYSTORE_PATH", "QIYAA_KEYSTORE_PASSWORD", "QIYAA_KEY_ALIAS", "QIYAA_KEY_PASSWORD")
        .associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotEmpty) }
val canSign = signingEnvironment.values.all { it != null }
if (releaseTag != null && !canSign) {
    val missing = signingEnvironment.filterValues { it == null }.keys.joinToString()
    throw GradleException("qiyaaVersion $releaseTag needs a signing key; not set: $missing")
}

android {
    namespace = "io.github.kickoman.qiyaa"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kickoman.qiyaa"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersion?.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
            ?: 1
        versionName = releaseVersion?.joinToString(".") ?: "0.0.0-dev"
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(signingEnvironment.getValue("QIYAA_KEYSTORE_PATH")!!)
                storePassword = signingEnvironment.getValue("QIYAA_KEYSTORE_PASSWORD")
                keyAlias = signingEnvironment.getValue("QIYAA_KEY_ALIAS")
                keyPassword = signingEnvironment.getValue("QIYAA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // Freshness checks: the verdict depends on what the machine has installed, not on the code.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
