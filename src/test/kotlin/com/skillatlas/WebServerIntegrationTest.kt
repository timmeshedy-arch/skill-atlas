package com.skillatlas

import com.skillatlas.web.WebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Интеграционные тесты `serve`: `HTTP-запрос UI → WebServer → стаб GitHub → JSON`.
 */
class WebServerIntegrationTest {

    private lateinit var gh: StubGitHub
    private lateinit var web: WebServer
    private val http = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        gh = StubGitHub()
        web = server(token = null)
    }

    @AfterTest
    fun tearDown() {
        web.close()
        gh.close()
    }

    private fun server(token: String?) =
        WebServer(port = 0, token = token, apiBase = gh.apiBase, rawBase = gh.rawBase).also { it.start() }

    private fun get(path: String, server: WebServer = web, method: String = "GET"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun scan(repo: String, ref: String? = null, server: WebServer = web): HttpResponse<String> {
        val query = buildString {
            append("repo=").append(URLEncoder.encode(repo, StandardCharsets.UTF_8))
            ref?.let { append("&ref=").append(URLEncoder.encode(it, StandardCharsets.UTF_8)) }
        }
        return get("/api/scan?$query", server)
    }

    private fun json(response: HttpResponse<String>): JsonObject = Json.parseToJsonElement(response.body()).jsonObject

    private fun givenAlpha(branch: String = MAIN) {
        gh.repo(OWNER, REPO, MAIN)
        gh.tree(OWNER, REPO, branch, blobs = listOf(SKILL_ALPHA))
        gh.file(OWNER, REPO, branch, SKILL_ALPHA, "---\nname: alpha\ndescription: Does alpha things.\n---\n")
    }

    @Test
    fun `root serves the web UI`() {
        val response = get("/")

        assertEquals(200, response.statusCode())
        assertEquals("text/html; charset=utf-8", response.headers().firstValue("Content-Type").orElse(null))
        assertContains(response.body(), "<title>Skill Atlas</title>")
    }

    // Сама фильтрация — клиентский JS, в JVM его не прогнать; проверяем, что UI её отдаёт.
    @Test
    fun `web UI ships the name filter`() {
        val body = get("/").body()

        assertContains(body, """<input id="filter"""")
        assertContains(body, "Filter by name")
        assertContains(body, "No matches.")
        assertContains(body, """qs.get("q")""")
    }

    @Test
    fun `scan returns the json report`() {
        givenAlpha()

        val response = scan("https://github.com/$OWNER/$REPO")

        assertEquals(200, response.statusCode())
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElse(null))
        val body = json(response)
        assertEquals(OWNER, body["owner"]!!.jsonPrimitive.content)
        assertEquals(REPO, body["repo"]!!.jsonPrimitive.content)
        assertEquals(MAIN, body["ref"]!!.jsonPrimitive.content)
        assertEquals(StubGitHub.SHA, body["sha"]!!.jsonPrimitive.content)
        val artifact = body["artifacts"]!!.jsonArray.single().jsonObject
        assertEquals("alpha", artifact["name"]!!.jsonPrimitive.content)
        assertEquals("Does alpha things.", artifact["description"]!!.jsonPrimitive.content)
        assertEquals(
            "https://github.com/$OWNER/$REPO/blob/${StubGitHub.SHA}/$SKILL_ALPHA",
            artifact["url"]!!.jsonPrimitive.content,
        )
        assertTrue(artifact["valid"]!!.jsonPrimitive.boolean)
        assertEquals(0, body["similar"]!!.jsonArray.size)
    }

    @Test
    fun `ref param is passed through to the tree request`() {
        givenAlpha(branch = "develop")

        val response = scan("$OWNER/$REPO", ref = "develop")

        assertEquals(200, response.statusCode())
        assertEquals("develop", json(response)["ref"]!!.jsonPrimitive.content)
        assertTrue(gh.requests.any { it.path == "repos/$OWNER/$REPO/git/trees/develop" })
    }

    @Test
    fun `missing repo param is a bad request`() {
        val response = get("/api/scan")

        assertEquals(400, response.statusCode())
        assertContains(json(response)["error"]!!.jsonPrimitive.content, "could not parse")
    }

    @Test
    fun `unknown repo is not found`() {
        val response = scan("$OWNER/$REPO")

        assertEquals(404, response.statusCode())
        assertEquals("repository or ref not found", json(response)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rate limit maps to 429`() {
        gh.repoFails(OWNER, REPO, 403, headers = mapOf("x-ratelimit-remaining" to "0"))

        val response = scan("$OWNER/$REPO")

        assertEquals(429, response.statusCode())
        assertContains(json(response)["error"]!!.jsonPrimitive.content, "rate limit")
    }

    @Test
    fun `github server error maps to 502`() {
        gh.repoFails(OWNER, REPO, 500)

        val response = scan("$OWNER/$REPO")

        assertEquals(502, response.statusCode())
        assertContains(json(response)["error"]!!.jsonPrimitive.content, "network error")
    }

    @Test
    fun `unknown path is not found and non-GET is rejected`() {
        assertEquals(404, get("/nope").statusCode())
        assertEquals(405, get("/api/scan?repo=a/b", method = "POST").statusCode())
    }

    @Test
    fun `token is sent to GitHub but not leaked in the error body`() {
        gh.repoFails(OWNER, REPO, 403, headers = mapOf("x-ratelimit-remaining" to "0"))
        server(token = SECRET).use { withToken ->
            val response = scan("$OWNER/$REPO", server = withToken)

            assertEquals(429, response.statusCode())
            assertFalse(response.body().contains(SECRET))
            assertEquals("Bearer $SECRET", gh.requests.last().header("Authorization"))
        }
    }

    private companion object {
        const val OWNER = "owner"
        const val REPO = "repo"
        const val MAIN = "main"
        const val SKILL_ALPHA = ".claude/skills/alpha/SKILL.md"
        const val SECRET = "ghp_supersecret"
    }
}
