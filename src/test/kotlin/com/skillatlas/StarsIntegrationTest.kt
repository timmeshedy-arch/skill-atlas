package com.skillatlas

import com.skillatlas.web.StarStore
import com.skillatlas.web.WebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Интеграционные тесты звёзд `serve`: `HTTP-запрос UI → WebServer → файл звёзд`.
 */
class StarsIntegrationTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var gh: StubGitHub
    private lateinit var web: WebServer
    private val http = HttpClient.newHttpClient()

    private val starsFile: Path get() = dir.resolve("nested/stars.json")

    @BeforeTest
    fun setUp() {
        gh = StubGitHub()
        web = server()
    }

    @AfterTest
    fun tearDown() {
        web.close()
        gh.close()
    }

    private fun server() = WebServer(
        port = 0,
        token = null,
        stars = StarStore(starsFile),
        apiBase = gh.apiBase,
        rawBase = gh.rawBase,
    ).also { it.start() }

    private fun send(method: String, path: String, server: WebServer = web): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun query(owner: String, repo: String, path: String) =
        listOf("owner" to owner, "repo" to repo, "path" to path)
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }

    private fun star(owner: String = OWNER, repo: String = REPO, path: String = SKILL_ALPHA) =
        send("PUT", "/api/stars?" + query(owner, repo, path))

    private fun unstar(owner: String = OWNER, repo: String = REPO, path: String = SKILL_ALPHA) =
        send("DELETE", "/api/stars?" + query(owner, repo, path))

    private fun stars(server: WebServer = web) = send("GET", "/api/stars", server)

    private fun json(response: HttpResponse<String>): JsonObject = Json.parseToJsonElement(response.body()).jsonObject

    /** Звёзды из ответа в виде `owner/repo/path`. */
    private fun keys(response: HttpResponse<String>): List<String> =
        json(response)["stars"]!!.jsonArray.map { it.jsonObject }.map {
            listOf("owner", "repo", "path").joinToString("/") { key -> it[key]!!.jsonPrimitive.content }
        }

    @Test
    fun `no stars file means no stars and nothing is written`() {
        val response = stars()

        assertEquals(200, response.statusCode())
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElse(null))
        assertEquals("""{"stars":[]}""", response.body())
        assertFalse(starsFile.exists())
    }

    @Test
    fun `starring adds the artifact once`() {
        val first = star()

        assertEquals(200, first.statusCode())
        assertEquals(listOf("$OWNER/$REPO/$SKILL_ALPHA"), keys(first))
        assertEquals(listOf("$OWNER/$REPO/$SKILL_ALPHA"), keys(star()))
        assertEquals(listOf("$OWNER/$REPO/$SKILL_ALPHA"), keys(stars()))
    }

    @Test
    fun `stars are sorted by owner, repo and path`() {
        star(repo = OTHER, path = COMMAND_FMT)
        star(path = SKILL_BETA)
        star()

        assertEquals(
            listOf("$OWNER/$OTHER/$COMMAND_FMT", "$OWNER/$REPO/$SKILL_ALPHA", "$OWNER/$REPO/$SKILL_BETA"),
            keys(stars()),
        )
    }

    @Test
    fun `unstarring removes the artifact and unknown stars are ignored`() {
        star()
        star(path = SKILL_BETA)

        val response = unstar()

        assertEquals(200, response.statusCode())
        assertEquals(listOf("$OWNER/$REPO/$SKILL_BETA"), keys(response))
        assertEquals(200, unstar().statusCode())
        assertEquals(listOf("$OWNER/$REPO/$SKILL_BETA"), keys(stars()))
    }

    @Test
    fun `owner and repo are case-insensitive, path is not`() {
        star(owner = "Owner", repo = "Repo")

        assertEquals(listOf("$OWNER/$REPO/$SKILL_ALPHA"), keys(star()))
        assertEquals(2, keys(star(path = SKILL_ALPHA.uppercase())).size)
        assertEquals(listOf("$OWNER/$REPO/${SKILL_ALPHA.uppercase()}"), keys(unstar(owner = "OWNER", repo = "REPO")))
    }

    @Test
    fun `path with spaces and unicode is kept as is`() {
        val path = ".claude/skills/ревью кода/SKILL.md"

        assertEquals(listOf("$OWNER/$REPO/$path"), keys(star(path = path)))
        assertEquals(listOf("$OWNER/$REPO/$path"), keys(stars()))
    }

    @Test
    fun `stars survive a server restart`() {
        star()
        web.close()

        web = server()

        assertEquals(listOf("$OWNER/$REPO/$SKILL_ALPHA"), keys(stars()))
    }

    @Test
    fun `malformed stars file fails at startup and is not overwritten`() {
        Files.createDirectories(starsFile.parent)
        starsFile.writeText("{not json")

        val e = assertFailsWith<IllegalArgumentException> { StarStore(starsFile) }

        assertContains(e.message.orEmpty(), starsFile.toString())
        assertEquals("{not json", starsFile.readText())
    }

    @Test
    fun `unwritable stars file is a server error and the star is not kept`() {
        Files.createDirectories(dir)
        dir.resolve("nested").writeText("a file where the directory should be")

        val response = star()

        assertEquals(500, response.statusCode())
        assertContains(json(response)["error"]!!.jsonPrimitive.content, "cannot save stars")
        assertEquals(listOf(), keys(stars()))
    }

    @Test
    fun `blank or slashed owner, repo and path are bad requests`() {
        val bad = listOf(
            send("PUT", "/api/stars"),
            star(owner = ""),
            star(repo = " "),
            star(path = ""),
            star(owner = "a/b"),
            unstar(repo = "x/y"),
        )

        assertEquals(List(bad.size) { 400 }, bad.map { it.statusCode() })
        assertContains(json(bad.first())["error"]!!.jsonPrimitive.content, "owner")
        assertFalse(starsFile.exists())
    }

    @Test
    fun `stars accept only GET, PUT and DELETE and never call GitHub`() {
        star()
        unstar()
        stars()

        assertEquals(405, send("POST", "/api/stars?" + query(OWNER, REPO, SKILL_ALPHA)).statusCode())
        assertEquals(405, send("PUT", "/api/scan?repo=a/b").statusCode())
        assertEquals(405, send("DELETE", "/").statusCode())
        assertTrue(gh.requests.isEmpty())
    }

    // Сам клик — клиентский JS, в JVM его не прогнать; проверяем, что UI его отдаёт.
    @Test
    fun `web UI ships the star toggle and the starred filter`() {
        val body = send("GET", "/").body()

        assertContains(body, "api/stars")
        assertContains(body, """className: "star"""")
        assertContains(body, """<button id="starred"""")
        assertContains(body, """qs.get("starred")""")
        assertContains(body, "No starred items.")
    }

    private companion object {
        const val OWNER = "owner"
        const val REPO = "repo"
        const val OTHER = "other"
        const val SKILL_ALPHA = ".claude/skills/alpha/SKILL.md"
        const val SKILL_BETA = ".claude/skills/beta/SKILL.md"
        const val COMMAND_FMT = ".claude/commands/fmt.md"
    }
}
