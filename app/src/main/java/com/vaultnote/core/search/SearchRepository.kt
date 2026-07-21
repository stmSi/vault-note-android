package com.vaultnote.core.search

import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.database.dao.SearchDao
import com.vaultnote.core.database.model.SearchResultRow
import com.vaultnote.core.common.model.VaultItemColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class VaultSearchResult(
    val itemId: String,
    val title: String,
    val color: VaultItemColor,
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
        return searchDao.observeMatches(
            matchExpression = query.matchExpression,
            startMarker = HIGHLIGHT_START,
            endMarker = HIGHLIGHT_END,
            ellipsis = ELLIPSIS,
            titleTokenLimit = TITLE_TOKEN_LIMIT,
            snippetTokenLimit = SNIPPET_TOKEN_LIMIT,
            limit = limit.coerceIn(1, MAX_RESULTS),
        )
            .map { rows -> rows.map { row -> row.toDomain(query.displayTerms) } }
            .flowOn(dispatchers.io)
    }

    private fun SearchResultRow.toDomain(displayTerms: List<String>): VaultSearchResult =
        VaultSearchResult(
            itemId = id,
            title = title,
            color = color,
            highlightedTitle = SearchHighlightNormalizer.markTypedPrefixes(
                highlightedTitle,
                displayTerms,
            ),
            highlightedSnippet = SearchHighlightNormalizer.markTypedPrefixes(
                highlightedSnippet,
                displayTerms,
            ),
            isArchived = isArchived,
            updatedAtEpochMillis = updatedAt,
        )

    companion object {
        const val HIGHLIGHT_START = "\u0001"
        const val HIGHLIGHT_END = "\u0002"
        const val ELLIPSIS = "…"
        const val MAX_RESULTS = 100
        private const val TITLE_TOKEN_LIMIT = 20
        private const val SNIPPET_TOKEN_LIMIT = 24
    }
}
