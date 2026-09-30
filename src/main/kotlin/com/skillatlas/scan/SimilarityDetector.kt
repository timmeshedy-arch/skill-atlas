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

    private class Tokens<T>(val item: T, val name: Set<String>, val description: Set<String>)

    fun findGroups(artifacts: List<Artifact>): List<SimilarGroup> =
        findGroups(artifacts, key = { it.path }, artifact = { it })
            .map { (members, score) -> SimilarGroup(paths = members.map { it.path }, score = score) }

    /**
     * То же для произвольных элементов — например, артефактов нескольких репозиториев.
     * [key] задаёт стабильный порядок (у одного репо — путь), [artifact] — что сравнивать.
     */
    fun <T> findGroups(items: List<T>, key: (T) -> String, artifact: (T) -> Artifact): List<Pair<List<T>, Double>> {
        val tokens = items
            .filter { artifact(it).let { a -> a.valid && !a.name.isNullOrBlank() } }
            .sortedBy(key)
            .map { Tokens(it, tokenize(artifact(it).name), tokenize(artifact(it).description)) }

        val parent = IntArray(tokens.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }

        val pairScores = mutableListOf<Triple<Int, Int, Double>>()
        for (i in tokens.indices) {
            for (j in i + 1 until tokens.size) {
                val score = score(tokens[i], tokens[j])
                if (score >= SIMILARITY_THRESHOLD) {
                    pairScores += Triple(i, j, score)
                    parent[root(j)] = root(i)
                }
            }
        }

        val bestScore = pairScores
            .groupBy({ root(it.first) }, { it.third })
            .mapValues { (_, scores) -> scores.max() }

        // tokens отсортированы по key, поэтому и группы, и участники в них идут по key.
        return tokens.indices
            .groupBy { root(it) }
            .filterKeys { it in bestScore }
            .map { (root, members) ->
                members.map { tokens[it].item } to (bestScore.getValue(root) * 100).roundToInt() / 100.0
            }
    }

    private fun score(a: Tokens<*>, b: Tokens<*>): Double =
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
