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

/** Логин GitHub: буквы, цифры и дефисы, не с дефиса, до 39 символов. */
private val GITHUB_LOGIN = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")

/**
 * Разбирает организацию (или пользователя): `org`, `@org`, `https://github.com/org`.
 * Ссылка на репозиторий (`owner/repo`) не принимается: скан организации — это все её репо,
 * молча подменять им скан одного репо нельзя.
 */
fun parseOrg(input: String): String? {
    val withoutHost = input.trim().removeSuffix("/")
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .removePrefix("github.com/").removePrefix("@")
    return withoutHost.takeIf { GITHUB_LOGIN.matches(it) }
}
