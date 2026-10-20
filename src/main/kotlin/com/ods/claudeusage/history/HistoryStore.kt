package com.ods.claudeusage.history

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.ods.claudeusage.model.UsageSample
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant

/** Ring buffer of chart samples, persisted as JSON lines so the chart survives an IDE restart. */
class HistoryStore(private val file: Path, private val retention: Duration = Duration.ofHours(48)) {

    private val samples = ArrayDeque<UsageSample>()
    private var dirty = 0

    @Synchronized
    fun load() {
        samples.clear()
        if (!Files.isRegularFile(file)) return
        val cutoff = Instant.now().minus(retention)
        try {
            Files.lines(file, StandardCharsets.UTF_8).use { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching { fromJson(JsonParser.parseString(line).asJsonObject) }
                        .getOrNull()?.takeIf { it.at.isAfter(cutoff) }?.let(samples::addLast)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Cannot load usage history", e)
        }
    }

    @Synchronized
    fun add(sample: UsageSample) {
        samples.addLast(sample)
        val cutoff = sample.at.minus(retention)
        while (samples.isNotEmpty() && samples.first().at.isBefore(cutoff)) samples.removeFirst()
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, toJson(sample).toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            LOG.debug("Cannot append usage history", e)
        }
        if (++dirty >= 500) compact()
    }

    @Synchronized
    fun snapshot(): List<UsageSample> = samples.toList()

    @Synchronized
    fun last(): UsageSample? = samples.lastOrNull()

    @Synchronized
    fun compact() {
        dirty = 0
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(tmp, samples.map { toJson(it).toString() }, StandardCharsets.UTF_8)
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            LOG.debug("Cannot compact usage history", e)
        }
    }

    private fun toJson(s: UsageSample): JsonObject = JsonObject().apply {
        addProperty("at", s.at.toEpochMilli())
        add("w", JsonObject().also { o -> s.windows.forEach { (k, v) -> o.addProperty(k, v) } })
        add("r", JsonObject().also { o -> s.resets.forEach { (k, v) -> o.addProperty(k, v) } })
        s.contextPercent?.let { addProperty("cp", it) }
        s.contextTokens?.let { addProperty("ct", it) }
        s.sessionId?.let { addProperty("sid", it) }
    }

    private fun fromJson(o: JsonObject): UsageSample = UsageSample(
        at = Instant.ofEpochMilli(o.get("at").asLong),
        windows = o.getAsJsonObject("w")?.entrySet()?.associate { it.key to it.value.asDouble } ?: emptyMap(),
        resets = o.getAsJsonObject("r")?.entrySet()?.associate { it.key to it.value.asLong } ?: emptyMap(),
        contextPercent = o.get("cp")?.asDouble,
        contextTokens = o.get("ct")?.asLong,
        sessionId = o.get("sid")?.asString,
    )

    companion object {
        private val LOG = logger<HistoryStore>()
    }
}
