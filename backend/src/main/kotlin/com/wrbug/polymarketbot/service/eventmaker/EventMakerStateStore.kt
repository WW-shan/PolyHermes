package com.wrbug.polymarketbot.service.eventmaker

import com.google.gson.Gson
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Service
class EventMakerStateStore(
    @Value("\${event-maker.state-file:data/event-maker-state.json}")
    private val stateFile: String = "data/event-maker-state.json"
) {
    private val gson = Gson()
    private val lock = Any()

    fun save(state: EventMakerPersistedState) = synchronized(lock) {
        val path = targetPath()
        val parent = path.parent ?: throw IllegalStateException("state_file_has_no_parent")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temp,
                gson.toJson(state),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun load(): EventMakerPersistedState? = synchronized(lock) {
        val path = targetPath()
        if (!Files.exists(path)) return@synchronized null
        runCatching {
            val parsed = Files.readString(path, StandardCharsets.UTF_8)
                .let { gson.fromJson(it, EventMakerPersistedState::class.java) }
            parsed?.takeIf { it.version == CURRENT_VERSION }
        }.getOrNull()
    }

    fun clear() = synchronized(lock) {
        Files.deleteIfExists(targetPath())
    }

    private fun targetPath(): Path = Paths.get(stateFile).toAbsolutePath().normalize()

    companion object {
        const val CURRENT_VERSION = 1
    }
}