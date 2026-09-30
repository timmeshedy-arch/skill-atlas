package com.skillatlas

/**
 * Разбирает `<repo>` в пару owner/repo. Принимает `owner/repo`, https/http-URL
 * (хвост вроде `/tree/main/sub` игнорируется) и `git@github.com:owner/repo.git`.
 */
fun parseRepo(input: String): Pair<String, String>? {
    val cleaned = input.trim().removeSuffix(".git").removeSuffix("/")
    val withoutScheme = cleaned.removePrefix("https://").removePrefix("http://").removePrefix("git@")
    val withoutHost = withoutScheme.removePrefix("github.com/").removePrefix("github.com:")
    val parts = withoutHost.split("/").filter { it.isNotBlank() }
    if (parts.size < 2) return null
    return parts[0] to parts[1]
}
