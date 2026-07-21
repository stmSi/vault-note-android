package com.vaultnote.feature.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.databinding.FragmentSearchBinding
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {
    private var binding: FragmentSearchBinding? = null
    private var keyboardRequested = false
    private val viewModel: SearchViewModel by viewModels {
        SearchViewModel.Factory(requireContext().appContainer().searchRepository)
    }
    private val resultAdapter = SearchResultAdapter(::openResult)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentSearchBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        currentBinding.results.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = resultAdapter
            setHasFixedSize(true)
        }
        currentBinding.searchInput.setText(viewModel.query.value)
        currentBinding.searchInput.setSelection(currentBinding.searchInput.text?.length ?: 0)
        currentBinding.searchInput.doAfterTextChanged { editable ->
            viewModel.setQuery(editable?.toString().orEmpty())
        }
        currentBinding.retryButton.setOnClickListener { viewModel.retry() }
        applyInsets(currentBinding)
        collectState(currentBinding)
        currentBinding.searchInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (keyboardRequested) return
        keyboardRequested = true
        binding?.searchInput?.post {
            val currentBinding = binding ?: return@post
            currentBinding.searchInput.requestFocus()
            WindowCompat.getInsetsController(
                requireActivity().window,
                currentBinding.searchInput,
            ).show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onDestroyView() {
        binding?.results?.adapter = null
        binding = null
        keyboardRequested = false
        super.onDestroyView()
    }

    private fun collectState(currentBinding: FragmentSearchBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(currentBinding, state) }
            }
        }
    }

    private fun render(currentBinding: FragmentSearchBinding, state: SearchUiState) {
        currentBinding.loadingIndicator.isVisible = state is SearchUiState.Loading
        currentBinding.results.isVisible = state is SearchUiState.Content
        currentBinding.messageState.isVisible = state is SearchUiState.Idle ||
            state is SearchUiState.Empty ||
            state is SearchUiState.QueryTooLong ||
            state is SearchUiState.Error
        currentBinding.retryButton.isVisible = state is SearchUiState.Error
        currentBinding.message.text = getString(
            when (state) {
                SearchUiState.Idle -> R.string.search_hint_message
                SearchUiState.Empty -> R.string.search_no_results
                SearchUiState.QueryTooLong -> R.string.search_query_too_long
                SearchUiState.Error -> R.string.search_failed
                SearchUiState.Loading,
                is SearchUiState.Content,
                -> R.string.search_hint_message
            },
        )
        resultAdapter.submitList((state as? SearchUiState.Content)?.results.orEmpty())
    }

    private fun openResult(itemId: String) {
        (activity as? MainNavigator)?.openNoteEditor(itemId)
    }

    private fun applyInsets(currentBinding: FragmentSearchBinding) {
        val contentStart = currentBinding.content.paddingStart
        val contentEnd = currentBinding.content.paddingEnd
        val contentTop = currentBinding.content.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val isRtl = currentBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            currentBinding.content.updatePaddingRelative(
                start = contentStart + if (isRtl) safe.right else safe.left,
                top = contentTop + safe.top,
                end = contentEnd + if (isRtl) safe.left else safe.right,
            )
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    companion object {
        const val BACK_STACK_NAME = "search"
        fun newInstance(): SearchFragment = SearchFragment()
    }
}
