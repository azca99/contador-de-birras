plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.contadordebirras"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.daniel.contadordebirras"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = (project.findProperty("RELEASE_STORE_FILE") as String?)
                ?: System.getenv("RELEASE_STORE_FILE")
            val storePasswordProp = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAliasProp = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPasswordProp = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (!storeFilePath.isNullOrBlank()) {
                val storeF = file(storeFilePath)
                if (!storeF.exists()) {
                    throw GradleException("El archivo de almacén de claves especificado en RELEASE_STORE_FILE no existe: $storeFilePath")
                }
                storeFile = storeF
                storePassword = storePasswordProp ?: throw GradleException("Falta configurar RELEASE_STORE_PASSWORD para la firma Release.")
                keyAlias = keyAliasProp ?: throw GradleException("Falta configurar RELEASE_KEY_ALIAS para la firma Release.")
                keyPassword = keyPasswordProp ?: throw GradleException("Falta configurar RELEASE_KEY_PASSWORD para la firma Release.")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
        getByName("test") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.work.runtime.ktx)


  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.8")
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.work.testing)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)


  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.navigation.compose)

  // Room & DataStore
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)
  testImplementation(libs.room.testing)
  androidTestImplementation(libs.room.testing)
  implementation(libs.datastore.preferences)

  // Location
  implementation(libs.play.services.location)
  
  // Image Loading
  implementation(libs.coil.compose)

  // Firebase & Auth
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.storage)
  implementation(libs.firebase.functions)
  implementation(libs.firebase.appcheck.playintegrity)
  debugImplementation(libs.firebase.appcheck.debug)
  implementation(libs.play.services.auth)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
}



