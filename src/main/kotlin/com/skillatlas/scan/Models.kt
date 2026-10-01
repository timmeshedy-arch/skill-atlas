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

/** Артефакт с репозиторием, откуда он взят, — для скана организаций. */
data class RepoArtifact(
    val owner: String,
    val repo: String,
    val artifact: Artifact,
)

/** Итог листинга организации: [repoCount] репо пошло в скан, либо [error]. */
data class OrgScan(
    val org: String,
    val repoCount: Int,
    /** У организации больше репо, чем одна страница листинга, — остальные не сканировались. */
    val truncated: Boolean,
    val error: String?,
)

/** Итог одного репо в скане организаций: либо [result], либо [error]. */
data class RepoScan(
    val owner: String,
    val repo: String,
    val result: ScanResult?,
    val error: String?,
)

/** Группа похожих артефактов, возможно из разных репо; [members] отсортированы по owner/repo/path. */
data class MultiSimilarGroup(
    val members: List<RepoArtifact>,
    val score: Double,
)

data class OrgScanResult(
    val orgs: List<OrgScan>,
    val repos: List<RepoScan>,
    val artifacts: List<RepoArtifact>,
    val similar: List<MultiSimilarGroup>,
)
