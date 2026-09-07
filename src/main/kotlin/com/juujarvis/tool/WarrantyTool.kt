package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.ConversationStore
import com.juujarvis.service.WarrantyRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.imageio.ImageIO

@Component
class WarrantyTool(
    private val conversationStore: ConversationStore,
    @Value("\${juujarvis.warranty.image-dir:data/warranty-images}")
    private val warrantyImageDir: String
) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "manage_warranty"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Track warranty information for family purchases. Use this when someone sends a receipt or " +
                "asks about warranties. You can save warranty info extracted from receipt images, search for " +
                "products, list all warranties, check if something is still under warranty, or view the original receipt."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("save", "search", "list", "view", "delete"),
                                    "description" to "Action to perform"
                                ),
                                "product_name" to mapOf(
                                    "type" to "string",
                                    "description" to "Product name (required for save)"
                                ),
                                "purchase_date" to mapOf(
                                    "type" to "string",
                                    "description" to "Purchase date in yyyy-MM-dd format"
                                ),
                                "warranty_months" to mapOf(
                                    "type" to "integer",
                                    "description" to "Warranty duration in months — expiry date is auto-calculated from purchase_date"
                                ),
                                "warranty_expiry" to mapOf(
                                    "type" to "string",
                                    "description" to "Explicit warranty expiry date in yyyy-MM-dd format (overrides warranty_months)"
                                ),
                                "store" to mapOf(
                                    "type" to "string",
                                    "description" to "Store or retailer name"
                                ),
                                "price" to mapOf(
                                    "type" to "string",
                                    "description" to "Purchase price including currency (e.g., '$299.99', '150 EUR')"
                                ),
                                "brand" to mapOf(
                                    "type" to "string",
                                    "description" to "Product brand/manufacturer"
                                ),
                                "category" to mapOf(
                                    "type" to "string",
                                    "description" to "Product category (e.g., 'electronics', 'appliance', 'furniture')"
                                ),
                                "notes" to mapOf(
                                    "type" to "string",
                                    "description" to "Additional notes (model number, serial number, etc.)"
                                ),
                                "image_source_path" to mapOf(
                                    "type" to "string",
                                    "description" to "File path of the receipt image to store permanently (from the attached image source path)"
                                ),
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "Search query for the search action"
                                ),
                                "filter" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("all", "active", "expired"),
                                    "description" to "Filter for list action: 'active' = warranty still valid, 'expired' = past expiry"
                                ),
                                "warranty_id" to mapOf(
                                    "type" to "integer",
                                    "description" to "Warranty record ID (for view and delete)"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("action")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        val action = arguments["action"] as? String ?: return "Error: action is required"

        return when (action) {
            "save" -> handleSave(arguments)
            "search" -> handleSearch(arguments)
            "list" -> handleList(arguments)
            "view" -> handleView(arguments)
            "delete" -> handleDelete(arguments)
            else -> "Unknown action: $action. Use save, search, list, view, or delete."
        }
    }

    private fun handleSave(args: Map<String, Any?>): String {
        val productName = args["product_name"] as? String ?: return "Error: product_name is required"
        val purchaseDate = args["purchase_date"] as? String
        val warrantyMonths = (args["warranty_months"] as? Number)?.toInt()
        val warrantyExpiry = args["warranty_expiry"] as? String
            ?: if (purchaseDate != null && warrantyMonths != null) {
                LocalDate.parse(purchaseDate).plusMonths(warrantyMonths.toLong()).toString()
            } else null
        val imageSourcePath = args["image_source_path"] as? String

        val imagePath = imageSourcePath?.let { copyToStorage(it) }

        val record = WarrantyRecord(
            productName = productName,
            purchaseDate = purchaseDate,
            warrantyExpiry = warrantyExpiry,
            store = args["store"] as? String,
            price = args["price"] as? String,
            brand = args["brand"] as? String,
            category = args["category"] as? String,
            notes = args["notes"] as? String,
            imagePath = imagePath,
            createdAt = Instant.now()
        )

        val id = conversationStore.saveWarranty(record)
        log.info("Saved warranty #{}: {} (expires: {})", id, productName, warrantyExpiry ?: "unknown")

        return buildString {
            append("Saved warranty #$id: $productName")
            warrantyExpiry?.let { append(" (expires: $it)") }
            imagePath?.let { append(" [receipt image stored]") }
        }
    }

    private fun handleSearch(args: Map<String, Any?>): String {
        val query = args["query"] as? String ?: return "Error: query is required for search"
        val results = conversationStore.searchWarranties(query)
        if (results.isEmpty()) return "No warranties found matching '$query'"
        return results.joinToString("\n") { formatWarranty(it) }
    }

    private fun handleList(args: Map<String, Any?>): String {
        val filter = args["filter"] as? String ?: "all"
        val all = conversationStore.loadAllWarranties()
        if (all.isEmpty()) return "No warranties stored yet"

        val today = LocalDate.now().toString()
        val filtered = when (filter) {
            "active" -> all.filter { it.warrantyExpiry == null || it.warrantyExpiry >= today }
            "expired" -> all.filter { it.warrantyExpiry != null && it.warrantyExpiry < today }
            else -> all
        }

        if (filtered.isEmpty()) return "No ${filter} warranties found"
        return filtered.joinToString("\n") { formatWarranty(it) }
    }

    private fun handleView(args: Map<String, Any?>): String {
        val id = parseId(args) ?: return "Error: warranty_id is required"
        val record = conversationStore.loadWarrantyById(id) ?: return "Warranty #$id not found"

        val details = formatWarranty(record)
        val imagePath = record.imagePath
        if (imagePath == null) return "$details\n(no receipt image)"

        val file = File(imagePath)
        if (!file.exists()) return "$details\n(receipt image missing: $imagePath)"

        return try {
            val img = ImageIO.read(file) ?: return "$details\n(could not read image)"
            val maxDim = 1200
            val scale = if (maxOf(img.width, img.height) > maxDim) {
                maxDim.toDouble() / maxOf(img.width, img.height)
            } else 1.0
            val w = (img.width * scale).toInt()
            val h = (img.height * scale).toInt()
            val resized = if (scale < 1.0) {
                val buf = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = buf.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(img, 0, 0, w, h, null)
                g.dispose()
                buf
            } else img

            val out = ByteArrayOutputStream()
            ImageIO.write(resized, "jpg", out)
            val base64 = Base64.getEncoder().encodeToString(out.toByteArray())
            "$details\n[IMAGE_BASE64:image/jpeg:$base64]"
        } catch (e: Exception) {
            log.error("Failed to load warranty image: {}", imagePath, e)
            "$details\n(error reading image)"
        }
    }

    private fun handleDelete(args: Map<String, Any?>): String {
        val id = parseId(args) ?: return "Error: warranty_id is required"
        val deleted = conversationStore.deleteWarranty(id)
        return if (deleted) "Warranty #$id deleted" else "Warranty #$id not found"
    }

    private fun formatWarranty(r: WarrantyRecord): String {
        val today = LocalDate.now().toString()
        val status = when {
            r.warrantyExpiry == null -> "no expiry date"
            r.warrantyExpiry >= today -> "ACTIVE (expires ${r.warrantyExpiry})"
            else -> "EXPIRED (${r.warrantyExpiry})"
        }
        return buildString {
            append("#${r.id} ${r.productName}")
            r.brand?.let { append(" ($it)") }
            append(" — $status")
            r.store?.let { append(" | Store: $it") }
            r.price?.let { append(" | Price: $it") }
            r.purchaseDate?.let { append(" | Purchased: $it") }
            r.category?.let { append(" | Category: $it") }
            r.notes?.let { append(" | Notes: $it") }
            if (r.imagePath != null) append(" | [has receipt]")
        }
    }

    private fun copyToStorage(sourcePath: String): String? {
        return try {
            val source = File(sourcePath)
            if (!source.exists()) {
                log.warn("Source image not found: {}", sourcePath)
                return null
            }
            val dir = File(warrantyImageDir)
            dir.mkdirs()
            val dest = File(dir, "${System.currentTimeMillis()}_${source.name}")
            source.copyTo(dest, overwrite = true)
            log.info("Copied warranty image: {} → {}", sourcePath, dest.absolutePath)
            dest.absolutePath
        } catch (e: Exception) {
            log.error("Failed to copy warranty image from {}: {}", sourcePath, e.message)
            null
        }
    }

    private fun parseId(args: Map<String, Any?>): Long? {
        return when (val v = args["warranty_id"]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }
}
