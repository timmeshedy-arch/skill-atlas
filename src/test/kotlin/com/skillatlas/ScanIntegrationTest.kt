package com.skillatlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
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

    // --- похожие артефакты ---

    @Test
    fun `similar skills are grouped in a SIMILAR section and stay valid`() {
        givenRepo(
            SKILL_CODE_REVIEW to skill("code-review", "Review a pull request for bugs and style issues."),
            SKILL_PR_REVIEW to skill("pr-review", "Review the pull request for bugs, style and security issues."),
        )

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_OK, code)
        assertEquals(
            listOf(
                "skill-atlas scan report — $OWNER/$REPO @ $MAIN (sha: 8a1541c)",
                "",
                "SKILLS (2)",
                "  ✔ code-review — valid",
                "    ${permalink(SKILL_CODE_REVIEW)}",
                "    Review a pull request for bugs and style issues.",
                "  ✔ pr-review — valid",
                "    ${permalink(SKILL_PR_REVIEW)}",
                "    Review the pull request for bugs, style and security issues.",
                "",
                "COMMANDS (0)",
                "",
                "SIMILAR (1)",
                "  ≈ score 0.54",
                "    code-review — $SKILL_CODE_REVIEW",
                "    pr-review — $SKILL_PR_REVIEW",
                "",
                "Total: 2 artifacts found, 2 valid, 0 invalid",
            ).joinToString("\n"),
            out.toString().trimEnd('\n'),
        )
    }

    @Test
    fun `similar skill and command are grouped across types in md`() {
        val command = ".claude/commands/draft-notes.md"
        givenRepo(
            SKILL_RELEASE_NOTES to skill("release-notes", "Draft release notes from merged pull requests."),
            command to "---\nname: /draft-notes\ndescription: Draft release notes from the merged pull requests.\n---\n",
        )

        app().run("$OWNER/$REPO", format = "md")

        assertEquals(
            listOf(
                "# skill-atlas report: $OWNER/$REPO @ $MAIN",
                "",
                "## SKILL (1)",
                "",
                "- ✅ [**release-notes**](${permalink(SKILL_RELEASE_NOTES)}) — valid",
                "  Draft release notes from merged pull requests.",
                "",
                "## COMMAND (1)",
                "",
                "- ✅ [**/draft-notes**](${permalink(command)}) — valid",
                "  Draft release notes from the merged pull requests.",
                "",
                "## SIMILAR (1)",
                "",
                "- ≈ score 0.60",
                "  - [**/draft-notes**](${permalink(command)}) — `$command`",
                "  - [**release-notes**](${permalink(SKILL_RELEASE_NOTES)}) — `$SKILL_RELEASE_NOTES`",
                "",
                "**Total:** 2 artifacts, 2 valid, 0 invalid",
            ).joinToString("\n"),
            out.toString().trimEnd('\n'),
        )
    }

    @Test
    fun `artifacts sharing a single name word are not similar`() {
        val checklist = ".claude/skills/release-checklist/SKILL.md"
        givenRepo(
            SKILL_RELEASE_NOTES to skill("release-notes", "Draft release notes from merged pull requests."),
            checklist to skill("release-checklist", "Checklist for cutting a release branch: version bump, changelog, tagging."),
            ".claude/commands/fmt.md" to "Run ktlint over the changed files.\n",
        )

        app().run("$OWNER/$REPO")

        assertEquals(
            listOf(
                "skill-atlas scan report — $OWNER/$REPO @ $MAIN (sha: 8a1541c)",
                "",
                "SKILLS (2)",
                "  ✔ release-notes — valid",
                "    ${permalink(SKILL_RELEASE_NOTES)}",
                "    Draft release notes from merged pull requests.",
                "  ✔ release-checklist — valid",
                "    ${permalink(checklist)}",
                "    Checklist for cutting a release branch: version bump, changelog, tagging.",
                "",
                "COMMANDS (1)",
                "  ✔ /fmt — valid",
                "    ${permalink(".claude/commands/fmt.md")}",
                "",
                "Total: 3 artifacts found, 3 valid, 0 invalid",
            ).joinToString("\n"),
            out.toString().trimEnd('\n'),
        )
    }

    @Test
    fun `similarity is transitive and members are sorted by path`() {
        val kotlinLint = ".claude/skills/kotlin-lint/SKILL.md"
        val kotlinFormat = ".claude/skills/kotlin-format/SKILL.md"
        val javaFormat = ".agents/skills/java-format/SKILL.md"
        givenRepo(
            kotlinLint to skill("kotlin-lint", "Lint Kotlin sources with ktlint."),
            kotlinFormat to skill("kotlin-format", "Format Kotlin sources with ktlint."),
            javaFormat to skill("java-format", "Format Java sources with ktlint."),
        )

        app().run("$OWNER/$REPO")

        assertContains(
            out,
            listOf(
                "",
                "SIMILAR (1)",
                "  ≈ score 0.44",
                "    java-format — $javaFormat",
                "    kotlin-format — $kotlinFormat",
                "    kotlin-lint — $kotlinLint",
                "",
                "Total: 3 artifacts found, 3 valid, 0 invalid",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `invalid artifacts do not take part in similarity`() {
        givenRepo(
            SKILL_CODE_REVIEW to skill("code-review", "First."),
            ".agents/skills/code-review/SKILL.md" to skill("code-review", "Second."),
            ".claude/commands/code-review.md" to "Review the current diff.\n",
            SKILL_PR_REVIEW to "---\nname: pr-review\n---\n",
            ".claude/commands/pr-review.md" to "Review the pull request.\n",
        )

        val code = app().run("$OWNER/$REPO")

        assertEquals(EXIT_OK, code)
        assertTrue("SIMILAR" !in out.toString(), "невалидные артефакты не должны попадать в похожие")
        assertContains(out, "Total: 5 artifacts found, 2 valid, 3 invalid")
    }

    @Test
    fun `json lists similar groups with score and paths`() {
        givenRepo(
            SKILL_PR_REVIEW to skill("pr-review", "Review the pull request for bugs, style and security issues."),
            SKILL_CODE_REVIEW to skill("code-review", "Review a pull request for bugs and style issues."),
        )

        app().run("$OWNER/$REPO", format = "json")

        val body = Json.parseToJsonElement(out.toString()).jsonObject
        val group = body["similar"]!!.jsonArray.single().jsonObject
        assertEquals(0.54, group["score"]!!.jsonPrimitive.double)
        assertEquals(
            listOf(SKILL_CODE_REVIEW, SKILL_PR_REVIEW),
            group["paths"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(body["artifacts"]!!.jsonArray.all { it.jsonObject["valid"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun `json has an empty similar array when nothing is similar`() {
        givenRepo(SKILL_ALPHA to skill("alpha", "Does alpha things."))

        app().run("$OWNER/$REPO", format = "json")

        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(out.toString()).jsonObject["similar"])
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
        const val SKILL_CODE_REVIEW = ".claude/skills/code-review/SKILL.md"
        const val SKILL_PR_REVIEW = ".claude/skills/pr-review/SKILL.md"
        const val SKILL_RELEASE_NOTES = ".claude/skills/release-notes/SKILL.md"
    }
}
