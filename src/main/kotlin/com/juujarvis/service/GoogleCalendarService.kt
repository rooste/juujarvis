package com.juujarvis.service

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileReader
import java.time.*
import java.time.format.DateTimeFormatter

@Service
@ConditionalOnProperty("juujarvis.google.credentials-path", matchIfMissing = false)
class GoogleCalendarService(
    @Value("\${juujarvis.google.credentials-path}")
    private val credentialsPath: String,
    @Value("\${juujarvis.google.tokens-path}")
    private val tokensPath: String,
    @Value("\${juujarvis.google.calendar-id:primary}")
    private val calendarId: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = GoogleNetHttpTransport.newTrustedTransport()

    private val calendarService: Calendar by lazy {
        val credential = authorize()
        Calendar.Builder(httpTransport, jsonFactory, credential)
            .setApplicationName("Juujarvis")
            .build()
    }

    private fun authorize(): Credential {
        val credFile = File(credentialsPath)
        if (!credFile.exists()) {
            throw IllegalStateException("Google credentials file not found at $credentialsPath")
        }

        val secrets = GoogleClientSecrets.load(jsonFactory, FileReader(credFile))
        val flow = GoogleAuthorizationCodeFlow.Builder(
            httpTransport, jsonFactory, secrets,
            listOf(CalendarScopes.CALENDAR)
        )
            .setDataStoreFactory(FileDataStoreFactory(File(tokensPath)))
            .setAccessType("offline")
            .build()

        val storedCredential = flow.loadCredential("user")
        if (storedCredential != null && (storedCredential.refreshToken != null || storedCredential.expiresInSeconds > 60)) {
            log.info("Using stored Google Calendar credentials")
            return storedCredential
        }

        log.info("No stored credentials — starting OAuth flow. A browser window will open.")
        val receiver = LocalServerReceiver.Builder().setPort(8888).build()
        val credential = com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
        log.info("Google Calendar authorized successfully")
        return credential
    }

    fun listEvents(from: Instant, to: Instant, maxResults: Int = 50): List<CalendarEvent> {
        log.info("Fetching calendar events from {} to {}", from, to)
        val events = calendarService.events().list(calendarId)
            .setTimeMin(DateTime(from.toEpochMilli()))
            .setTimeMax(DateTime(to.toEpochMilli()))
            .setMaxResults(maxResults)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()

        return events.items?.map { it.toCalendarEvent() } ?: emptyList()
    }

    fun getUpcomingEvents(days: Int): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val end = now.plus(Duration.ofDays(days.toLong()))
        return listEvents(now, end)
    }

    fun searchEvents(query: String, fromNow: Boolean = true, months: Int = 6): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val from = if (fromNow) Instant.now() else LocalDate.now(zone).minusMonths(1).atStartOfDay(zone).toInstant()
        val to = LocalDate.now(zone).plusMonths(months.toLong()).atStartOfDay(zone).toInstant()

        log.info("Searching calendar for '{}' from {} to {}", query, from, to)
        val events = calendarService.events().list(calendarId)
            .setTimeMin(DateTime(from.toEpochMilli()))
            .setTimeMax(DateTime(to.toEpochMilli()))
            .setQ(query)
            .setMaxResults(50)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()

        return events.items?.map { it.toCalendarEvent() } ?: emptyList()
    }

    fun createEvent(summary: String, startTime: ZonedDateTime, endTime: ZonedDateTime, description: String? = null, location: String? = null): CalendarEvent {
        val event = Event().apply {
            setSummary(summary)
            setStart(EventDateTime().setDateTime(DateTime(startTime.toInstant().toEpochMilli())).setTimeZone(startTime.zone.id))
            setEnd(EventDateTime().setDateTime(DateTime(endTime.toInstant().toEpochMilli())).setTimeZone(endTime.zone.id))
            description?.let { setDescription(it) }
            location?.let { setLocation(it) }
        }

        val created = calendarService.events().insert(calendarId, event).execute()
        log.info("Created calendar event: {} ({})", created.summary, created.id)
        return created.toCalendarEvent()
    }

    fun updateEvent(eventId: String, summary: String? = null, startTime: ZonedDateTime? = null, endTime: ZonedDateTime? = null, description: String? = null, location: String? = null): CalendarEvent {
        val existing = calendarService.events().get(calendarId, eventId).execute()
        summary?.let { existing.summary = it }
        startTime?.let { existing.start = EventDateTime().setDateTime(DateTime(it.toInstant().toEpochMilli())).setTimeZone(it.zone.id) }
        endTime?.let { existing.end = EventDateTime().setDateTime(DateTime(it.toInstant().toEpochMilli())).setTimeZone(it.zone.id) }
        description?.let { existing.description = it }
        location?.let { existing.location = it }

        val updated = calendarService.events().update(calendarId, eventId, existing).execute()
        log.info("Updated calendar event: {} ({})", updated.summary, updated.id)
        return updated.toCalendarEvent()
    }

    fun deleteEvent(eventId: String) {
        calendarService.events().delete(calendarId, eventId).execute()
        log.info("Deleted calendar event: {}", eventId)
    }

    private fun Event.toCalendarEvent(): CalendarEvent {
        val startInstant = start?.dateTime?.let { Instant.ofEpochMilli(it.value) }
            ?: start?.date?.let { LocalDate.parse(it.toStringRfc3339()).atStartOfDay(ZoneId.systemDefault()).toInstant() }
            ?: Instant.now()
        val endInstant = end?.dateTime?.let { Instant.ofEpochMilli(it.value) }
            ?: end?.date?.let { LocalDate.parse(it.toStringRfc3339()).atStartOfDay(ZoneId.systemDefault()).toInstant() }

        val isAllDay = start?.date != null

        return CalendarEvent(
            id = id,
            summary = summary ?: "(No title)",
            start = startInstant,
            end = endInstant,
            isAllDay = isAllDay,
            location = location,
            description = description
        )
    }
}

data class CalendarEvent(
    val id: String,
    val summary: String,
    val start: Instant,
    val end: Instant?,
    val isAllDay: Boolean = false,
    val location: String? = null,
    val description: String? = null
) {
    fun formatForDisplay(zone: ZoneId = ZoneId.systemDefault()): String {
        val date = start.atZone(zone)
        val dateStr = date.format(DateTimeFormatter.ofPattern("EEE MMM d"))
        val timeStr = if (isAllDay) "all day" else date.format(DateTimeFormatter.ofPattern("h:mm a"))
        val loc = location?.let { " @ $it" } ?: ""
        return "$summary — $dateStr, $timeStr$loc"
    }
}
