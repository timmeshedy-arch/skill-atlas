package com.skillatlas.github

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

sealed class GitHubException(message: String) : Exception(message) {
    open class NotFound(message: String) : GitHubException(message)

    /** `409` на дерево: у репозитория нет ни одного коммита. */
    class EmptyRepository(message: String) : NotFound(message)
    class RateLimited(message: String) : GitHubException(message)
    class Network(message: String, cause: Throwable? = null) : GitHubException(message)
}

const val DEFAULT_API_BASE = "https://api.github.com"
const val DEFAULT_RAW_BASE = "https://raw.githubusercontent.com"

/** Максимум на страницу у GitHub; листинг репозиториев владельца берёт одну такую страницу. */
const val OWNER_REPOS_PAGE_SIZE = 100

class GitHubClient(
    private val token: String?,
    private val verbose: Boolean = false,
    private val apiBase: String = DEFAULT_API_BASE,
    private val rawBase: String = DEFAULT_RAW_BASE,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun baseRequest(uri: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(uri))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(30))
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun send(uri: String): HttpResponse<String> {
        if (verbose) System.err.println("[skill-atlas] GET $uri")
        val request = baseRequest(uri).GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw GitHubException.Network("Failed to reach GitHub API: ${e.message}", e)
        }
        when (response.statusCode()) {
            404 -> throw GitHubException.NotFound("Not found: $uri")
            409 -> throw GitHubException.EmptyRepository("Empty repository: $uri")
            403, 429 -> throw GitHubException.RateLimited(
                "GitHub API rate limit exceeded or authentication required for: $uri " +
                    "(pass --token or set GITHUB_TOKEN)"
            )
        }
        if (response.statusCode() >= 400) {
            throw GitHubException.Network("GitHub API error ${response.statusCode()} for $uri: ${response.body()}")
        }
        return response
    }

    fun getRepoInfo(owner: String, repo: String): RepoInfo {
        val response = send("$apiBase/repos/$owner/$repo")
        return json.decodeFromString(RepoInfo.serializer(), response.body())
    }

    fun getTree(owner: String, repo: String, ref: String): TreeResponse {
        val response = send("$apiBase/repos/$owner/$repo/git/trees/$ref?recursive=1")
        return json.decodeFromString(TreeResponse.serializer(), response.body())
    }

    /**
     * Первая страница репозиториев организации, самые недавно обновлённые первыми.
     * Если организации с таким именем нет — репозитории пользователя (`/users/{owner}/repos`).
     */
    fun listOwnerRepos(owner: String): OwnerRepos {
        val query = "per_page=$OWNER_REPOS_PAGE_SIZE&sort=pushed"
        val response = try {
            send("$apiBase/orgs/$owner/repos?$query")
        } catch (e: GitHubException.NotFound) {
            send("$apiBase/users/$owner/repos?$query")
        }
        val repos = json.decodeFromString(ListSerializer(OwnerRepo.serializer()), response.body())
        val hasMore = response.headers().allValues("Link").any { it.contains("rel=\"next\"") }
        return OwnerRepos(repos, hasMore)
    }

    fun getRawFile(owner: String, repo: String, ref: String, path: String): String {
        val response = send("$rawBase/$owner/$repo/$ref/$path")
        return response.body()
    }
}
