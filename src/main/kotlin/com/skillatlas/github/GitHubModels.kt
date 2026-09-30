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
