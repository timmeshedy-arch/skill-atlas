package com.skillatlas.scan

import com.skillatlas.github.GitHubClient

class Scanner(private val client: GitHubClient) {

    fun scan(owner: String, repo: String, ref: String): ScanResult {
        val repoInfo = client.getRepoInfo(owner, repo)
        val resolvedRef = if (ref.isBlank()) repoInfo.default_branch else ref
        val tree = client.getTree(owner, repo, resolvedRef)

        val candidates = ArtifactScanner.findCandidates(tree.tree)
        val artifacts = candidates.map { candidate ->
            val content = client.getRawFile(owner, repo, resolvedRef, candidate.path)
            val url = "https://github.com/$owner/$repo/blob/${tree.sha}/${candidate.path}"
            ArtifactParser.parse(candidate, content, url)
        }

        val checked = markDuplicates(artifacts)
        return ScanResult(
            owner = owner,
            repo = repo,
            ref = resolvedRef,
            sha = tree.sha,
            artifacts = checked,
            similar = SimilarityDetector.findGroups(checked),
            truncated = tree.truncated,
        )
    }

    private fun markDuplicates(artifacts: List<Artifact>): List<Artifact> {
        val seenNames = mutableMapOf<Pair<ArtifactType, String>, Int>()
        for (artifact in artifacts) {
            if (artifact.valid && artifact.name != null) {
                val key = artifact.type to artifact.name
                seenNames[key] = (seenNames[key] ?: 0) + 1
            }
        }
        return artifacts.map { artifact ->
            val key = artifact.type to (artifact.name ?: "")
            if (artifact.valid && (seenNames[key] ?: 0) > 1) {
                artifact.copy(valid = false, error = "duplicate name '${artifact.name}'")
            } else {
                artifact
            }
        }
    }
}
