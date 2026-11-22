package com.smartdraw.gallery

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.smartdraw.R
import com.smartdraw.project.PaintingProject
import com.smartdraw.project.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryDialogFragment : DialogFragment() {

    interface Listener {
        fun onProjectSelected(project: PaintingProject)
        fun onNewPaintingRequested()
        fun onProjectDeleted(deletedProject: PaintingProject)
    }

    var listener: Listener? = null
    lateinit var projectManager: ProjectManager

    private lateinit var recyclerGallery: RecyclerView
    private lateinit var textEmptyGallery: TextView
    private lateinit var adapter: GalleryAdapter

    companion object {
        const val TAG = "GalleryDialogFragment"

        fun newInstance(): GalleryDialogFragment {
            return GalleryDialogFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_gallery, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnCloseGallery: ImageButton = view.findViewById(R.id.btnCloseGallery)
        val btnNewPaintingGallery: MaterialButton = view.findViewById(R.id.btnNewPaintingGallery)
        recyclerGallery = view.findViewById(R.id.recyclerGallery)
        textEmptyGallery = view.findViewById(R.id.textEmptyGallery)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val spanCount = if (isLandscape) 3 else 2
        recyclerGallery.layoutManager = GridLayoutManager(requireContext(), spanCount)

        adapter = GalleryAdapter(
            projectManager = projectManager,
            onProjectClicked = { project ->
                listener?.onProjectSelected(project)
                dismiss()
            },
            onDeleteClicked = { project ->
                showDeleteConfirmationDialog(project)
            }
        )
        recyclerGallery.adapter = adapter

        btnCloseGallery.setOnClickListener {
            dismiss()
        }

        btnNewPaintingGallery.setOnClickListener {
            dismiss()
            listener?.onNewPaintingRequested()
        }

        refreshProjects()
    }

    fun refreshProjects() {
        if (!isAdded) return
        lifecycleScope.launch(Dispatchers.IO) {
            val projects = projectManager.getAllProjects()
            val activeId = projectManager.getActiveProjectId()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (projects.isEmpty()) {
                    textEmptyGallery.visibility = View.VISIBLE
                    recyclerGallery.visibility = View.GONE
                } else {
                    textEmptyGallery.visibility = View.GONE
                    recyclerGallery.visibility = View.VISIBLE
                    adapter.setProjects(projects, activeId)
                }
            }
        }
    }

    private fun showDeleteConfirmationDialog(project: PaintingProject) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Painting")
            .setMessage("Are you sure you want to permanently delete \"${project.title}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = projectManager.deleteProject(project.id)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            listener?.onProjectDeleted(project)
                            if (isAdded) {
                                refreshProjects()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
