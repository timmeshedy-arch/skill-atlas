package com.skillatlas.report

import com.skillatlas.scan.Artifact
import com.skillatlas.scan.ArtifactType
import com.skillatlas.scan.ScanResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ReportFormat { TABLE, JSON, MD }

private const val TABLE_DESCRIPTION_LIMIT = 160

object ReportFormatter {

    fun format(result: ScanResult, format: ReportFormat): String = when (format) {
        ReportFormat.TABLE -> table(result)
        ReportFormat.JSON -> json(result)
        ReportFormat.MD -> markdown(result)
    }

    private fun status(artifact: Artifact) =
        if (artifact.valid) "valid" else "invalid: ${artifact.error}"

    private fun table(result: ScanResult): String {
        val sb = StringBuilder()
        sb.appendLine("skill-atlas scan report — ${result.owner}/${result.repo} @ ${result.ref} (sha: ${result.sha.take(7)})")
        if (result.truncated) {
            sb.appendLine("WARNING: GitHub tree API response was truncated — results may be incomplete for very large repos")
        }
        sb.appendLine()

        for (type in ArtifactType.entries) {
            val group = result.artifacts.filter { it.type == type }
            sb.appendLine("${type.label}S (${group.size})")
            for (artifact in group) {
                val mark = if (artifact.valid) "✔" else "✖"
                sb.appendLine("  $mark ${artifact.name ?: "—"} — ${status(artifact)}")
                sb.appendLine("    ${artifact.url}")
                artifact.description?.let { sb.appendLine("    ${truncate(it)}") }
            }
            sb.appendLine()
        }

        val total = result.artifacts.size
        val valid = result.artifacts.count { it.valid }
        sb.appendLine("Total: $total artifacts found, $valid valid, ${total - valid} invalid")
        return sb.toString().trimEnd()
    }

    private fun truncate(text: String) =
        if (text.length <= TABLE_DESCRIPTION_LIMIT) text else text.take(TABLE_DESCRIPTION_LIMIT) + "…"

    private fun markdown(result: ScanResult): String {
        val sb = StringBuilder()
        sb.appendLine("# skill-atlas report: ${result.owner}/${result.repo} @ ${result.ref}")
        sb.appendLine()
        if (result.truncated) {
            sb.appendLine("> ⚠️ GitHub tree response was truncated — results may be incomplete.")
            sb.appendLine()
        }
        for (type in ArtifactType.entries) {
            val group = result.artifacts.filter { it.type == type }
            if (group.isEmpty()) continue
            sb.appendLine("## ${type.label} (${group.size})")
            sb.appendLine()
            for (artifact in group) {
                val mark = if (artifact.valid) "✅" else "❌"
                val label = artifact.name?.let { "**$it**" } ?: "`${artifact.path}`"
                sb.appendLine("- $mark [$label](${artifact.url}) — ${status(artifact)}")
                artifact.description?.let { sb.appendLine("  $it") }
            }
            sb.appendLine()
        }
        val total = result.artifacts.size
        val valid = result.artifacts.count { it.valid }
        sb.appendLine("**Total:** $total artifacts, $valid valid, ${total - valid} invalid")
        return sb.toString().trimEnd()
    }

    private fun json(result: ScanResult): String =
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), toJson(result))

    /** Структура `--format json`; её же отдаёт `/api/scan` веб-UI. */
    fun toJson(result: ScanResult): JsonObject {
        val artifactsArray = JsonArray(result.artifacts.map { artifact -> artifactToJson(artifact) })
        return buildJsonObject {
            put("owner", result.owner)
            put("repo", result.repo)
            put("ref", result.ref)
            put("sha", result.sha)
            put("truncated", result.truncated)
            put("artifacts", artifactsArray)
        }
    }

    private fun artifactToJson(artifact: Artifact): JsonObject = buildJsonObject {
        put("type", artifact.type.label)
        put("path", artifact.path)
        put("name", artifact.name)
        put("description", artifact.description)
        put("url", artifact.url)
        put("valid", artifact.valid)
        put("error", artifact.error)
    }
}
