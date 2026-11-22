
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "dev.materialclient"
version = "0.3.1"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    // Dispatchers.Main для desktop UI на Swing EDT; версия совпадает с coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Для управления оформлением заголовка Windows
    implementation("net.java.dev.jna:jna:5.15.0")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "dev.materialclient.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageName = "Material Client"
            // Формат x.y.z обязателен для установщика Windows. Бета-метка добавляется в имя файла при выкладке.
            packageVersion = "0.3.1"
            description = "Material Client — Minecraft launcher"
            vendor = "Material Client"
            // Нужны, чтобы лаунчер узнавал объём оперативной памяти для автоподбора -Xmx.
            // jdk.crypto.ec и java.naming нужны для HTTPS (Mojang, Modrinth): без них в собранной версии
            // соединение с сайтами падает, хотя из Gradle всё работает.
            modules("java.management", "jdk.management", "jdk.crypto.ec", "java.naming", "jdk.unsupported")

            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "Material Client"
                shortcut = true
                dirChooser = true
                // Установка без прав администратора, в профиль пользователя.
                perUserInstall = true
                // Постоянный идентификатор: по нему новая версия обновляет старую, а не ставится рядом.
                upgradeUuid = "6f1f6d0c-3a52-4d1b-9a1e-7b5c2d9e4a10"
            }
        }
    }
}
