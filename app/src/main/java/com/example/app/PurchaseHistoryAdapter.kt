package com.dontry.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class PurchaseHistoryAdapter(private val items: List<Purchase>) :
    RecyclerView.Adapter<PurchaseHistoryAdapter.ViewHolder>() {

    companion object {
        private val planDisplay = mapOf(
            "test"           to "Test Plan",
            "Free Credits"   to "Free Credits",
            "1tryon"         to "Starter",
            "5tryon"         to "Basic",
            "10tryon"        to "Popular",
            "50tryon"        to "Value",
            "100tryon"       to "Pro",
            "1000tryon"      to "Ultimate"
        )
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPlanName: TextView = view.findViewById(R.id.tvPlanName)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_history, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        // ✅ Look up friendly name from planDisplay map using planId
        val planName = planDisplay[item.planId] ?: item.planId
        holder.tvPlanName.text = planName
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        holder.tvDetails.text = "+${item.tryonsCredited} tryons • ${sdf.format(Date(item.purchasedAt))}"
        holder.tvAmount.text = "₹${item.amount}"
    }

    override fun getItemCount() = items.size
}