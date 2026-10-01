package com.skillatlas

import com.skillatlas.StubGitHub.ListedRepo
import com.skillatlas.web.StarStore
import com.skillatlas.web.WebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import java.nio.file.Path
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

    @TempDir
    lateinit var dir: Path

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
        WebServer(
            port = 0,
            token = token,
            stars = StarStore(dir.resolve("stars.json")),
            apiBase = gh.apiBase,
            rawBase = gh.rawBase,
        ).also { it.start() }

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

    private fun orgScan(vararg orgs: String): HttpResponse<String> =
        get("/api/org-scan?" + orgs.joinToString("&") { "org=" + URLEncoder.encode(it, StandardCharsets.UTF_8) })

    private fun json(response: HttpResponse<String>): JsonObject = Json.parseToJsonElement(response.body()).jsonObject

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun givenAlpha(branch: String = MAIN) {
        gh.repo(OWNER, REPO, MAIN)
        gh.tree(OWNER, REPO, branch, blobs = listOf(SKILL_ALPHA))
        gh.file(OWNER, REPO, branch, SKILL_ALPHA, "---\nname: alpha\ndescription: Does alpha things.\n---\n")
    }

    private fun givenSkill(repo: String, name: String, description: String, branch: String = MAIN, owner: String = ORG) {
        gh.tree(owner, repo, branch, blobs = listOf(SKILL_ALPHA))
        gh.file(owner, repo, branch, SKILL_ALPHA, "---\nname: $name\ndescription: $description\n---\n")
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
    fun `web UI scans organizations without a ref input`() {
        val body = get("/").body()

        assertFalse(body.contains("""id="ref""""))
        assertFalse(body.contains("api/multi-scan"))
        assertContains(body, """<input id="org"""")
        assertContains(body, """<button id="add"""")
        assertContains(body, "api/org-scan")
        assertContains(body, """qs.getAll("org")""")
    }

    @Test
    fun `org-scan scans every repo of the org by its default branch`() {
        gh.ownerRepos(ORG, listOf(ListedRepo(REPO), ListedRepo(OTHER, defaultBranch = "trunk")))
        givenSkill(REPO, "alpha", "Does alpha things.")
        givenSkill(OTHER, "beta", "Builds beta widgets.", branch = "trunk")

        val response = orgScan(ORG)

        assertEquals(200, response.statusCode())
        val body = json(response)
        val org = body["orgs"]!!.jsonArray.single().jsonObject
        assertEquals(ORG, org.str("org"))
        assertEquals(2, org["repos"]!!.jsonPrimitive.int)
        assertFalse(org["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, org["error"])
        val repos = body["repos"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(REPO, OTHER), repos.map { it.str("repo") })
        assertEquals(listOf(MAIN, "trunk"), repos.map { it.str("ref") })
        assertTrue(repos.all { it.str("sha") == StubGitHub.SHA && it["error"] == JsonNull })
        val artifacts = body["artifacts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("$ORG/$REPO:alpha", "$ORG/$OTHER:beta"),
            artifacts.map { "${it.str("owner")}/${it.str("repo")}:${it.str("name")}" },
        )
        assertEquals(0, body["similar"]!!.jsonArray.size)
    }

    @Test
    fun `org listing is one page of the most recently pushed repos, default branch is not refetched`() {
        gh.ownerRepos(ORG, listOf(ListedRepo(REPO)))
        givenSkill(REPO, "alpha", "Does alpha things.")

        orgScan(ORG)

        val listing = gh.requests.single { it.path == "orgs/$ORG/repos" }
        assertEquals(setOf("per_page=100", "sort=pushed"), listing.query!!.split("&").toSet())
        assertTrue(gh.requests.none { it.path == "repos/$ORG/$REPO" })
    }

    @Test
    fun `forks and archived repos are skipped`() {
        gh.ownerRepos(
            ORG,
            listOf(ListedRepo(REPO), ListedRepo("forked", fork = true), ListedRepo("old", archived = true)),
        )
        givenSkill(REPO, "alpha", "Does alpha things.")

        val body = json(orgScan(ORG))

        assertEquals(1, body["orgs"]!!.jsonArray.single().jsonObject["repos"]!!.jsonPrimitive.int)
        assertEquals(listOf(REPO), body["repos"]!!.jsonArray.map { it.jsonObject.str("repo") })
        assertTrue(gh.requests.none { "forked" in it.path || "old" in it.path })
    }

    @Test
    fun `user account is scanned when there is no such organization`() {
        gh.ownerRepos(ORG, listOf(ListedRepo(REPO)), user = true)
        givenSkill(REPO, "alpha", "Does alpha things.")

        val body = json(orgScan(ORG))

        assertEquals(JsonNull, body["orgs"]!!.jsonArray.single().jsonObject["error"])
        assertEquals("alpha", body["artifacts"]!!.jsonArray.single().jsonObject.str("name"))
        assertEquals(listOf("orgs/$ORG/repos", "users/$ORG/repos"), gh.requests.map { it.path }.take(2))
    }

    @Test
    fun `repo owner is the canonical login from GitHub`() {
        gh.ownerRepos(ORG.uppercase(), listOf(ListedRepo(REPO)), login = ORG)
        givenSkill(REPO, "alpha", "Does alpha things.")

        val artifact = json(orgScan(ORG.uppercase()))["artifacts"]!!.jsonArray.single().jsonObject

        assertEquals(ORG, artifact.str("owner"))
        assertEquals("https://github.com/$ORG/$REPO/blob/${StubGitHub.SHA}/$SKILL_ALPHA", artifact.str("url"))
    }

    @Test
    fun `org with more repos than one page is marked truncated`() {
        gh.ownerRepos(ORG, listOf(ListedRepo(REPO)), hasMore = true)
        givenSkill(REPO, "alpha", "Does alpha things.")

        val org = json(orgScan(ORG))["orgs"]!!.jsonArray.single().jsonObject

        assertTrue(org["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(1, org["repos"]!!.jsonPrimitive.int)
    }

    @Test
    fun `unknown org is reported per org and does not break the others`() {
        gh.ownerRepos(ORG, listOf(ListedRepo(REPO)))
        givenSkill(REPO, "alpha", "Does alpha things.")

        val response = orgScan("ghost", ORG)

        assertEquals(200, response.statusCode())
        val body = json(response)
        val (failed, ok) = body["orgs"]!!.jsonArray.map { it.jsonObject }
        assertEquals("organization not found", failed.str("error"))
        assertEquals(0, failed["repos"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, ok["error"])
        assertEquals(REPO, body["artifacts"]!!.jsonArray.single().jsonObject.str("repo"))
    }

    @Test
    fun `rate limit on org listing is reported per org`() {
        gh.ownerReposFail(ORG, 403, headers = mapOf("x-ratelimit-remaining" to "0"))

        val response = orgScan(ORG)

        assertEquals(200, response.statusCode())
        assertContains(json(response)["orgs"]!!.jsonArray.single().jsonObject.str("error"), "rate limit")
    }

    @Test
    fun `failing and empty repos are reported per repo and do not break the others`() {
        gh.ownerRepos(ORG, listOf(ListedRepo("empty"), ListedRepo("broken"), ListedRepo(REPO)))
        gh.treeFails(ORG, "empty", MAIN, status = 409)
        gh.treeFails(ORG, "broken", MAIN, status = 500)
        givenSkill(REPO, "alpha", "Does alpha things.")

        val response = orgScan(ORG)

        assertEquals(200, response.statusCode())
        val body = json(response)
        val (empty, broken, ok) = body["repos"]!!.jsonArray.map { it.jsonObject }
        assertEquals("repository is empty", empty.str("error"))
        assertEquals(JsonNull, empty["sha"])
        assertContains(broken.str("error"), "network error")
        assertEquals(JsonNull, ok["error"])
        assertEquals(REPO, body["artifacts"]!!.jsonArray.single().jsonObject.str("repo"))
    }

    @Test
    fun `same skill in two orgs is valid and similar across orgs`() {
        gh.ownerRepos(ORG, listOf(ListedRepo(REPO)))
        gh.ownerRepos(OTHER_ORG, listOf(ListedRepo(REPO)))
        givenSkill(REPO, "alpha", "Does alpha things.")
        givenSkill(REPO, "alpha", "Does alpha things.", owner = OTHER_ORG)

        val body = json(orgScan(ORG, OTHER_ORG))

        assertTrue(body["artifacts"]!!.jsonArray.all { it.jsonObject["valid"]!!.jsonPrimitive.boolean })
        val group = body["similar"]!!.jsonArray.single().jsonObject
        assertEquals(1.0, group.str("score").toDouble())
        assertEquals(
            listOf("$ORG/$REPO/$SKILL_ALPHA", "$OTHER_ORG/$REPO/$SKILL_ALPHA"),
            group["members"]!!.jsonArray.map { it.jsonObject }.map { "${it.str("owner")}/${it.str("repo")}/${it.str("path")}" },
        )
    }

    @Test
    fun `duplicate orgs in any accepted form are scanned once`() {
        gh.ownerRepos(ORG, listOf(ListedRepo(REPO)))
        givenSkill(REPO, "alpha", "Does alpha things.")

        val body = json(orgScan(ORG, "https://github.com/${ORG.uppercase()}/", "@$ORG"))

        assertEquals(1, body["orgs"]!!.jsonArray.size)
        assertEquals(1, gh.requests.count { it.path == "orgs/$ORG/repos" })
        assertEquals(1, body["repos"]!!.jsonArray.size)
    }

    @Test
    fun `org-scan rejects no orgs, too many orgs and unparsable orgs`() {
        assertEquals(400, get("/api/org-scan").statusCode())
        assertEquals(400, orgScan(*Array(6) { "org$it" }).statusCode())
        for (bad in listOf("$ORG/$REPO", "https://github.com/$ORG/$REPO", "-bad", "a".repeat(40), "")) {
            val response = orgScan(ORG, bad)
            assertEquals(400, response.statusCode(), "'$bad'")
            assertContains(json(response).str("error"), "'$bad'")
        }
        assertTrue(gh.requests.isEmpty())
    }

    @Test
    fun `repo list endpoint is gone`() {
        assertEquals(404, get("/api/multi-scan?repo=$ORG/$REPO").statusCode())
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
        const val OTHER = "other"
        const val ORG = "acme"
        const val OTHER_ORG = "globex"
        const val MAIN = "main"
        const val SKILL_ALPHA = ".claude/skills/alpha/SKILL.md"
        const val SECRET = "ghp_supersecret"
    }
}
