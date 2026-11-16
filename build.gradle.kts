import com.modrinth.minotaur.ModrinthExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.neoforged.moddevgradle.dsl.NeoForgeExtension

// Nodes named <mc>-neoforge build for NeoForge (ModDevGradle); the others for Fabric (Loom).
// Sources tell them apart with //? if fabric / //? if neoforge; loader code lives in the *Entry classes and Loader.
val neoforge = stonecutter.current.project.endsWith("-neoforge")
val loader = if (neoforge) "neoforge" else "fabric"
stonecutter.constants.match(loader, "fabric", "neoforge")
// Up to 1.21.11 Minecraft ships obfuscated: Loom remaps with Mojang's mappings. 26.x is plain.
// NeoForge runs on Mojang's names, so its jar needs no remapping.
val obfuscated = !neoforge && stonecutter.eval(stonecutter.current.version, "<=1.21.11")
apply(plugin = if (neoforge) "net.neoforged.moddev" else if (obfuscated) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")
apply(plugin = "com.modrinth.minotaur")

// Plain renames between Minecraft versions, applied to the sources (written in the newest names).
// rename(since, new, old): `old` is used below version `since`. Different logic uses //? comments instead.
fun below(v: String) = stonecutter.eval(stonecutter.current.version, "<$v")
stonecutter.replacements {
    fun rename(since: String, new: String, old: String) = string(below(since)) { replace(new, old) }
    fun renameWord(since: String, new: String, old: String) =
        regex(below(since)) { replace("\\b$new\\b", old, "\\b$old\\b", new) }

    rename("26.1", "client.renderer.state.level.CameraRenderState", "client.renderer.state.CameraRenderState")

    renameWord("1.21.11", "Identifier", "ResourceLocation")
    renameWord("1.20.3", "SystemToastId", "SystemToastIds")
    renameWord("26.1", "GuiGraphicsExtractor", "GuiGraphics")
    // Fabric API took Mojang's "level" and "clientbound" names in 26.1
    renameWord("26.1", "ServerEntityLevelChangeEvents", "ServerEntityWorldChangeEvents")
    renameWord("26.1", "AFTER_PLAYER_CHANGE_LEVEL", "AFTER_PLAYER_CHANGE_WORLD")
    renameWord("26.1", "clientboundConfiguration", "configurationS2C")
    renameWord("26.1", "serverboundConfiguration", "configurationC2S")
    renameWord("26.1", "clientboundPlay", "playS2C")
    renameWord("26.1", "serverboundPlay", "playC2S")
    renameWord("26.1", "packetListener", "networkHandler")
}

version = "${property("mod.version")}+${stonecutter.current.project}"
group = property("mod.group") as String
base.archivesName = "troupe"

val javaVersion = JavaVersion.toVersion(property("java").toString())

fun mod(configuration: String) = if (obfuscated) "mod" + configuration.replaceFirstChar(Char::uppercase) else configuration
// Libraries shipped inside our jar: Loom's include, NeoForge's jarJar.
val bundle = if (neoforge) "jarJar" else "include"

repositories {
    mavenLocal { content { includeGroup("dev.holoweb") } } // Vitrine from ../cefmod: ./gradlew :26.3:publishToMavenLocal
    // CI has no Maven local: release.yml puts Vitrine's released jars (its GitHub release) in libs/.
    flatDir { dirs(rootProject.file("libs")) }
    maven("https://maven.squiddev.cc") {
        name = "SquidDev"
        content { includeGroup("cc.tweaked") }
    }
}

dependencies {
    // Lua 5.2 VM in plain Java (MIT), the one CC: Tweaked uses; shipped inside our jar.
    "implementation"("cc.tweaked:cobalt:${property("deps.cobalt")}")
    bundle("cc.tweaked:cobalt:${property("deps.cobalt")}")
    // The db module's default database (Apache 2.0); other JDBC drivers come from the server owner.
    "implementation"("org.xerial:sqlite-jdbc:${property("deps.sqlite")}")
    bundle("org.xerial:sqlite-jdbc:${property("deps.sqlite")}")
    // UI (client only). Not bundled: players install Vitrine; without it, ui.* errors and the rest works.
    mod("implementation")("dev.holoweb:vitrine:${property("deps.vitrine")}+${stonecutter.current.project}")

    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// Client and server need separate folders: both would read resources/ and write troupe/.
fun runDir(name: String) = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.project}/$name")
fun runProperties(name: String) = buildMap {
    // The engine built in ../cefmod instead of the first-launch download.
    put("vitrine.cefDir", rootProject.file("../cefmod/native/build/out").absolutePath)
    // Dev server: resources restart when their files change.
    if (name == "server") put("troupe.dev", "true")
}

if (neoforge) {
    extensions.configure<NeoForgeExtension> {
        version = property("deps.neoforge") as String
        for (side in listOf("client", "server")) runs.register(side) {
            if (side == "client") client() else server()
            gameDirectory = runDir(side)
            runProperties(side).forEach(::systemProperty)
            if (side == "server") programArgument("--nogui")
            // --args would replace the main class here: -PquickPlay=localhost:25565 joins a server right away.
            if (side == "client") findProperty("quickPlay")?.let { programArguments.addAll("--quickPlayMultiplayer", it.toString()) }
        }
        mods.register("troupe") { sourceSet(the<SourceSetContainer>()["main"]) }
    }
    // Dev runs only: plain libraries reach the game's classes through this (the jar carries them with jarJar).
    dependencies {
        "additionalRuntimeClasspath"("cc.tweaked:cobalt:${property("deps.cobalt")}")
        "additionalRuntimeClasspath"("org.xerial:sqlite-jdbc:${property("deps.sqlite")}")
    }
} else {
    val loom = extensions.getByType<LoomGradleExtensionAPI>()
    dependencies {
        "minecraft"("com.mojang:minecraft:${property("minecraft")}")
        if (obfuscated) "mappings"(loom.officialMojangMappings())
        // Permission nodes (LuckPerms and friends answer through it); 7 classes, shipped inside our jar.
        // Not transitive: it brings a Fabric API platform of the game version it was built on, which drags that
        // version's modules into the dev runs of older ones (0.3.3 on 1.21.1 loaded 1.21.3's and didn't start).
        mod("implementation")("me.lucko:fabric-permissions-api:${property("deps.permissions")}") { isTransitive = false }
        "include"("me.lucko:fabric-permissions-api:${property("deps.permissions")}")
        mod("implementation")("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
        mod("implementation")("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    }
    loom.runConfigs.configureEach {
        runDirectory = runDir(name)
        runProperties(name).forEach(::property)
    }
}

// Modrinth: .github/workflows/release.yml runs ./gradlew modrinth on a v<mod.version> tag, so the files there are
// always built by CI. -PmodrinthDry prints the upload and sends nothing.
extensions.configure<ModrinthExtension> {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = "holoweb-troupe"
    versionNumber = project.version.toString()
    versionName = "Troupe ${property("mod.version")} for Minecraft ${property("mod.mc_versions").toString().replace(",", ", ")}"
    versionType = "alpha"
    System.getenv("MODRINTH_CHANGELOG")?.let { changelog = it }
    uploadFile.set(tasks.named(if (obfuscated) "remapJar" else "jar"))
    gameVersions.addAll(property("mod.mc_versions").toString().split(","))
    loaders.add(loader)
    dependencies {
        if (!neoforge) required.project("fabric-api")
        optional.project("holoweb-vitrine") // clients need it for ui.*; launchers offer it with Troupe
        if (!neoforge) embedded.project("fabric-permissions-api") // inside the jar; Cobalt and sqlite-jdbc aren't Modrinth projects
    }
    debugMode = project.hasProperty("modrinthDry")
}
tasks.named("modrinth") { notCompatibleWithConfigurationCache("Minotaur reads the project at execution time") }

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
    // ModDevGradle keeps Minecraft off the test classpath; the Fabric node of the same version runs these tests.
    if (neoforge) for (task in listOf("compileTestJava", "test")) named(task) { enabled = false }

    named<ProcessResources>("processResources") {
        val props = mapOf(
            "version" to project.version,
            "minecraft" to project.property("mod.mc_dep"),
            "java" to javaVersion.majorVersion,
            // NeoForge: the oldest build the jar accepts (mod.neoforge_dep), not the dev one; players' packs lag behind.
            "loader" to project.property(if (neoforge) "mod.neoforge_dep" else "deps.fabric_loader"),
            // Mixins whose targets exist only on some versions, and NeoForge's stand-ins for Fabric API events.
            "mixins" to if (neoforge) ", \"ConfigDisconnectMixin\", \"TrackedEntityAccess\"" else "",
            "client_mixins" to buildList {
                if (!below("1.20.3")) addAll(listOf("DownloadedPackSourceMixin", "CommonListenerAccess"))
                if (neoforge) add("ClientConnectionMixin")
            }.joinToString("") { ", \"$it\"" },
        )
        inputs.properties(props)
        filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "*.mixins.json")) { expand(props) }
        exclude(if (neoforge) "fabric.mod.json" else "META-INF/neoforge.mods.toml")
    }
}
