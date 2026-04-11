package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.ExchangeRateService
import com.juujarvis.service.OneDriveExcelService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ForestFinanceTool(
    private val excelService: OneDriveExcelService,
    private val exchangeRateService: ExchangeRateService,
    @Value("\${juujarvis.forest.spreadsheet-name:Forest_Finances.xlsx}")
    private val spreadsheetName: String,
    @Value("\${juujarvis.forest.table-name:Transactions}")
    private val tableName: String
) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "record_forest_transaction"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Record a forest income or expense transaction to the Forest_Finances spreadsheet. " +
                "Use this when you extract transaction data from receipts or invoices related to the " +
                "co-owned forest property. The EUR amount will be automatically converted to USD at " +
                "the historical exchange rate for the transaction date."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "date" to mapOf(
                                    "type" to "string",
                                    "description" to "Transaction date in YYYY-MM-DD format"
                                ),
                                "description" to mapOf(
                                    "type" to "string",
                                    "description" to "Description of the transaction"
                                ),
                                "vendor_or_payer" to mapOf(
                                    "type" to "string",
                                    "description" to "Name of the vendor (expense) or payer (income)"
                                ),
                                "type" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("income", "expense"),
                                    "description" to "Whether this is income or expense"
                                ),
                                "amount_eur" to mapOf(
                                    "type" to "number",
                                    "description" to "Amount in EUR"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("date", "description", "type", "amount_eur")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        val date = arguments["date"] as? String ?: return "Error: date is required"
        val description = arguments["description"] as? String ?: return "Error: description is required"
        val vendorOrPayer = arguments["vendor_or_payer"] as? String ?: ""
        val type = arguments["type"] as? String ?: return "Error: type is required"
        val amountEur = (arguments["amount_eur"] as? Number)?.toDouble() ?: return "Error: amount_eur is required"

        // Get exchange rate
        val rate = exchangeRateService.getEurToUsd(date)
            ?: return "Error: could not fetch EUR/USD exchange rate for $date"
        val amountUsd = Math.round(amountEur * rate * 100) / 100.0

        // Find the spreadsheet
        val fileInfo = excelService.findSharedFile(spreadsheetName)
            ?: return "Error: could not find spreadsheet '$spreadsheetName' in OneDrive. Make sure it's shared with juujarvis@outlook.com."

        // Add the row: Date, Description, Vendor/Payer, Type, EUR, USD, Exchange Rate, Source
        val row = listOf(date, description, vendorOrPayer, type, amountEur, amountUsd, rate, "Juujarvis")
        val success = excelService.addTableRow(fileInfo, tableName, row)

        return if (success) {
            log.info("Recorded forest transaction: {} {} EUR {} ({} USD at {})", type, amountEur, description, amountUsd, rate)
            "Recorded $type: $description — €$amountEur (\$$amountUsd USD at rate $rate on $date)"
        } else {
            "Failed to add row to spreadsheet. Check that the table '$tableName' exists in '$spreadsheetName'."
        }
    }
}
