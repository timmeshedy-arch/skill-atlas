package com.skillatlas.github

import kotlinx.serialization.Serializable

@Serializable
data class RepoInfo(
    val default_branch: String,
)

@Serializable
data class TreeEntry(
    val path: String,
    val type: String,
)

@Serializable
data class TreeResponse(
    val sha: String,
    val tree: List<TreeEntry>,
    val truncated: Boolean = false,
)

@Serializable
data class RepoOwner(
    val login: String,
)

/** Элемент ответа `/orgs/{org}/repos` (и `/users/{user}/repos`). */
@Serializable
data class OwnerRepo(
    val name: String,
    val owner: RepoOwner,
    val default_branch: String,
    val fork: Boolean = false,
    val archived: Boolean = false,
)

/** Одна страница репозиториев владельца; [hasMore] — у GitHub есть следующая страница. */
data class OwnerRepos(
    val repos: List<OwnerRepo>,
    val hasMore: Boolean,
)
