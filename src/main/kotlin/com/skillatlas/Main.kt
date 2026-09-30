package com.skillatlas

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.skillatlas.github.DEFAULT_API_BASE
import com.skillatlas.github.DEFAULT_RAW_BASE
import com.skillatlas.github.GitHubClient
import com.skillatlas.github.GitHubException
import com.skillatlas.report.ReportFormat
import com.skillatlas.report.ReportFormatter
import com.skillatlas.scan.Scanner
import java.io.File
import kotlin.system.exitProcess

const val EXIT_OK = 0
const val EXIT_NOT_FOUND = 1
const val EXIT_RATE_LIMITED = 2
const val EXIT_ARG_ERROR = 3
const val EXIT_NETWORK_ERROR = 4

/**
 * Ядро команды `scan` — всё, кроме разбора аргументов и завершения процесса.
 *
 * Возвращает exit code вместо вызова `exitProcess`, а базовые URL и потоки вывода
 * принимает параметрами. Это делает сценарии CLI проверяемыми в одном JVM:
 * тест подставляет адрес локального стаба GitHub и читает вывод из буфера.
 */
class ScanApp(
    private val apiBase: String = DEFAULT_API_BASE,
    private val rawBase: String = DEFAULT_RAW_BASE,
    private val out: Appendable = System.out,
    private val err: Appendable = System.err,
) {
    fun run(
        repoArg: String,
        ref: String = "",
        token: String? = null,
        format: String = "table",
        output: String? = null,
        verbose: Boolean = false,
    ): Int {
        val parsed = parseRepo(repoArg)
        if (parsed == null) {
            err.appendLine("skill-atlas: could not parse a GitHub owner/repo from '$repoArg'")
            return EXIT_ARG_ERROR
        }
        val (owner, repo) = parsed

        val reportFormat = when (format.lowercase()) {
            "table" -> ReportFormat.TABLE
            "json" -> ReportFormat.JSON
            "md" -> ReportFormat.MD
            else -> {
                err.appendLine("skill-atlas: unknown --format '$format', expected table, json or md")
                return EXIT_ARG_ERROR
            }
        }

        val client = GitHubClient(
            token = token,
            verbose = verbose,
            apiBase = apiBase,
            rawBase = rawBase,
        )

        val result = try {
            Scanner(client).scan(owner, repo, ref)
        } catch (e: GitHubException.NotFound) {
            err.appendLine("skill-atlas: repository or ref not found: ${e.message}")
            return EXIT_NOT_FOUND
        } catch (e: GitHubException.RateLimited) {
            err.appendLine("skill-atlas: ${e.message}")
            return EXIT_RATE_LIMITED
        } catch (e: GitHubException.Network) {
            err.appendLine("skill-atlas: network error: ${e.message}")
            return EXIT_NETWORK_ERROR
        }

        val report = ReportFormatter.format(result, reportFormat)
        if (output != null) {
            File(output).writeText(report + "\n")
        } else {
            out.appendLine(report)
        }
        return EXIT_OK
    }

    private fun parseRepo(input: String): Pair<String, String>? {
        val cleaned = input.trim().removeSuffix(".git").removeSuffix("/")
        val withoutScheme = cleaned.removePrefix("https://").removePrefix("http://").removePrefix("git@")
        val withoutHost = withoutScheme.removePrefix("github.com/").removePrefix("github.com:")
        val parts = withoutHost.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }
}

class SkillAtlas : CliktCommand(name = "skill-atlas") {
    override fun run() = Unit
}

class ScanCommand : CliktCommand(
    name = "scan",
    help = "Scan a GitHub repository for Claude Code skills and commands",
) {

    private val repoArg by argument(
        name = "repo",
        help = "GitHub repo URL (https://github.com/owner/repo) or short form owner/repo",
    )

    private val ref by option("--ref", help = "Branch, tag or commit sha to scan (defaults to the repo's default branch)")
        .default("")

    private val token by option("--token", help = "GitHub token (defaults to the GITHUB_TOKEN env var)")
        .default("")

    private val format by option("--format", help = "Output format: table, json or md")
        .default("table")

    private val output by option("-o", "--output", help = "Write report to file instead of stdout")

    private val verbose by option("-v", "--verbose", help = "Log every GitHub API request").flag(default = false)

    override fun run() {
        val effectiveToken = token.ifBlank { System.getenv("GITHUB_TOKEN") }
        val code = ScanApp().run(
            repoArg = repoArg,
            ref = ref,
            token = effectiveToken,
            format = format,
            output = output,
            verbose = verbose,
        )
        exitProcess(code)
    }
}

fun main(args: Array<String>) {
    SkillAtlas().subcommands(ScanCommand()).main(args)
}
