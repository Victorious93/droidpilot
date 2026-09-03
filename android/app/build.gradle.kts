import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing is resolved from, in order of precedence:
 *   1. Environment variables (CI):  DROIDPILOT_KEYSTORE, DROIDPILOT_KEYSTORE_PASSWORD,
 *                                   DROIDPILOT_KEY_ALIAS, DROIDPILOT_KEY_PASSWORD
 *   2. `keystore.properties` in the `android/` directory (local dev, git-ignored).
 * If neither is present the release variant is built unsigned rather than failing,
 * so that CI can still produce a lint/test/assemble signal on forks.
 * No keystore or credential is ever committed. See SECURITY.md.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(prop)

val releaseStorePath = signingValue("DROIDPILOT_KEYSTORE", "storeFile")
val hasReleaseSigning = releaseStorePath != null && file(releaseStorePath).exists()

android {
    namespace = "com.mobilemcp.pro"
    compileSdk = 35

    defaultConfig {
        // NOTE: applicationId is deliberately left as `com.mobilemcp.pro`.
        // Changing it would force every existing user to uninstall, reinstall and
        // re-grant the Accessibility permission. See ARCHITECTURE.md ("Naming").
        applicationId = "com.mobilemcp.pro"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = signingValue("DROIDPILOT_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("DROIDPILOT_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("DROIDPILOT_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
        // Written to build/reports/lint-results-*.html and consumed by CI.
        htmlReport = true
        sarifReport = true
        // Dependency versions are chosen deliberately and reviewed together; see
        // CONTRIBUTING.md. AGP 9.x in particular is a major release with breaking changes,
        // so an automated nag to jump to it is noise rather than signal.
        disable += setOf(
            // Dependency versions are chosen deliberately and reviewed together; see
            // CONTRIBUTING.md. AGP 9.x is a major release with breaking changes, so an
            // automated nag to jump to it is noise rather than signal.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            // Suggests dropping the `-v26` qualifier from mipmap-anydpi-v26 because minSdk
            // is 30. Following it fails resource linking outright: AAPT2 does not accept a
            // bare `mipmap-anydpi` directory. Verified by trying it. Reported against the
            // folder, so it cannot be suppressed with tools:ignore in the file itself.
            "ObsoleteSdkInt",
        )
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.java.websocket)

    // Instrumented tests. These are the only tests that exercise the Accessibility layer,
    // the Android Keystore and real gesture dispatch — none of which can be meaningfully
    // faked. They require an emulator or device; CI runs them on one.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
