plugins {
    id("com.android.application")

}

val versionCodeOverride = (project.findProperty("versionCode") as String?)?.toIntOrNull()
val versionNameOverride = project.findProperty("versionName") as String?
val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keystoreAlias = System.getenv("ANDROID_KEY_ALIAS")
val keystoreKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasKeystore = !keystorePath.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    !keystoreAlias.isNullOrBlank() &&
    !keystoreKeyPassword.isNullOrBlank()

android {
    namespace = "net.hlan.sushi"
    compileSdk = 36

    testBuildType = "minifiedDebug"

    defaultConfig {
        applicationId = "net.hlan.sushi"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOverride ?: 15
        versionName = versionNameOverride ?: "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Default debug settings.
        }
        release {
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("minifiedDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "minified-debug-proguard-rules.pro"
            )
            testProguardFiles("test-proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    // Use maintained JSch fork for modern host key/kex support (OpenSSH 9+/10+).
    implementation("com.github.mwiede:jsch:2.27.9")
    implementation("com.jcraft:jzlib:1.1.3")
    // Bouncy Castle provides Ed25519 on Android API < 33 (JCE lacks EdDSA before API 33).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // JSch BC integration references ML-KEM classes shipped in bcprov-ext.
    implementation("org.bouncycastle:bcprov-ext-jdk18on:1.78.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.api-client:google-api-client-gson:2.9.0")
    implementation("com.google.http-client:google-http-client-android:1.43.3")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")

    val coroutines_version = "1.10.2"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines_version")

    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.7.0")
    // Force 1.3.0 in the main classpath so consistent resolution does not pin it to 1.1.0,
    // which conflicts with the androidTest dependencies (espresso/junit/concurrent-futures-ktx).
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    androidTestImplementation("androidx.concurrent:concurrent-futures:1.3.0")
    androidTestImplementation("com.google.guava:guava:33.5.0-jre")

}
