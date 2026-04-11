package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.EmailAttachmentService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ZoomImageTool(private val emailAttachmentService: EmailAttachmentService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "zoom_image"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Zoom into a specific region of an image attachment to read fine print, numbers, or details. " +
                "Use this when an image preview is too small to read clearly. The image_id is provided when " +
                "an image attachment is included in an email. Specify the crop region in pixels relative to " +
                "the full-size image dimensions (also provided)."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "image_id" to mapOf(
                                    "type" to "string",
                                    "description" to "The image ID from the email attachment"
                                ),
                                "x" to mapOf(
                                    "type" to "integer",
                                    "description" to "Left edge of crop region in pixels (0 = left edge of image)"
                                ),
                                "y" to mapOf(
                                    "type" to "integer",
                                    "description" to "Top edge of crop region in pixels (0 = top of image)"
                                ),
                                "width" to mapOf(
                                    "type" to "integer",
                                    "description" to "Width of crop region in pixels"
                                ),
                                "height" to mapOf(
                                    "type" to "integer",
                                    "description" to "Height of crop region in pixels"
                                ),
                                "output_width" to mapOf(
                                    "type" to "integer",
                                    "description" to "Desired output width in pixels (default 800, max 1600)"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("image_id", "x", "y", "width", "height")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        val imageId = arguments["image_id"] as? String ?: return "Error: image_id is required"
        val x = (arguments["x"] as? Number)?.toInt() ?: return "Error: x is required"
        val y = (arguments["y"] as? Number)?.toInt() ?: return "Error: y is required"
        val width = (arguments["width"] as? Number)?.toInt() ?: return "Error: width is required"
        val height = (arguments["height"] as? Number)?.toInt() ?: return "Error: height is required"
        val outputWidth = (arguments["output_width"] as? Number)?.toInt()?.coerceIn(100, 1600) ?: 800

        log.info("Zoom request: {} crop({}x{} at {},{}) → {}px wide", imageId, width, height, x, y, outputWidth)

        val base64 = emailAttachmentService.cropImage(imageId, x, y, width, height, outputWidth)
            ?: return "Error: image '$imageId' not found or could not be cropped"

        return "[IMAGE_BASE64:image/jpeg:$base64]"
    }
}
