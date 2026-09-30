package com.skillatlas.scan

import com.skillatlas.github.TreeEntry

data class Candidate(val type: ArtifactType, val path: String)

object ArtifactScanner {

    fun findCandidates(entries: List<TreeEntry>): List<Candidate> {
        return entries
            .filter { it.type == "blob" }
            .mapNotNull { entry -> classify(entry.path)?.let { Candidate(it, entry.path) } }
    }

    private fun classify(path: String): ArtifactType? {
        val segments = path.split("/")
        val fileName = segments.last()
        val parentDir = segments.dropLast(1).lastOrNull()

        return when {
            fileName == "SKILL.md" -> ArtifactType.SKILL
            fileName.endsWith(".md") && parentDir == "commands" -> ArtifactType.COMMAND
            else -> null
        }
    }
}
