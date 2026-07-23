package com.vaultnote.core.search

import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.database.dao.SearchDao
import com.vaultnote.core.database.model.SearchResultRow
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class VaultSearchResult(
    val itemId: String,
    val title: String,
    val color: VaultItemColor,
    val type: VaultItemType,
    val primaryAttachmentId: String?,
    val highlightedTitle: String,
    val highlightedSnippet: String,
    val isArchived: Boolean,
    val updatedAtEpochMillis: Long,
)

interface SearchRepository {
    fun observe(query: CompiledSearchQuery, limit: Int): Flow<List<VaultSearchResult>>
}

class RoomSearchRepository(
    private val searchDao: SearchDao,
    private val dispatchers: DispatcherProvider,
) : SearchRepository {
    override fun observe(
        query: CompiledSearchQuery,
        limit: Int,
    ): Flow<List<VaultSearchResult>> {
        if (query.matchExpression.isBlank()) return flowOf(emptyList())
        val boundedLimit = limit.coerceIn(1, MAX_RESULTS)
        val exactMatches = searchDao.observeMatches(
            matchExpression = query.matchExpression,
            startMarker = HIGHLIGHT_START,
            endMarker = HIGHLIGHT_END,
            ellipsis = ELLIPSIS,
            titleTokenLimit = TITLE_TOKEN_LIMIT,
            snippetTokenLimit = SNIPPET_TOKEN_LIMIT,
            limit = boundedLimit,
        )
        val subsequenceMatches = if (
            query.subsequencePattern != null && query.subsequenceAnchor != null
        ) {
            searchDao.observeSubsequenceMatches(
                subsequencePattern = query.subsequencePattern,
                anchor = query.subsequenceAnchor,
                snippetLeadingCharacters = SUBSEQUENCE_SNIPPET_LEADING_CHARACTERS,
                snippetCharacterLimit = SUBSEQUENCE_SNIPPET_CHARACTER_LIMIT,
                limit = boundedLimit,
            )
        } else {
            flowOf(emptyList())
        }
        return combine(exactMatches, subsequenceMatches) { exactRows, subsequenceRows ->
            val exactResults = exactRows.map { row ->
                row.toDomain(query.displayTerms, subsequenceHighlight = false)
            }
            val exactIds = exactResults.asSequence().map(VaultSearchResult::itemId).toHashSet()
            exactResults + subsequenceRows.asSequence()
                .filterNot { row -> row.id in exactIds }
                .map { row -> row.toDomain(query.displayTerms, subsequenceHighlight = true) }
                .take(boundedLimit - exactResults.size)
                .toList()
        }
            .flowOn(dispatchers.io)
    }

    private fun SearchResultRow.toDomain(
        displayTerms: List<String>,
        subsequenceHighlight: Boolean,
    ): VaultSearchResult =
        VaultSearchResult(
            itemId = id,
            title = title,
            color = color,
            type = type,
            primaryAttachmentId = primaryAttachmentId,
            highlightedTitle = highlightedTitle.markSearchTerms(displayTerms, subsequenceHighlight),
            highlightedSnippet = highlightedSnippet.markSearchTerms(displayTerms, subsequenceHighlight),
            isArchived = isArchived,
            updatedAtEpochMillis = updatedAt,
        )

    private fun String.markSearchTerms(
        displayTerms: List<String>,
        subsequenceHighlight: Boolean,
    ): String = if (subsequenceHighlight) {
        SearchHighlightNormalizer.markTypedSubsequences(this, displayTerms)
    } else {
        SearchHighlightNormalizer.markTypedPrefixes(this, displayTerms)
    }

    companion object {
        const val HIGHLIGHT_START = "\u0001"
        const val HIGHLIGHT_END = "\u0002"
        const val ELLIPSIS = "…"
        const val MAX_RESULTS = 100
        private const val TITLE_TOKEN_LIMIT = 20
        private const val SNIPPET_TOKEN_LIMIT = 24
        private const val SUBSEQUENCE_SNIPPET_LEADING_CHARACTERS = 48
        private const val SUBSEQUENCE_SNIPPET_CHARACTER_LIMIT = 240
    }
}
