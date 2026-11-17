plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.grupopecuario.gpscamiones"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.grupopecuario.gpscamiones"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
