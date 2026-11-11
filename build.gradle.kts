plugins {
    id("net.fabricmc.fabric-loom")
}

version = "${property("mod.version")}+${stonecutter.current.version}"
group = property("mod.group") as String
base.archivesName = "troupe"

val javaVersion = JavaVersion.toVersion(property("java").toString())

repositories {
    mavenLocal { content { includeGroup("dev.holoweb") } } // Vitrine from ../cefmod: ./gradlew :26.3:publishToMavenLocal
    maven("https://maven.squiddev.cc") {
        name = "SquidDev"
        content { includeGroup("cc.tweaked") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft")}")
    // Lua 5.2 VM in plain Java (MIT), the one CC: Tweaked uses; shipped inside our jar.
    implementation("cc.tweaked:cobalt:${property("deps.cobalt")}")
    include("cc.tweaked:cobalt:${property("deps.cobalt")}")
    // UI (client only). Not bundled: players install Vitrine; without it, ui.* errors and the rest works.
    implementation("dev.holoweb:vitrine:${property("deps.vitrine")}+${property("minecraft")}")
    implementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom.runConfigs.configureEach {
    // Client and server need separate folders: both would read resources/ and write troupe/.
    runDirectory = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.version}/$name")
    // The engine built in ../cefmod instead of the first-launch download.
    property("vitrine.cefDir", rootProject.file("../cefmod/native/build/out").absolutePath)
}

java {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = javaVersion.majorVersion.toInt()
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "minecraft" to project.property("mod.mc_dep"),
            "java" to javaVersion.majorVersion,
            "loader" to project.property("deps.fabric_loader"),
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }
}
