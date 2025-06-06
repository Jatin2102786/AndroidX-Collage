package com.o7solutions.task

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.o7solutions.task.database.ImageEntity
import com.o7solutions.task.utils.Functions

class ImageListAdapter(val list: ArrayList<ImageEntity>,val click: OnItemClick)
    : RecyclerView.Adapter<ImageListAdapter.ViewHolder>(){
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
       val view = LayoutInflater.from(parent.context).inflate(R.layout.saved_image_item,parent,false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
       holder.apply {

           nameTV.text = list[position].name
           timeTV.text = Functions.convertMillisToDateTime(list[position].timeStamp)

           view.setOnClickListener {
               click.onItemClick(position)
           }
//
//           upload.setOnClickListener {
//
//           }
       }
    }

    override fun getItemCount(): Int {

        return list.size
    }


    interface OnItemClick {
        fun onItemClick(position: Int)
        fun upload(position: Int)
    }

    inner class ViewHolder(var view: View): RecyclerView.ViewHolder(view) {

        var nameTV = view.findViewById<TextView>(R.id.imageName)
        var timeTV = view.findViewById<TextView>(R.id.timeStamp)
//        var upload = view.findViewById<ImageButton>(R.id.upload)

    }

}