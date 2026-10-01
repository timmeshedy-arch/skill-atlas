package com.skillatlas.scan

import com.skillatlas.github.GitHubClient
import com.skillatlas.github.GitHubException
import com.skillatlas.github.OwnerRepo
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Сколько репозиториев сканируется одновременно: быстрее, но без всплеска запросов к GitHub. */
private const val SCAN_PARALLELISM = 8

/**
 * Скан всех репозиториев организаций по default branch. Для каждой организации берётся
 * одна страница её репозиториев ([GitHubClient.listOwnerRepos]), форки и архивные отбрасываются.
 * Артефакты сливаются в один список, похожие ищутся по всем репо сразу. Ошибка организации
 * или репо не прерывает остальные — она попадает в [OrgScan.error] / [RepoScan.error].
 * Валидация (дубли имени) остаётся внутри репо.
 */
class OrgScanner(private val client: GitHubClient) {

    private val scanner = Scanner(client)

    fun scan(orgs: List<String>): OrgScanResult {
        val listings = orgs.map { org -> list(org) }
        val targets = listings.flatMap { it.second }

        val pool = Executors.newFixedThreadPool(SCAN_PARALLELISM)
        val scans = try {
            targets.map { repo -> pool.submit(Callable { scanRepo(repo) }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val artifacts = scans.flatMap { s -> s.result?.artifacts.orEmpty().map { RepoArtifact(s.owner, s.repo, it) } }
        val similar = SimilarityDetector
            .findGroups(artifacts, key = { "${it.owner}/${it.repo}/${it.artifact.path}" }, artifact = { it.artifact })
            .map { (members, score) -> MultiSimilarGroup(members, score) }
        return OrgScanResult(listings.map { it.first }, scans, artifacts, similar)
    }

    private fun list(org: String): Pair<OrgScan, List<OwnerRepo>> {
        val page = try {
            client.listOwnerRepos(org)
        } catch (e: Exception) {
            val error = when (e) {
                is GitHubException.NotFound -> "organization not found"
                else -> errorMessage(e)
            }
            return OrgScan(org, repoCount = 0, truncated = false, error = error) to emptyList()
        }
        val repos = page.repos.filter { !it.fork && !it.archived }
        return OrgScan(org, repos.size, truncated = page.hasMore, error = null) to repos
    }

    private fun scanRepo(repo: OwnerRepo): RepoScan {
        val owner = repo.owner.login
        return try {
            RepoScan(owner, repo.name, scanner.scan(owner, repo.name, repo.default_branch), error = null)
        } catch (e: Exception) {
            val error = when (e) {
                is GitHubException.EmptyRepository -> "repository is empty"
                is GitHubException.NotFound -> "repository not found"
                else -> errorMessage(e)
            }
            RepoScan(owner, repo.name, result = null, error = error)
        }
    }

    private fun errorMessage(e: Exception): String = when (e) {
        is GitHubException.RateLimited -> e.message.orEmpty()
        is GitHubException.Network -> "network error: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
