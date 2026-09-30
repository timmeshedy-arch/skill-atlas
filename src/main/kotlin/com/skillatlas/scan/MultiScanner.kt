package com.skillatlas.scan

import com.skillatlas.github.GitHubException

/**
 * Скан нескольких репозиториев по default branch: артефакты сливаются в один список,
 * похожие ищутся по всем репо сразу. Ошибка одного репо не прерывает остальные —
 * она попадает в [RepoScan.error]. Валидация (дубли имени) остаётся внутри репо.
 */
class MultiScanner(private val scanner: Scanner) {

    fun scan(repos: List<Pair<String, String>>): MultiScanResult {
        val scans = repos.map { (owner, repo) ->
            try {
                RepoScan(owner, repo, scanner.scan(owner, repo, ref = ""), error = null)
            } catch (e: GitHubException.NotFound) {
                RepoScan(owner, repo, result = null, error = "repository not found")
            } catch (e: GitHubException.RateLimited) {
                RepoScan(owner, repo, result = null, error = e.message.orEmpty())
            } catch (e: GitHubException.Network) {
                RepoScan(owner, repo, result = null, error = "network error: ${e.message}")
            } catch (e: Exception) {
                RepoScan(owner, repo, result = null, error = e.message ?: e.javaClass.simpleName)
            }
        }
        val artifacts = scans.flatMap { s -> s.result?.artifacts.orEmpty().map { RepoArtifact(s.owner, s.repo, it) } }
        val similar = SimilarityDetector
            .findGroups(artifacts, key = { "${it.owner}/${it.repo}/${it.artifact.path}" }, artifact = { it.artifact })
            .map { (members, score) -> MultiSimilarGroup(members, score) }
        return MultiScanResult(scans, artifacts, similar)
    }
}
