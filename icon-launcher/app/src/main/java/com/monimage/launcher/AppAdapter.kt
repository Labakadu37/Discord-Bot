package com.monimage.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()

    fun submit(newItems: List<AppEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        if (app.styledIcon != null) holder.icon.setImageBitmap(app.styledIcon)
        else holder.icon.setImageDrawable(app.originalIcon)
        holder.label.text = app.label
        holder.itemView.contentDescription = app.label
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener { onLongClick(app, it); true }
    }
}
