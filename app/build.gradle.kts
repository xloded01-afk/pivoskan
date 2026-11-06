import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release signing config is wired only when keystore.properties exists,
// so a fresh clone can always assemble without the real keystore.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            require(!getProperty(key).isNullOrBlank()) { "Missing $key in keystore.properties" }
        }
        require(rootProject.file(getProperty("storeFile")).isFile) {
            "Signing keystore does not exist: ${getProperty("storeFile") }"
        }
    }
}

android {
    namespace = "ru.pivoskan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.pivoskan.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.1"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
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
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-splashscreen:1.0.1")

    testImplementation("junit:junit:4.13.2")
}

// Optional integration check against public catalogs. Unit tests remain deterministic/offline.
tasks.register<JavaExec>("liveCatalogSmoke") {
    group = "verification"
    description = "Read official Magnit and Fix Price beer catalogs for Balashikha"
    dependsOn("compileDebugUnitTestKotlin", "processDebugUnitTestJavaRes")
    mainClass.set("ru.pivoskan.app.data.LiveCatalogSmoke")
    doFirst { classpath = tasks.named<Test>("testDebugUnitTest").get().classpath }
}
