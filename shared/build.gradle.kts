plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library") apply false
}
if (providers.gradleProperty("coreOnly").orNull != "true") apply(plugin = "com.android.library")
kotlin {
    jvmToolchain(17)
    jvm()
    if (providers.gradleProperty("coreOnly").orNull != "true") androidTarget()
    if (System.getProperty("os.name").contains("Mac")) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            it.binaries.framework { baseName = "ExpenseCore"; isStatic = true }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        if (providers.gradleProperty("coreOnly").orNull != "true") {
            getByName("androidMain").dependsOn(jvmAndAndroidMain)
        }
    }
}
if (providers.gradleProperty("coreOnly").orNull != "true") {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "om.masroofi.core"
        compileSdk = 35
        defaultConfig { minSdk = 26 }
        compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    }
}
