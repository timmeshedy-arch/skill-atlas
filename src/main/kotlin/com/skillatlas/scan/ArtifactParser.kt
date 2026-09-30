package com.skillatlas.scan

import org.yaml.snakeyaml.Yaml

object ArtifactParser {

    private val yaml = Yaml()
    private val whitespace = Regex("\\s+")

    fun parse(candidate: Candidate, content: String, url: String): Artifact {
        val requireDescription = candidate.type == ArtifactType.SKILL
        return parseFrontmatterArtifact(candidate, content, url, requireDescription)
    }

    private fun extractFrontmatter(content: String): Map<*, *>? {
        val trimmed = content.trimStart('﻿')
        if (!trimmed.startsWith("---")) return null
        val afterOpening = trimmed.substring(3)
        val closingIndex = afterOpening.indexOf("\n---")
        if (closingIndex < 0) return null
        return yaml.load(afterOpening.substring(0, closingIndex)) as? Map<*, *>
    }

    private fun parseFrontmatterArtifact(
        candidate: Candidate,
        content: String,
        url: String,
        requireDescription: Boolean,
    ): Artifact {
        val frontmatter = try {
            extractFrontmatter(content)
        } catch (e: Exception) {
            return Artifact(candidate.type, candidate.path, null, null, url, false, "parse error: ${e.message}")
        }

        if (frontmatter == null) {
            return if (requireDescription) {
                Artifact(candidate.type, candidate.path, null, null, url, false, "missing frontmatter")
            } else {
                val derivedName = "/" + candidate.path.substringAfterLast("/").removeSuffix(".md")
                Artifact(candidate.type, candidate.path, derivedName, null, url, true, null)
            }
        }

        val name = frontmatter["name"]?.toString()
        val description = frontmatter["description"]?.toString()
            ?.replace(whitespace, " ")?.trim()?.ifBlank { null }

        return when {
            name == null ->
                Artifact(candidate.type, candidate.path, null, description, url, false, "missing 'name'")
            requireDescription && description == null ->
                Artifact(candidate.type, candidate.path, name, null, url, false, "missing 'description'")
            else ->
                Artifact(candidate.type, candidate.path, name, description, url, true, null)
        }
    }
}
