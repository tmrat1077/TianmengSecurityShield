package com.tianmeng.security.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tianmeng.security.R
import com.tianmeng.security.data.AppInfo
import com.tianmeng.security.data.Prefs

class AppListAdapter(
    private val mode: Mode,
    private val onActionClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.ViewHolder>(DiffCallback()) {

    enum class Mode { VIRUS, FREEZE, FROZEN, WHITELIST }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivAppIcon)
        val name: TextView = view.findViewById(R.id.tvAppName)
        val packageName: TextView = view.findViewById(R.id.tvPackageName)
        val tag: TextView = view.findViewById(R.id.tvAppTag)
        val actionBtn: MaterialButton = view.findViewById(R.id.btnAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.appName
        holder.packageName.text = app.packageName

        when (mode) {
            Mode.VIRUS -> {
                holder.tag.visibility = View.VISIBLE
                holder.tag.text = app.virusReason
                holder.tag.setTextColor(holder.itemView.context.getColor(R.color.danger))
                holder.actionBtn.text = holder.itemView.context.getString(R.string.delete)
                holder.actionBtn.backgroundTintList =
                    holder.itemView.context.getColorStateList(R.color.danger)
            }
            Mode.FREEZE -> {
                if (app.isFrozen) {
                    holder.tag.visibility = View.VISIBLE
                    holder.tag.text = "已冻结(强制冻结)"
                    holder.tag.setTextColor(holder.itemView.context.getColor(R.color.warning))
                    holder.actionBtn.text = holder.itemView.context.getString(R.string.unfreeze)
                    holder.actionBtn.backgroundTintList =
                        holder.itemView.context.getColorStateList(R.color.success)
                } else {
                    holder.tag.visibility = View.GONE
                    holder.actionBtn.text = holder.itemView.context.getString(R.string.freeze)
                    holder.actionBtn.backgroundTintList =
                        holder.itemView.context.getColorStateList(R.color.accent)
                }
            }
            Mode.FROZEN -> {
                holder.tag.visibility = View.VISIBLE
                holder.tag.text = "强制冻结"
                holder.tag.setTextColor(holder.itemView.context.getColor(R.color.primary))
                holder.actionBtn.text = holder.itemView.context.getString(R.string.unfreeze)
                holder.actionBtn.backgroundTintList =
                    holder.itemView.context.getColorStateList(R.color.success)
            }
            Mode.WHITELIST -> {
                val whitelisted = Prefs.getWhitelist().contains(app.packageName)
                if (whitelisted) {
                    holder.tag.visibility = View.VISIBLE
                    holder.tag.text = holder.itemView.context.getString(R.string.whitelist_in)
                    holder.tag.setTextColor(holder.itemView.context.getColor(R.color.success))
                    holder.actionBtn.text =
                        holder.itemView.context.getString(R.string.whitelist_remove)
                    holder.actionBtn.backgroundTintList =
                        holder.itemView.context.getColorStateList(R.color.warning)
                } else {
                    holder.tag.visibility = View.GONE
                    holder.actionBtn.text =
                        holder.itemView.context.getString(R.string.whitelist_add)
                    holder.actionBtn.backgroundTintList =
                        holder.itemView.context.getColorStateList(R.color.accent)
                }
            }
        }

        holder.actionBtn.setOnClickListener { onActionClick(app) }
    }

    class DiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem == newItem
    }
}
