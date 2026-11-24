plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.kunzisoft.keepass"
    compileSdk = 36

    val gmsPackage = "com.google.android.gms"

    defaultConfig {
        applicationId = "uk.zjd.cloudpass"
        minSdk = 23
        targetSdk = 36
        // версия: -PcpVersionCode / -PcpVersionName (сборка на раннере), по умолчанию — ниже
        versionCode = (project.findProperty("cpVersionCode") as String?)?.toInt() ?: 3
        versionName = (project.findProperty("cpVersionName") as String?) ?: "0.1.2"
        // основа: KeePassDX 4.5.8 (GPLv3), исходники форка — docs/SOURCE.md
        multiDexEnabled = true

        testApplicationId = "uk.zjd.cloudpass.tests"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_PLAY_SERVICES_PACKAGE", "\"$gmsPackage\"")
        buildConfigField("String[]", "ICON_PACKS", "{\"classic\",\"material\"}")
        
        manifestPlaceholders["googleAndroidBackupAPIKey"] = "unused"
        manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions += "version"
    productFlavors {
        create("libre") {
            dimension = "version"
            buildConfigField("String", "BUILD_VERSION", "\"libre\"")
            buildConfigField("boolean", "CLOSED_STORE", "false")
            buildConfigField("String[]", "STYLES_DISABLED", "{}")
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
        }
        create("free") {
            dimension = "version"
            applicationIdSuffix = ".free"
            buildConfigField("String", "BUILD_VERSION", "\"free\"")
            buildConfigField("boolean", "CLOSED_STORE", "true")
            buildConfigField(
                "String[]", "STYLES_DISABLED",
                "{\"KeepassDXStyle_Blue\"," +
                    "\"KeepassDXStyle_Blue_Night\"," +
                    "\"KeepassDXStyle_Red\"," +
                    "\"KeepassDXStyle_Red_Night\"," +
                    "\"KeepassDXStyle_Reply\"," +
                    "\"KeepassDXStyle_Reply_Night\"," +
                    "\"KeepassDXStyle_Purple\"," +
                    "\"KeepassDXStyle_Purple_Dark\"," +
                    "\"KeepassDXStyle_Dynamic_Light\"," +
                    "\"KeepassDXStyle_Dynamic_Night\"}"
            )
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
            manifestPlaceholders["googleAndroidBackupAPIKey"] = "AEdPqrEAAAAIbRfbV8fHLItXo8OcHwrO0sSNblqhPwkc0DPTqg"
            manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
        }
    }

    sourceSets {
        getByName("libre") {
            res.srcDirs("src/libre/res")
        }
        getByName("free") {
            res.srcDirs("src/free/res")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.media)
    // Lifecycle - ViewModel - Coroutines
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.google.material)
    // Token auto complete
    // From sources until https://github.com/splitwise/TokenAutoComplete/pull/422 fixed
    implementation(libs.tokenautocomplete)
    // Database
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Utilities
    implementation(libs.androidx.autofill)
    implementation(libs.joda.time)
    implementation(libs.chroma)
    implementation(libs.taptargetview)
    implementation(libs.commons.io)
    // Credentials
    implementation(libs.nbvcxz)
    implementation(libs.androidx.credentials)
    // Modules import
    implementation(project(":database"))
    implementation(project(":icon-pack"))
    // Tests
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // Cloud Pass: настоящий org.json для тестов импорта на JVM
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
