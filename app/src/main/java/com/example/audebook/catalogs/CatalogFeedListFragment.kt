/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.catalogs

import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.audebook.R
import com.example.audebook.data.model.Catalog
import com.example.audebook.databinding.FragmentCatalogFeedListBinding
import com.example.audebook.utils.viewLifecycle

class CatalogFeedListFragment : Fragment() {

    private val catalogFeedListViewModel: CatalogFeedListViewModel by viewModels()
    private lateinit var catalogsAdapter: CatalogFeedListAdapter
    private var binding: FragmentCatalogFeedListBinding by viewLifecycle()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        catalogFeedListViewModel.eventChannel.receive(this) { handleEvent(it) }
        binding = FragmentCatalogFeedListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        catalogsAdapter = CatalogFeedListAdapter(onLongClick = { catalog -> onLongClick(catalog) })

        binding.catalogFeedList.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireContext())
            adapter = catalogsAdapter
            addItemDecoration(
                VerticalSpaceItemDecoration(
                    10
                )
            )
        }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                catalogFeedListViewModel.catalogs.collectLatest {
                    catalogsAdapter.submitList(it)
                }
            }
        }

        binding.catalogFeedAddCatalogFab.setOnClickListener {
            val alertDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.add_catalog))
                .setView(R.layout.add_catalog_dialog)
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.cancel()
                }
                .setPositiveButton(getString(R.string.save), null)
                .show()
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = alertDialog.findViewById<EditText>(R.id.catalogTitle)
                val url = alertDialog.findViewById<EditText>(R.id.catalogUrl)
                if (TextUtils.isEmpty(title?.text)) {
                    title?.error = getString(R.string.invalid_title)
                } else if (TextUtils.isEmpty(url?.text)) {
                    url?.error = getString(R.string.invalid_url)
                } else if (!URLUtil.isValidUrl(url?.text.toString())) {
                    url?.error = getString(R.string.invalid_url)
                } else {
                    catalogFeedListViewModel.parseCatalog(
                        url?.text.toString(),
                        title?.text.toString()
                    )
                    alertDialog.dismiss()
                }
            }
        }
    }

    private fun handleEvent(event: CatalogFeedListViewModel.Event) {
        val message =
            when (event) {
                is CatalogFeedListViewModel.Event.FeedListEvent.CatalogParseFailed -> getString(
                    R.string.catalog_parse_error
                )
            }
        Snackbar.make(
            requireView(),
            message,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun deleteCatalogModel(catalogModelId: Long) {
        catalogFeedListViewModel.deleteCatalog(catalogModelId)
    }

    private fun onLongClick(catalog: Catalog) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_delete_catalog_title))
            .setMessage(getString(R.string.confirm_delete_catalog_text))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .setPositiveButton(getString(R.string.delete)) { dialog, _ ->
                catalog.id?.let { deleteCatalogModel(it) }
                dialog.dismiss()
            }
            .show()
    }

    class VerticalSpaceItemDecoration(private val verticalSpaceHeight: Int) :
        RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.bottom = verticalSpaceHeight
        }
    }
}
