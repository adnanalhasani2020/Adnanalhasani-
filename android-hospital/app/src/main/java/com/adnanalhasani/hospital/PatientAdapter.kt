package com.adnanalhasani.hospital

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PatientAdapter : RecyclerView.Adapter<PatientAdapter.PatientViewHolder>() {
    private var items: List<PatientEntity> = emptyList()
    fun submitItems(newItems: List<PatientEntity>) { items = newItems; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_patient, parent, false)
        return PatientViewHolder(view)
    }
    override fun onBindViewHolder(holder: PatientViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
    class PatientViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.txtName)
        private val phone: TextView = itemView.findViewById(R.id.txtPhone)
        fun bind(patient: PatientEntity) {
            name.text = patient.fullName
            phone.text = if (patient.phone.isBlank()) "لا يوجد رقم هاتف" else patient.phone
        }
    }
}
