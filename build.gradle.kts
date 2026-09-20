plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.automodpack4paper"
version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

// Vendored, unmodified AutoModpack core (LGPL-3.0). See NOTICE.
sourceSets.main {
    java.srcDir("third_party/automodpack-core/java")
}

val shade = configurations.create("shade")
configurations.compileClasspath { extendsFrom(shade) }
configurations.runtimeClasspath { extendsFrom(shade) }

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.124-stable")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")

    // Provided by the server at runtime (netty, log4j, gson ship with Paper)
    compileOnly("io.netty:netty-all:4.2.16.Final")
    compileOnly("org.apache.logging.log4j:log4j-core:2.26.1")

    shade("org.bouncycastle:bcpkix-jdk18on:1.82")
    shade("org.tomlj:tomlj:1.1.1")
}

java {
    // Paper 26.x API is compiled for Java 25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") { expand(props) }
    }
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:none")
    }
    jar { enabled = false } // only the shaded jar is a release artifact
    shadowJar {
        archiveClassifier.set("")
        configurations = listOf(shade)
        relocate("org.bouncycastle", "dev.automodpack4paper.libs.bouncycastle")
        relocate("org.tomlj", "dev.automodpack4paper.libs.tomlj")
        relocate("org.antlr", "dev.automodpack4paper.libs.antlr")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        // Keep the jar small (Hangar caps uploads at 10 MB): drop BouncyCastle parts this plugin never uses.
        // Only certificate generation/PEM parsing (asn1, cert, operator, openssl, jcajce, jce, crypto) is needed.
        listOf("oer", "tsp", "cmp", "crmf", "ess", "eac", "est", "dvcs", "mime", "smime", "mozilla", "dane", "its", "pkcs/jcajce").forEach {
            exclude("org/bouncycastle/$it/**")
            exclude("dev/automodpack4paper/libs/bouncycastle/$it/**")
        }
        exclude("META-INF/versions/**") // multi-release duplicates
        mergeServiceFiles()
    }
    build { dependsOn(shadowJar) }
}
