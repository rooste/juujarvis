package com.juujarvis.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class ExchangeRateService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val restClient = RestClient.create()

    /**
     * Get EUR to USD exchange rate for a specific date.
     * Uses the ECB exchange rate API via frankfurter.app (free, no key needed).
     * Falls back to latest rate if historical date unavailable.
     */
    fun getEurToUsd(date: String): Double? {
        return try {
            val response = restClient.get()
                .uri("https://api.frankfurter.app/$date?from=EUR&to=USD")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)
            json.get("rates")?.get("USD")?.asDouble()
        } catch (e: Exception) {
            log.warn("Failed to get exchange rate for {}, trying latest: {}", date, e.message)
            getLatestEurToUsd()
        }
    }

    fun getLatestEurToUsd(): Double? {
        return try {
            val response = restClient.get()
                .uri("https://api.frankfurter.app/latest?from=EUR&to=USD")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)
            json.get("rates")?.get("USD")?.asDouble()
        } catch (e: Exception) {
            log.error("Failed to get latest EUR/USD rate: {}", e.message)
            null
        }
    }
}
