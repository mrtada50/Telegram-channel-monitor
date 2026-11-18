plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Telegram account login (TDLib) is optional and OFF by default, so the normal build never depends on it.
// Turn it on with enableTdlib=true in gradle.properties, plus either app/libs/tdlib.aar or tdlibDep=<coordinate>.
val enableTdlib = providers.gradleProperty("enableTdlib").orNull == "true"
val tdlibDep: String? = providers.gradleProperty("tdlibDep").orNull

android {
    namespace = "com.tgmonitor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tgmonitor.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    // A FIXED signing key (from CI secrets) lets every new build install over the old one with no uninstall.
    // Without the secrets it falls back to the auto-generated debug key (each CI build then has a new signature).
    signingConfigs {
        create("app") {
            val path = System.getenv("KEYSTORE_FILE")
            if (!path.isNullOrBlank() && file(path).exists()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    val fixedKey = signingConfigs.getByName("app").storeFile != null

    buildTypes {
        debug {
            if (fixedKey) signingConfig = signingConfigs.getByName("app")
        }
        release {
            // Optimized (R8) and much smoother than debug.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (fixedKey) signingConfigs.getByName("app") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    testOptions { unitTests.isReturnDefaultValues = true }

    sourceSets {
        getByName("main").java.srcDir(if (enableTdlib) "src/tdlib/java" else "src/notdlib/java")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json.test)

    if (enableTdlib) {
        val localAar = file("libs/tdlib.aar")
        when {
            localAar.exists() -> implementation(files(localAar))
            tdlibDep != null -> implementation(tdlibDep)
            else -> throw GradleException("enableTdlib=true needs app/libs/tdlib.aar or -PtdlibDep=<maven coordinate>")
        }
    }
}
