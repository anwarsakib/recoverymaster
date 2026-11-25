package com.recovery.pvphoto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * One adapter for all three categories:
 * PHOTO / VIDEO -> 3-column grid tiles (item_photo.xml)
 * DOC           -> single-column rows (item_file.xml)
 */
class MediaAdapter(
    private val kind: Kind,
    private val onClick: (RecoverItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<RecoverItem>()

    fun submit(list: List<RecoverItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = if (kind == Kind.DOC) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            MediaVH(inf.inflate(R.layout.item_photo, parent, false))
        } else {
            FileVH(inf.inflate(R.layout.item_file, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is MediaVH -> holder.bind(item, kind)
            is FileVH -> holder.bind(item)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class MediaVH(view: View) : RecyclerView.ViewHolder(view) {
        private val img: ImageView = view.findViewById(R.id.imgItem)
        private val badge: ImageView = view.findViewById(R.id.imgPlayBadge)
        private val meta: TextView = view.findViewById(R.id.txtMeta)

        fun bind(item: RecoverItem, kind: Kind) {
            badge.visibility = if (kind == Kind.VIDEO) View.VISIBLE else View.GONE
            val model: Any? = item.uri ?: item.file
            Glide.with(img).load(model ?: R.drawable.ic_photo).centerCrop().into(img)
            meta.text = "${formatDate(item.dateModified)} · ${formatSize(item.size)}"
        }
    }

    class FileVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.imgFileIcon)
        private val name: TextView = view.findViewById(R.id.txtName)
        private val meta: TextView = view.findViewById(R.id.txtFileMeta)

        fun bind(item: RecoverItem) {
            name.text = item.name
            meta.text = "${formatDate(item.dateModified)} · ${formatSize(item.size)} · ${
                item.name.substringAfterLast('.', "?").uppercase()
            }"
        }
    }
}
