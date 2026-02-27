plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        minSdk = 24
        targetSdk = 36
        versionCode = versionCodeOverride ?: 6
        versionName = versionNameOverride ?: "0.1.2"

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

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Use maintained JSch fork for modern host key/kex support (OpenSSH 9+/10+).
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("com.jcraft:jzlib:1.1.3")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.api-client:google-api-client-gson:2.2.0")
    implementation("com.google.http-client:google-http-client-android:1.43.3")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")

    val coroutines_version = "1.7.3"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines_version")

    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.concurrent:concurrent-futures:1.1.0")
    androidTestImplementation("com.google.guava:guava:31.1-jre")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
}
