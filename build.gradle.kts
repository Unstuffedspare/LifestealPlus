plugins {
    java
}

group = "dev.lifestealplus"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val sqliteVersion = "3.54.1.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.235-stable")
    // Not shaded: Paper downloads it at startup from the plugin.yml `libraries` list.
    compileOnly("org.xerial:sqlite-jdbc:$sqliteVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

tasks.processResources {
    val props = mapOf("version" to version, "sqliteVersion" to sqliteVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    useJUnitPlatform()
}
