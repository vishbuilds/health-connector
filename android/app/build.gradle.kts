plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.compose.screenshot") version "0.0.1-alpha15"
}

android {
    namespace = "com.vishaal.healthconnector"
    // connect-client:1.1.0 requires consumers to compile against API 36+.
    compileSdk = 36

    val ciDebugKeystore = layout.projectDirectory.file("ci-debug-keystore.p12").asFile

    defaultConfig {
        applicationId = "com.vishaal.healthconnector"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("ciDebug") {
            storeFile = ciDebugKeystore
            storePassword = "healthconnector"
            keyAlias = "healthconnectordebug"
            keyPassword = "healthconnector"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            if (ciDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // (temporary) enable Compose screenshot preview rendering
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    // Material icon set (ImageVectors only — we render them through our own AppIcon, not Material UI)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Health Connect (1.1.0 is the current stable release; verify against
    // https://mvnrepository.com/artifact/androidx.health.connect/connect-client
    // in case a newer stable has shipped since this was written)
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Background work
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Encrypted local storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DataStore for lightweight sync state (changes tokens, backfill flags)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // (temporary) Compose preview screenshot rendering
    screenshotTestImplementation(composeBom)
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
