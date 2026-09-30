package com.skillatlas.scan

import kotlin.math.roundToInt

/** Пара с score не ниже порога считается похожей. */
private const val SIMILARITY_THRESHOLD = 0.4

/** Вес имени в score; остальное — описание. */
private const val NAME_WEIGHT = 0.6

private const val MIN_TOKEN_LENGTH = 2

/**
 * Поиск почти-дублей: Jaccard по токенам `name` и `description`, похожие пары
 * транзитивно склеиваются в группы (union-find). Алгоритм описан в README.
 */
object SimilarityDetector {

    private val camelBoundary = Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val stopWords = setOf(
        "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "by", "for", "with",
        "from", "into", "over", "as", "is", "are", "be", "was", "were", "it", "its", "this", "that",
        "these", "those", "you", "your", "use", "used", "using", "when", "how", "what", "which",
        "can", "will", "should", "must", "may", "not", "no", "do", "does", "via", "per", "all",
        "any", "each", "some", "other", "only", "just", "also", "then", "than", "so", "if",
        "skill", "skills", "command", "commands", "claude",
    )

    private class Tokens(val artifact: Artifact, val name: Set<String>, val description: Set<String>)

    fun findGroups(artifacts: List<Artifact>): List<SimilarGroup> {
        val items = artifacts
            .filter { it.valid && !it.name.isNullOrBlank() }
            .sortedBy { it.path }
            .map { Tokens(it, tokenize(it.name), tokenize(it.description)) }

        val parent = IntArray(items.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }

        val pairScores = mutableListOf<Triple<Int, Int, Double>>()
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                val score = score(items[i], items[j])
                if (score >= SIMILARITY_THRESHOLD) {
                    pairScores += Triple(i, j, score)
                    parent[root(j)] = root(i)
                }
            }
        }

        val bestScore = pairScores
            .groupBy({ root(it.first) }, { it.third })
            .mapValues { (_, scores) -> scores.max() }

        // items отсортированы по пути, поэтому и группы, и участники в них идут по пути.
        return items.indices
            .groupBy { root(it) }
            .filterKeys { it in bestScore }
            .map { (key, members) ->
                SimilarGroup(
                    paths = members.map { items[it].artifact.path },
                    score = (bestScore.getValue(key) * 100).roundToInt() / 100.0,
                )
            }
    }

    private fun score(a: Tokens, b: Tokens): Double =
        NAME_WEIGHT * jaccard(a.name, b.name) + (1 - NAME_WEIGHT) * jaccard(a.description, b.description)

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val union = (a union b).size
        return if (union == 0) 0.0 else (a intersect b).size.toDouble() / union
    }

    private fun tokenize(text: String?): Set<String> {
        if (text == null) return emptySet()
        return text.replace(camelBoundary, " ")
            .lowercase()
            .split(separators)
            .filter { it.length >= MIN_TOKEN_LENGTH && it !in stopWords }
            .toSet()
    }
}
