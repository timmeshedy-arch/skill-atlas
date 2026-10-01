package com.skillatlas.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Отмеченный в UI артефакт. `owner` и `repo` — в нижнем регистре (GitHub к ним нечувствителен). */
@Serializable
data class Star(val owner: String, val repo: String, val path: String) {
    companion object {
        fun of(owner: String, repo: String, path: String) = Star(owner.lowercase(), repo.lowercase(), path)
    }
}

/** Формат файла звёзд — тот же, что тело `GET /api/stars`. */
@Serializable
data class Stars(val stars: List<Star>)

/**
 * Звёзды web UI в JSON-файле. Файл читается один раз при создании, а пишется целиком
 * на каждое изменение — через временный файл и атомарный rename, чтобы обрыв записи
 * не оставил полфайла. Пока звёзд не ставили, файл не создаётся.
 *
 * Битый файл — [IllegalArgumentException] из конструктора: лучше не стартовать,
 * чем молча перезаписать чужие звёзды пустым списком. Если файл не записался,
 * изменение откатывается и летит [IOException] — в памяти и на диске одно и то же.
 */
class StarStore(private val file: Path) {

    private val stars = sortedSetOf(compareBy<Star>({ it.owner }, { it.repo }, { it.path }))

    init {
        if (file.exists()) {
            val loaded = try {
                Json.decodeFromString(Stars.serializer(), file.readText())
            } catch (e: Exception) {
                throw IllegalArgumentException("cannot read stars file $file: ${e.message}", e)
            }
            loaded.stars.mapTo(stars) { Star.of(it.owner, it.repo, it.path) }
        }
    }

    @Synchronized
    fun list(): Stars = Stars(stars.toList())

    @Synchronized
    fun add(star: Star): Stars {
        if (stars.add(star)) save { stars.remove(star) }
        return list()
    }

    @Synchronized
    fun remove(star: Star): Stars {
        if (stars.remove(star)) save { stars.add(star) }
        return list()
    }

    private fun save(rollback: () -> Unit) {
        val dir = file.toAbsolutePath().parent
        var tmp: Path? = null
        try {
            Files.createDirectories(dir)
            tmp = Files.createTempFile(dir, file.fileName.toString(), ".tmp")
            tmp.writeText(Json.encodeToString(Stars.serializer(), list()))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            rollback()
            tmp?.let { Files.deleteIfExists(it) }
            throw IOException("cannot save stars file $file: ${e.message}", e)
        }
    }
}
