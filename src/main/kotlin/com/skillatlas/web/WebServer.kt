package com.skillatlas.web

import com.skillatlas.github.DEFAULT_API_BASE
import com.skillatlas.github.DEFAULT_RAW_BASE
import com.skillatlas.github.GitHubClient
import com.skillatlas.github.GitHubException
import com.skillatlas.parseOrg
import com.skillatlas.parseRepo
import com.skillatlas.report.ReportFormatter
import com.skillatlas.scan.OrgScanner
import com.skillatlas.scan.Scanner
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Локальный веб-UI для `serve`: одностраничник из `resources/web/index.html` и `GET /api/scan`.
 *
 * `/api/scan?repo=<repo>&ref=<ref>` отдаёт ту же структуру, что `--format json`.
 * Ошибки GitHub маппятся в HTTP-статусы (404 / 429 / 502) с телом `{"error": "..."}`.
 * `/api/org-scan?org=<org>&org=<org>…` — скан всех репо до [MAX_ORGS] организаций, которым
 * пользуется UI; ошибки отдельных организаций и репо уходят в тело ответа, а не в статус.
 * Слушает только 127.0.0.1: токен живёт на сервере, наружу его отдавать некому.
 */
/** Лимит организаций на один `/api/org-scan` — чтобы один клик не съел rate limit. */
const val MAX_ORGS = 5

class WebServer(
    port: Int = 8080,
    private val token: String?,
    private val apiBase: String = DEFAULT_API_BASE,
    private val rawBase: String = DEFAULT_RAW_BASE,
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)

    val port: Int get() = server.address.port

    init {
        server.executor = executor
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
    }

    fun start() = server.start()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            return respondJson(exchange, 405, error("method not allowed"))
        }
        when (exchange.requestURI.path) {
            "/", "/index.html" -> serveIndex(exchange)
            "/api/scan" -> serveScan(exchange)
            "/api/org-scan" -> serveOrgScan(exchange)
            else -> respondJson(exchange, 404, error("not found"))
        }
    }

    private fun serveIndex(exchange: HttpExchange) {
        val page = javaClass.getResourceAsStream("/web/index.html")?.use { it.readBytes() }
            ?: return respondJson(exchange, 500, error("web UI resource is missing"))
        respond(exchange, 200, "text/html; charset=utf-8", page)
    }

    private fun serveScan(exchange: HttpExchange) {
        val params = parseQuery(exchange.requestURI.rawQuery)
        val repoArg = params["repo"]?.last().orEmpty()
        val (owner, repo) = parseRepo(repoArg)
            ?: return respondJson(exchange, 400, error("could not parse a GitHub owner/repo from '$repoArg'"))

        val client = GitHubClient(token = token, apiBase = apiBase, rawBase = rawBase)
        val (status, body) = try {
            200 to ReportFormatter.toJson(Scanner(client).scan(owner, repo, params["ref"]?.last().orEmpty().trim()))
        } catch (e: GitHubException.NotFound) {
            404 to error("repository or ref not found")
        } catch (e: GitHubException.RateLimited) {
            429 to error(e.message.orEmpty())
        } catch (e: GitHubException.Network) {
            502 to error("network error: ${e.message}")
        } catch (e: Exception) {
            500 to error(e.message ?: e.javaClass.simpleName)
        }
        respondJson(exchange, status, body)
    }

    private fun serveOrgScan(exchange: HttpExchange) {
        val orgArgs = parseQuery(exchange.requestURI.rawQuery)["org"].orEmpty()
        val orgs = orgArgs
            .map { arg ->
                parseOrg(arg)
                    ?: return respondJson(exchange, 400, error("could not parse a GitHub organization from '$arg'"))
            }
            .distinctBy { it.lowercase() }
        if (orgs.isEmpty()) return respondJson(exchange, 400, error("at least one org is required"))
        if (orgs.size > MAX_ORGS) return respondJson(exchange, 400, error("at most $MAX_ORGS orgs per scan"))

        val client = GitHubClient(token = token, apiBase = apiBase, rawBase = rawBase)
        respondJson(exchange, 200, ReportFormatter.toJson(OrgScanner(client).scan(orgs)))
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>> =
        rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }
            .map { pair -> pair.split("=", limit = 2).let { decode(it[0]) to decode(it.getOrElse(1) { "" }) } }
            .groupBy({ it.first }, { it.second })

    private fun decode(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)

    private fun error(message: String) = buildJsonObject { put("error", message) }

    private fun respondJson(exchange: HttpExchange, status: Int, body: JsonObject) =
        respond(exchange, status, "application/json; charset=utf-8", body.toString().toByteArray(StandardCharsets.UTF_8))

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
