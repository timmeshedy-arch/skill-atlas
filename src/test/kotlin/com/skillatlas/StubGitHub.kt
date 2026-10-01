package com.skillatlas

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Локальный стаб GitHub API на встроенном `com.sun.net.httpserver.HttpServer`.
 *
 * Один сервер обслуживает оба базовых URL, которые нужны туле:
 *  - API  → `http://127.0.0.1:<port>`      (`/repos/...`)
 *  - raw  → `http://127.0.0.1:<port>/raw`  (`/raw/<owner>/<repo>/<ref>/<path>`)
 *
 * Префикс `/raw` убирает коллизию с `/repos`, так что подмены одного адреса хватает.
 * Ответы регистрируются по пути (query игнорируется), все запросы записываются —
 * чтобы проверять заголовки, попавший в URL ref и отсутствие утечки токена.
 */
class StubGitHub : AutoCloseable {

    data class Stubbed(
        val status: Int = 200,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        /** Закрыть соединение без ответа — клиент получит IOException. */
        val dropConnection: Boolean = false,
    )

    data class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val stubs = LinkedHashMap<String, Stubbed>()
    private val recorded = mutableListOf<Recorded>()

    val port: Int get() = server.address.port
    val apiBase: String get() = "http://127.0.0.1:$port"
    val rawBase: String get() = "http://127.0.0.1:$port/raw"

    val requests: List<Recorded> get() = synchronized(recorded) { recorded.toList() }

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    /** Собирает [ScanApp], направленный на этот стаб. */
    fun scanApp(out: Appendable = StringBuilder(), err: Appendable = StringBuilder()): ScanApp =
        ScanApp(apiBase = apiBase, rawBase = rawBase, out = out, err = err)

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trimStart('/')
        synchronized(recorded) {
            recorded += Recorded(
                method = exchange.requestMethod,
                path = path,
                query = exchange.requestURI.query,
                headers = exchange.requestHeaders.mapValues { it.value.toList() },
            )
        }

        val stub = synchronized(stubs) { stubs[path] }
        if (stub == null) {
            respond(exchange, 404, """{"message":"Not Found (no stub for /$path)"}""", emptyMap())
            return
        }
        if (stub.dropConnection) {
            exchange.close()
            return
        }
        respond(exchange, stub.status, stub.body, stub.headers)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String>) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        if (exchange.responseHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // --- регистрация ответов ---

    fun stub(path: String, response: Stubbed) {
        synchronized(stubs) { stubs[path.trimStart('/')] = response }
    }

    fun repo(owner: String, repo: String, defaultBranch: String = "main") {
        val body = buildJsonObject { put("default_branch", defaultBranch) }.toString()
        stub("repos/$owner/$repo", Stubbed(body = body))
    }

    fun repoFails(
        owner: String,
        repo: String,
        status: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = """{"message":"stub $status"}""",
    ) {
        stub("repos/$owner/$repo", Stubbed(status = status, body = body, headers = headers))
    }

    fun repoDropsConnection(owner: String, repo: String) {
        stub("repos/$owner/$repo", Stubbed(dropConnection = true))
    }

    fun tree(
        owner: String,
        repo: String,
        ref: String,
        sha: String = SHA,
        blobs: List<String> = emptyList(),
        trees: List<String> = emptyList(),
        truncated: Boolean = false,
    ) {
        val entries: List<JsonObject> = blobs.map { entry(it, "blob") } + trees.map { entry(it, "tree") }
        val body = buildJsonObject {
            put("sha", sha)
            put("truncated", truncated)
            put("tree", JsonArray(entries))
        }.toString()
        stub("repos/$owner/$repo/git/trees/$ref", Stubbed(body = body))
    }

    /** Репозиторий в ответе листинга `/orgs/{org}/repos`. */
    data class ListedRepo(
        val name: String,
        val defaultBranch: String = "main",
        val fork: Boolean = false,
        val archived: Boolean = false,
    )

    /**
     * Листинг репозиториев владельца: `orgs/<owner>/repos`, а при [user] — `users/<owner>/repos`
     * (для организации с таким именем стаба нет → 404, как у GitHub для пользователя).
     * [hasMore] добавляет `Link: rel="next"` — у владельца есть следующая страница.
     */
    fun ownerRepos(
        owner: String,
        repos: List<ListedRepo>,
        hasMore: Boolean = false,
        user: Boolean = false,
        login: String = owner,
    ) {
        val body = JsonArray(repos.map { r ->
            buildJsonObject {
                put("name", r.name)
                put("owner", buildJsonObject { put("login", login) })
                put("default_branch", r.defaultBranch)
                put("fork", r.fork)
                put("archived", r.archived)
            }
        }).toString()
        val headers = if (hasMore) {
            mapOf("Link" to """<$apiBase/orgs/$owner/repos?page=2>; rel="next", <$apiBase/orgs/$owner/repos?page=3>; rel="last"""")
        } else {
            emptyMap()
        }
        stub("${if (user) "users" else "orgs"}/$owner/repos", Stubbed(body = body, headers = headers))
    }

    fun ownerReposFail(owner: String, status: Int, headers: Map<String, String> = emptyMap()) {
        stub("orgs/$owner/repos", Stubbed(status = status, body = """{"message":"stub $status"}""", headers = headers))
    }

    fun treeFails(owner: String, repo: String, ref: String, status: Int) {
        stub("repos/$owner/$repo/git/trees/$ref", Stubbed(status = status, body = """{"message":"stub $status"}"""))
    }

    private fun entry(path: String, type: String): JsonObject = buildJsonObject {
        put("path", path)
        put("type", type)
    }

    fun file(owner: String, repo: String, ref: String, path: String, content: String) {
        stub(
            "raw/$owner/$repo/$ref/$path",
            Stubbed(body = content, headers = mapOf("Content-Type" to "text/plain; charset=utf-8")),
        )
    }

    fun fileFails(owner: String, repo: String, ref: String, path: String, status: Int) {
        stub("raw/$owner/$repo/$ref/$path", Stubbed(status = status, body = "stub $status"))
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        /** Фиксированный sha, чтобы permalink'и в ожидаемых отчётах были стабильны. */
        const val SHA = "8a1541c4a3ffa5a20a5a91de0dcf3f0bab1d1ef4"
    }
}
