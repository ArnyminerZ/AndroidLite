package com.arnyminerz.filmagentaproto.database.data.woo

import androidx.annotation.StringDef
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.CANCELLED
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.COMPLETED
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.FAILED
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.ON_HOLD
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.PENDING
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.PROCESSING
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.REFUNDED
import com.arnyminerz.filmagentaproto.database.data.woo.Status.Companion.TRASH
import com.arnyminerz.filmagentaproto.database.prototype.JsonSerializable
import com.arnyminerz.filmagentaproto.database.prototype.JsonSerializer
import com.arnyminerz.filmagentaproto.utils.getDateGmt
import com.arnyminerz.filmagentaproto.utils.mapObjects
import com.arnyminerz.filmagentaproto.utils.toJSON
import java.util.Date
import org.json.JSONObject

@StringDef(PENDING, PROCESSING, ON_HOLD, COMPLETED, CANCELLED, REFUNDED, FAILED, TRASH)
annotation class Status {
    companion object {
        const val PENDING = "pending"
        const val PROCESSING = "processing"
        const val ON_HOLD = "on-hold"
        const val COMPLETED = "completed"
        const val CANCELLED = "cancelled"
        const val REFUNDED = "refunded"
        const val FAILED = "failed"
        const val TRASH = "trash"
    }
}

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey override val id: Long,
    val status: String,
    val currency: String,
    val dateCreated: Date,
    val dateModified: Date,
    val total: Double,
    val customerId: Long,
    val items: List<Product>,
) : JsonSerializable, WooClass(id) {
    companion object : JsonSerializer<Order> {
        override fun fromJSON(json: JSONObject): Order = Order(
            json.getLong("id"),
            json.getString("status"),
            json.getString("currency"),
            json.getDateGmt("date_created"),
            json.getDateGmt("date_modified"),
            json.getDouble("total"),
            json.getLong("customer_id"),
            json.getJSONArray("line_items").mapObjects { Product.fromJSON(it) },
        )
    }

    override fun toJSON(): JSONObject = JSONObject().apply {
        put("id", id)
        put("status", status)
        put("currency", currency)
        put("date_created", dateCreated)
        put("date_modified", dateModified)
        put("total", total)
        put("customer_id", customerId)
        put("line_items", items.toJSON())
    }

    data class Product(
        val id: Long,
        val name: String,
        val productId: Long,
        val variationId: Long,
        val quantity: Long,
        val price: Double,
    ) : JsonSerializable {
        companion object : JsonSerializer<Product> {
            override fun fromJSON(json: JSONObject): Product = Product(
                json.getLong("id"),
                json.getString("name"),
                json.getLong("product_id"),
                json.getLong("variation_id"),
                json.getLong("quantity"),
                json.getDouble("price"),
            )
        }

        override fun toJSON(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("product_id", productId)
            put("variation_id", variationId)
            put("quantity", quantity)
            put("price", price)
        }
    }

    data class Metadata(
        val id: Long,
        val key: String,
        val value: String,
        val displayKey: String,
        val displayValue: String,
    ) : JsonSerializable {
        override fun toJSON(): JSONObject = JSONObject().apply {
            put("id", id)
            put("key", key)
            put("value", value)
            put("display_key", displayKey)
            put("display_value", displayValue)
        }
    }
}
