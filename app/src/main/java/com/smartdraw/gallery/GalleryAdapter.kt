package com.smartdraw.gallery

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.smartdraw.R
import com.smartdraw.project.PaintingProject
import com.smartdraw.project.ProjectManager
import java.io.File

class GalleryAdapter(
    private val projectManager: ProjectManager,
    private val onProjectClicked: (PaintingProject) -> Unit,
    private val onDeleteClicked: (PaintingProject) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ProjectViewHolder>() {

    private val projects = mutableListOf<PaintingProject>()
    private var activeProjectId: String? = null

    fun setProjects(newProjects: List<PaintingProject>, activeId: String?) {
        projects.clear()
        projects.addAll(newProjects)
        activeProjectId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_project, parent, false)
        return ProjectViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    inner class ProjectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardProject: MaterialCardView = itemView.findViewById(R.id.cardProject)
        private val imageThumbnail: ImageView = itemView.findViewById(R.id.imageThumbnail)
        private val textActiveBadge: TextView = itemView.findViewById(R.id.textActiveBadge)
        private val btnDeleteProject: ImageButton = itemView.findViewById(R.id.btnDeleteProject)
        private val textProjectTitle: TextView = itemView.findViewById(R.id.textProjectTitle)
        private val textProjectSubtitle: TextView = itemView.findViewById(R.id.textProjectSubtitle)

        fun bind(project: PaintingProject) {
            val isActive = project.id == activeProjectId

            textProjectTitle.text = project.title

            val timeAgo = DateUtils.getRelativeTimeSpanString(
                project.updatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            textProjectSubtitle.text = "${project.canvasWidth} × ${project.canvasHeight} • $timeAgo"

            // Active visual indication
            if (isActive) {
                textActiveBadge.visibility = View.VISIBLE
                cardProject.strokeWidth = 4 // 4px highlight border
            } else {
                textActiveBadge.visibility = View.GONE
                cardProject.strokeWidth = 0
            }

            // Load thumbnail
            val projectDir = projectManager.getProjectDir(project.id)
            val thumbFile = File(projectDir, "thumbnail.png")
            if (thumbFile.exists()) {
                val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                imageThumbnail.setImageBitmap(bmp)
            } else {
                val paintingFile = File(projectDir, "painting.png")
                if (paintingFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(paintingFile.absolutePath)
                    imageThumbnail.setImageBitmap(bmp)
                } else {
                    imageThumbnail.setImageDrawable(null)
                }
            }

            cardProject.setOnClickListener {
                onProjectClicked(project)
            }

            btnDeleteProject.setOnClickListener {
                onDeleteClicked(project)
            }
        }
    }
}
