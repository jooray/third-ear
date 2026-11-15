import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.compose)
}

// Release signing with Juraj's shared publisher key (Zapstore binds its certificate to his
// npub). The properties file and keystore live outside the repo; APP_SIGNING_PROPERTIES may
// point elsewhere. Without it the release build is unsigned.
val keystoreProperties = Properties().apply {
    val path = System.getenv("APP_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.apk-signing-keystore/signing.properties"
    File(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "thirdear-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        vectorDrawables.useSupportLibrary = true
        // the MarmotKit build in use (tools/marmotkit/fetch.sh stamps it); a change rotates the KeyPackage
        val marmotkit = rootProject.file("app/src/marmotkit/.version").takeIf { it.exists() }?.readText()?.substringBefore(' ')?.trim().orEmpty()
        buildConfigField("String", "MARMOTKIT_VERSION", "\"$marmotkit\"")
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        } else {
            logger.warn("Warning: no signing properties found. The release build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        // release speed (R8) on the debug app id and key: screenshots on an emulator with demo data
        // put in through run-as; the debug build is too slow there
        create("screenshots") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
        // MarmotKit (White Noise): generated bindings plus native libraries, fetched and
        // checksum-verified by tools/marmotkit/fetch.sh into this git-ignored directory
        getByName("main").kotlin.directories.add("src/marmotkit/kotlin")
        getByName("main").jniLibs.directories.add("src/marmotkit/jniLibs")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // compressed native libraries keep the download small; MarmotKit's is ~52 MB uncompressed
        jniLibs.useLegacyPackaging = true
        // White Noise runs on arm64 only (MarmotHost.supported()); the other ABIs' MarmotKit
        // libraries (~70 MB) would never load
        jniLibs.excludes += listOf("lib/x86/libmarmot_uniffi.so", "lib/x86_64/libmarmot_uniffi.so", "lib/armeabi-v7a/libmarmot_uniffi.so")
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = "app.thirdear"

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.eventbus)
    implementation(libs.audiorecordview)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.tandroidlame)
    implementation(libs.autofittextview)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // MarmotKit's UniFFI bindings call the native library through JNA
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.car.app:app:1.4.0")
    detektPlugins(libs.compose.detekt)

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
}

// AGP asks for the Compose group-mapping tool of its own built-in Kotlin (2.2.10, never published);
// the one matching the Kotlin compiler in use is.
configurations.matching { it.name.contains("composeMapping", ignoreCase = true) }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "compose-group-mapping") {
            useVersion(libs.versions.kotlin.get())
        }
    }
}
