package com.skillatlas.scan

enum class ArtifactType(val label: String) {
    SKILL("SKILL"),
    COMMAND("COMMAND"),
}

data class Artifact(
    val type: ArtifactType,
    val path: String,
    val name: String?,
    val description: String?,
    val url: String,
    val valid: Boolean,
    val error: String?,
)

/** Группа похожих артефактов (возможных дублей); [paths] отсортированы. */
data class SimilarGroup(
    val paths: List<String>,
    val score: Double,
)

data class ScanResult(
    val owner: String,
    val repo: String,
    val ref: String,
    val sha: String,
    val artifacts: List<Artifact>,
    val similar: List<SimilarGroup>,
    val truncated: Boolean,
)
