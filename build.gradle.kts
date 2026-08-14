plugins {
    java
    id("com.gradleup.shadow") version "8.3.8"
}

group = "cn.blockeco"
version = "0.1.0-SNAPSHOT"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.zaxxer:HikariCP:6.3.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    expand("version" to pluginVersion)
}

tasks.shadowJar {
    relocate("com.zaxxer.hikari", "cn.blockeco.exchange.libs.com.zaxxer.hikari")
    relocate("org.sqlite", "cn.blockeco.exchange.libs.org.sqlite")
}
