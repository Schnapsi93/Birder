package com.yourname.birder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BirdAdapter(
    private val songs: List<BirdSong>,
    private val onItemClick: (BirdSong) -> Unit
) : RecyclerView.Adapter<BirdAdapter.BirdViewHolder>() {

    inner class BirdViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSpeciesName: TextView = view.findViewById(R.id.tvSpeciesName)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BirdViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bird, parent, false)
        return BirdViewHolder(view)
    }

    override fun onBindViewHolder(holder: BirdViewHolder, position: Int) {
        val song = songs[position]
        holder.tvSpeciesName.text = song.commonName
        holder.tvFileName.text = "${song.scientificName} • ${song.soundType}"
        holder.itemView.setOnClickListener { onItemClick(song) }
    }

    override fun getItemCount() = songs.size
}