package world.gregs.voidps.tools.cache

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoderFull
import world.gregs.voidps.cache.definition.decoder.SpriteDecoder
import world.gregs.voidps.engine.data.Settings
import java.io.File
import javax.imageio.ImageIO

object DumpInterface320Sprites {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: DumpInterface320Sprites <output-dir> [game.properties path]")
            println("  If no properties file is given, defaults to ./game.properties")
            return
        }

        val outputDir = File(args[0])
        outputDir.mkdirs()

        val propertiesFile = args.getOrNull(1) ?: "game.properties"
        println("Loading settings from $propertiesFile...")
        val properties = Settings.load(propertiesFile)

        println("Loading cache the same way the game server does...")
        val cache = Cache.load(properties)

        println("Loading sprites...")
        val spriteDecoder = SpriteDecoder().load(cache)

        println("Loading interfaces...")
        val interfaceDecoder = InterfaceDecoderFull().load(cache)

        val interface320 = interfaceDecoder.getOrNull(320)
        if (interface320 == null) {
            println("Interface 320 not found!")
            return
        }

        val components = interface320.components
        if (components == null) {
            println("Interface 320 has no components!")
            return
        }

        println("Interface 320 has ${components.size} components.")
        var dumped = 0

        for ((index, component) in components.withIndex()) {
            if (component.type != 5) continue

            val spriteId = component.defaultImage
            if (spriteId == -1 || spriteId == 65535) continue

            val spriteDef = spriteDecoder.getOrNull(spriteId)
            if (spriteDef == null) {
                println("  Component $index: sprite $spriteId not found in cache")
                continue
            }

            val sprites = spriteDef.sprites
            if (sprites.isNullOrEmpty()) {
                println("  Component $index: sprite $spriteId has no frames")
                continue
            }

            val sprite = sprites[0]
            if (sprite.width <= 0 || sprite.height <= 0) {
                println("  Component $index: sprite $spriteId has invalid dimensions")
                continue
            }

            val name = if (component.name.isNotBlank()) component.name else "component_$index"
            val safeName = name.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val file = outputDir.resolve("${safeName}_${index}_${spriteId}.png")
            ImageIO.write(sprite.toBufferedImage(), "png", file)
            println("  Dumped component $index (sprite $spriteId) -> ${file.name}")
            dumped++
        }

        println("Done. Dumped $dumped sprites to ${outputDir.absolutePath}")
    }
}
