package com.lensalpr.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lensalpr.app.R
import com.lensalpr.app.databinding.ItemVehicleBinding
import com.lensalpr.app.pipeline.VehicleCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Right-hand list of confirmed vehicles: snapshot, plate, make/model and provenance. */
class VehicleAdapter(
    private val onClick: (VehicleCard) -> Unit,
) : ListAdapter<VehicleCard, VehicleAdapter.VehicleViewHolder>(DIFF) {

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VehicleViewHolder {
        val binding = ItemVehicleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VehicleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VehicleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VehicleViewHolder(
        private val binding: ItemVehicleBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: VehicleCard) {
            val context = binding.root.context
            binding.plate.text = card.displayPlate
            binding.makeModel.text = listOfNotNull(card.makeModel, card.year)
                .joinToString(" · ")
                .ifBlank { context.getString(R.string.card_unknown_model) }

            val details = listOfNotNull(
                card.color,
                card.bodyStyle,
                card.country,
                clock.format(Date(card.confirmedAtMs)),
            )
            binding.meta.text = listOfNotNull(card.reasons, details.joinToString(" · "))
                .filter { it.isNotBlank() }
                .joinToString("\n")

            val levelName = card.levelName
            if (levelName == null) {
                binding.levelBadge.visibility = android.view.View.GONE
            } else {
                binding.levelBadge.visibility = android.view.View.VISIBLE
                binding.levelBadge.text = levelName
                binding.levelBadge.setTextColor(LEVEL_COLORS[card.level.coerceIn(0, 4)])
            }

            binding.lensBadge.text = card.lenses.joinToString("/")
            binding.scoreBadge.text = context.getString(
                R.string.vehicle_ocr,
                card.ocrScore.roundToInt(),
            )
            binding.countBadge.text = context.getString(R.string.card_sightings, card.sightings)

            val thumbnail = card.thumbnail
            if (thumbnail != null && !thumbnail.isRecycled) {
                binding.thumb.setImageBitmap(thumbnail)
            } else {
                binding.thumb.setImageDrawable(null)
            }

            binding.root.setOnClickListener { onClick(card) }
        }
    }

    private companion object {
        val LEVEL_COLORS = intArrayOf(
            0xFF8FA0B4.toInt(),
            0xFFF5A524.toInt(),
            0xFFFF8A3D.toInt(),
            0xFFFF5A5F.toInt(),
            0xFFB026FF.toInt(),
        )

        val DIFF = object : DiffUtil.ItemCallback<VehicleCard>() {
            override fun areItemsTheSame(oldItem: VehicleCard, newItem: VehicleCard): Boolean =
                oldItem.plate == newItem.plate

            // The bitmap is compared by identity on purpose: a new frame is a new object, and a
            // pixel comparison of two thumbnails per diff is exactly the cost this callback exists
            // to avoid.
            @Suppress("DiffUtilEquals")
            override fun areContentsTheSame(oldItem: VehicleCard, newItem: VehicleCard): Boolean =
                oldItem.sightings == newItem.sightings &&
                    oldItem.ocrScore == newItem.ocrScore &&
                    oldItem.displayPlate == newItem.displayPlate &&
                    oldItem.makeModel == newItem.makeModel &&
                    oldItem.lastSeenMs == newItem.lastSeenMs &&
                    oldItem.level == newItem.level &&
                    // The badge is drawn from levelName, and it can change while the level does
                    // not: marking a car as police deliberately leaves the threat level alone, so
                    // without this the list keeps showing the old badge and the tap looks ignored.
                    oldItem.levelName == newItem.levelName &&
                    oldItem.reasons == newItem.reasons &&
                    oldItem.thumbnail === newItem.thumbnail
        }
    }
}
