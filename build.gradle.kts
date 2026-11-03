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
    maven("https://repo.extendedclip.com/releases/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://jitpack.io")
}

val sqliteVersion = "3.54.1.0"
val mariadbVersion = "3.5.11"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.235-stable")
    // Not shaded: Paper downloads it at startup from the plugin.yml `libraries` list.
    compileOnly("org.xerial:sqlite-jdbc:$sqliteVersion")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")
    // Optional hooks: used only when the server has these plugins installed.
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.20")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")
    // For testing how config.yml is read (YamlConfiguration).
    testImplementation("io.papermc.paper:paper-api:26.3.build.235-stable")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

tasks.processResources {
    val props = mapOf("version" to version, "sqliteVersion" to sqliteVersion, "mariadbVersion" to mariadbVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    useJUnitPlatform()
}
