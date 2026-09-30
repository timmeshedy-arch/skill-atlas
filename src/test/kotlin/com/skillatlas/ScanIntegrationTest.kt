package com.skillatlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Интеграционные тесты: `аргументы → HTTP → парсинг → отчёт`.
 * Весь путь настоящий, подменён только адрес GitHub (см. [StubGitHub]).
 */
class ScanIntegrationTest {

    private lateinit var gh: StubGitHub
    private val out = StringBuilder()
    private val err = StringBuilder()

    @BeforeTest
    fun setUp() {
        gh = StubGitHub()
    }

    @AfterTest
    fun tearDown() {
        gh.close()
    }

    private fun app() = gh.scanApp(out, err)

    private fun givenRepo(
        vararg files: Pair<String, String>,
        branch: String = MAIN,
        truncated: Boolean = false,
        trees: List<String> = emptyList(),
    ) {
        gh.repo(OWNER, REPO, branch)
        gh.tree(OWNER, REPO, branch, blobs = files.map { it.first }, trees = trees, truncated = truncated)
        files.forEach { (path, content) -> gh.file(OWNER, REPO, branch, path, content) }
    }

    private fun skill(name: String, description: String) =
        "---\nname: $name\ndescription: $description\n---\n\nBody text.\n"

    private fun permalink(path: String) =
        "https://github.com/$OWNER/$REPO/blob/${StubGitHub.SHA}/$path"

    // --- happy path ---

    @Test
    fun `table report shows name status permalink and description`() {
        givenRepo(SKILL_ALPHA to skill("alpha", "Does alpha things."))

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_OK, code)
        assertEquals(
            listOf(
                "skill-atlas scan report — $OWNER/$REPO @ $MAIN (sha: 8a1541c)",
                "",
                "SKILLS (1)",
                "  ✔ alpha — valid",
                "    ${permalink(SKILL_ALPHA)}",
                "    Does alpha things.",
                "",
                "COMMANDS (0)",
                "",
                "Total: 1 artifacts found, 1 valid, 0 invalid",
            ).joinToString("\n"),
            out.toString().trimEnd('\n'),
        )
    }

    @Test
    fun `command without frontmatter is valid and takes its name from the file`() {
        givenRepo(".claude/commands/fmt.md" to "Run ktlint over the changed files.\n")

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_OK, code)
        assertContains(out, "  ✔ /fmt — valid")
        assertContains(out, "Total: 1 artifacts found, 1 valid, 0 invalid")
    }

    // --- валидация ---

    @Test
    fun `skill without frontmatter is invalid`() {
        givenRepo(SKILL_ALPHA to "# Just markdown, no frontmatter\n")

        app().run("$OWNER/$REPO")

        assertContains(out, "  ✖ — — invalid: missing frontmatter")
    }

    @Test
    fun `skill without description is invalid`() {
        givenRepo(SKILL_ALPHA to "---\nname: alpha\n---\n")

        app().run("$OWNER/$REPO")

        assertContains(out, "  ✖ alpha — invalid: missing 'description'")
    }

    @Test
    fun `duplicate skill names invalidate every copy`() {
        givenRepo(
            SKILL_ALPHA to skill("alpha", "First."),
            ".agents/skills/alpha/SKILL.md" to skill("alpha", "Second."),
        )

        app().run("$OWNER/$REPO")

        assertEquals(2, Regex("invalid: duplicate name 'alpha'").findAll(out).count())
    }

    @Test
    fun `long description is truncated in table but full in json`() {
        val long = "x".repeat(200)
        givenRepo(SKILL_ALPHA to skill("alpha", long))

        app().run("$OWNER/$REPO")
        assertContains(out, "    ${"x".repeat(160)}…")

        val jsonOut = StringBuilder()
        gh.scanApp(jsonOut, err).run("$OWNER/$REPO", format = "json")
        val described = Json.parseToJsonElement(jsonOut.toString())
            .jsonObject["artifacts"]!!.jsonArray[0].jsonObject["description"]!!.jsonPrimitive.content
        assertEquals(long, described)
    }

    // --- обнаружение ---

    @Test
    fun `directory entries are ignored`() {
        givenRepo(trees = listOf(".claude/skills/alpha/SKILL.md"))

        app().run("$OWNER/$REPO")

        assertContains(out, "Total: 0 artifacts found, 0 valid, 0 invalid")
    }

    @Test
    fun `empty tree yields an empty report`() {
        givenRepo()

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_OK, code)
        assertContains(out, "SKILLS (0)")
        assertContains(out, "Total: 0 artifacts found, 0 valid, 0 invalid")
    }

    @Test
    fun `truncated tree produces a warning but not a failure`() {
        givenRepo(SKILL_ALPHA to skill("alpha", "Does alpha things."), truncated = true)

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_OK, code)
        assertContains(out, "WARNING: GitHub tree API response was truncated")
    }

    // --- форматы ---

    @Test
    fun `md format skips empty groups and links the name`() {
        givenRepo(SKILL_ALPHA to skill("alpha", "Does alpha things."))

        app().run("$OWNER/$REPO", format = "md")

        assertContains(out, "- ✅ [**alpha**](${permalink(SKILL_ALPHA)}) — valid")
        assertTrue("COMMAND" !in out.toString(), "пустая группа не должна попадать в md")
    }

    @Test
    fun `json format emits nulls for missing fields`() {
        givenRepo(SKILL_ALPHA to "---\nname: alpha\n---\n")

        app().run("$OWNER/$REPO", format = "json")

        val artifact = Json.parseToJsonElement(out.toString())
            .jsonObject["artifacts"]!!.jsonArray[0].jsonObject
        assertEquals("alpha", artifact["name"]!!.jsonPrimitive.content)
        assertNull(artifact["description"]!!.jsonPrimitive.contentOrNull)
        assertEquals("missing 'description'", artifact["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `output option writes the report to a file and leaves stdout empty`() {
        givenRepo(SKILL_ALPHA to skill("alpha", "Does alpha things."))
        val target = Files.createTempFile("skill-atlas-report", ".txt")

        val code = app().run("$OWNER/$REPO", output = target.toString())

        assertEquals(EXIT_OK, code)
        assertEquals("", out.toString())
        assertContains(target.toFile().readText(), "  ✔ alpha — valid")
    }

    @Test
    fun `unknown format is an argument error`() {
        val code = app().run("$OWNER/$REPO", format = "yaml")

        assertEquals(EXIT_ARG_ERROR, code)
        assertContains(err, "unknown --format 'yaml'")
    }

    @Test
    fun `unparseable repo argument is an argument error`() {
        val code = app().run("not-a-repo")

        assertEquals(EXIT_ARG_ERROR, code)
        assertContains(err, "could not parse a GitHub owner/repo")
    }

    // --- HTTP ---

    @Test
    fun `missing repository exits with not found`() {
        gh.repoFails(OWNER, REPO, 404)

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_NOT_FOUND, code)
        assertContains(err, "repository or ref not found")
    }

    @Test
    fun `exhausted rate limit exits with the rate limit code`() {
        gh.repoFails(OWNER, REPO, 403, headers = mapOf("x-ratelimit-remaining" to "0"))

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_RATE_LIMITED, code)
    }

    @Test
    fun `dropped connection is a network error`() {
        gh.repoDropsConnection(OWNER, REPO)

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_NETWORK_ERROR, code)
        assertContains(err, "network error")
    }

    @Test
    fun `token is sent as a bearer header and omitted when absent`() {
        givenRepo(SKILL_ALPHA to skill("alpha", "Does alpha things."))

        app().run("$OWNER/$REPO", token = "ghp_secret")
        assertEquals("Bearer ghp_secret", gh.requests.first().header("Authorization"))

        gh.close()
        gh = StubGitHub()
        givenRepo(SKILL_ALPHA to skill("alpha", "Does alpha things."))
        gh.scanApp(StringBuilder(), err).run("$OWNER/$REPO", token = null)
        assertNull(gh.requests.first().header("Authorization"))
    }

    @Test
    fun `explicit ref is used instead of the default branch`() {
        gh.repo(OWNER, REPO, defaultBranch = MAIN)
        gh.tree(OWNER, REPO, "develop", blobs = listOf(SKILL_ALPHA))
        gh.file(OWNER, REPO, "develop", SKILL_ALPHA, skill("alpha", "Does alpha things."))

        val code = app().run("$OWNER/$REPO", ref = "develop")

        assertEquals(EXIT_OK, code)
        assertContains(out, "@ develop")
        assertTrue(gh.requests.any { it.path == "repos/$OWNER/$REPO/git/trees/develop" })
    }

    private companion object {
        const val OWNER = "owner"
        const val REPO = "repo"
        const val MAIN = "main"
        const val SKILL_ALPHA = ".claude/skills/alpha/SKILL.md"
    }
}
