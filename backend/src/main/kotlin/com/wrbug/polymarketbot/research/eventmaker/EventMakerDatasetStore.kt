package com.wrbug.polymarketbot.research.eventmaker

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Immutable research dataset. Storing the exact fetched markets/trades lets
 * parameter searches reuse one data snapshot instead of fetching public APIs
 * again and producing a subtly different sample.
 */
data class EventMakerDatasetSnapshot(
    val version: Int = 1,
    val createdAt: Long,
    val fromDate: String,
    val toDate: String,
    val minVolume: Double,
    val maxMarkets: Int,
    val entryHorizonSeconds: Long,
    val markets: List<EventMakerMarket>? = null
)

object EventMakerDatasetStore {
    private val gson: Gson = GsonBuilder().create()

    fun write(path: Path, snapshot: EventMakerDatasetSnapshot) {
        val target = path.toAbsolutePath()
        val parent = target.parent
            ?: throw IllegalArgumentException("dataset path must have a parent: $path")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, target.fileName.toString() + ".", ".tmp")
        try {
            Files.newOutputStream(temp).use { file ->
                GZIPOutputStream(file).use { gzip ->
                    OutputStreamWriter(gzip, StandardCharsets.UTF_8).use { writer ->
                        gson.toJson(snapshot, writer)
                    }
                }
            }
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun read(path: Path): EventMakerDatasetSnapshot {
        require(Files.isRegularFile(path)) { "dataset not found: $path" }
        val snapshot = try {
            Files.newInputStream(path).use { file ->
                GZIPInputStream(file).use { gzip ->
                    InputStreamReader(gzip, StandardCharsets.UTF_8).use { reader ->
                        gson.fromJson(reader, EventMakerDatasetSnapshot::class.java)
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("dataset is not valid gzip JSON: $path", e)
        }
        require(snapshot != null) { "dataset is empty: $path" }
        require(snapshot.version == 1) { "unsupported dataset version: ${snapshot.version}" }
        val markets = snapshot.markets ?: throw IllegalArgumentException("dataset markets are missing")
        require(markets.isNotEmpty()) { "dataset has no markets" }
        markets.forEach { market ->
            require(market.conditionId.isNotBlank()) { "dataset contains a blank condition id" }
        }
        return snapshot.copy(markets = markets)
    }

    fun matches(
        snapshot: EventMakerDatasetSnapshot,
        fromDate: LocalDate,
        toDate: LocalDate,
        minVolume: Double,
        maxMarkets: Int,
        entryHorizonSeconds: Long
    ): Boolean = snapshot.fromDate == fromDate.toString() &&
        snapshot.toDate == toDate.toString() &&
        kotlin.math.abs(snapshot.minVolume - minVolume) < 1e-9 &&
        snapshot.maxMarkets == maxMarkets &&
        // A dataset fetched over a longer horizon is a strict superset for a shorter
        // replay window: all trades before the requested entry timestamp are present.
        snapshot.entryHorizonSeconds >= entryHorizonSeconds
}