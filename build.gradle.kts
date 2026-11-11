import net.fabricmc.loom.api.LoomGradleExtensionAPI

// Up to 1.21.11 Minecraft ships obfuscated: Loom remaps with Mojang's mappings. 26.x is plain.
val obfuscated = stonecutter.eval(stonecutter.current.version, "<=1.21.11")
apply(plugin = if (obfuscated) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")

// Plain renames between Minecraft versions, applied to the sources (written in the newest names).
// rename(since, new, old): `old` is used below version `since`. Different logic uses //? comments instead.
fun below(v: String) = stonecutter.eval(stonecutter.current.version, "<$v")
stonecutter.replacements {
    fun renameWord(since: String, new: String, old: String) =
        regex(below(since)) { replace("\\b$new\\b", old, "\\b$old\\b", new) }

    renameWord("1.21.11", "Identifier", "ResourceLocation")
}

version = "${property("mod.version")}+${stonecutter.current.version}"
group = property("mod.group") as String
base.archivesName = "troupe"

val javaVersion = JavaVersion.toVersion(property("java").toString())
val loom = extensions.getByType<LoomGradleExtensionAPI>()

fun mod(configuration: String) = if (obfuscated) "mod" + configuration.replaceFirstChar(Char::uppercase) else configuration

repositories {
    mavenLocal { content { includeGroup("dev.holoweb") } } // Vitrine from ../cefmod: ./gradlew :26.3:publishToMavenLocal
    maven("https://maven.squiddev.cc") {
        name = "SquidDev"
        content { includeGroup("cc.tweaked") }
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:${property("minecraft")}")
    if (obfuscated) "mappings"(loom.officialMojangMappings())
    // Lua 5.2 VM in plain Java (MIT), the one CC: Tweaked uses; shipped inside our jar.
    "implementation"("cc.tweaked:cobalt:${property("deps.cobalt")}")
    "include"("cc.tweaked:cobalt:${property("deps.cobalt")}")
    // UI (client only). Not bundled: players install Vitrine; without it, ui.* errors and the rest works.
    mod("implementation")("dev.holoweb:vitrine:${property("deps.vitrine")}+${property("minecraft")}")
    mod("implementation")("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    mod("implementation")("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

loom.runConfigs.configureEach {
    // Client and server need separate folders: both would read resources/ and write troupe/.
    runDirectory = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.version}/$name")
    // The engine built in ../cefmod instead of the first-launch download.
    property("vitrine.cefDir", rootProject.file("../cefmod/native/build/out").absolutePath)
    // Dev server: resources restart when their files change.
    if (name == "server") property("troupe.dev", "true")
}

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = javaVersion.majorVersion.toInt()
    }

    named<Test>("test") {
        useJUnitPlatform()
    }

    named<ProcessResources>("processResources") {
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
