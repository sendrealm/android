plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").getOrElse("0.0.1")
val hasSigningCredentials = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.secretKeyRingFile").isPresent

android {
    namespace = "com.sendrealm.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 27

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {

    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))
    implementation(libs.gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.github.bumptech.glide:glide:4.16.0")
//    kapt "com.github.bumptech.glide:compiler:4.16.0"

    implementation(libs.firebase.messaging)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.base)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    if (hasSigningCredentials) {
        signAllPublications()
    }

    coordinates("com.sendrealm", "sendrealm-android", sdkVersion)

    pom {
        name.set("Sendrealm Android SDK")
        description.set("Native Android SDK for Sendrealm.")
        inceptionYear.set("2026")
        url.set("https://github.com/sendrealm/android")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("sendrealm")
                name.set("Sendrealm")
                url.set("https://sendrealm.com")
            }
        }

        scm {
            url.set("https://github.com/sendrealm/android")
            connection.set("scm:git:https://github.com/sendrealm/android.git")
            developerConnection.set("scm:git:ssh://git@github.com/sendrealm/android.git")
        }
    }
}
