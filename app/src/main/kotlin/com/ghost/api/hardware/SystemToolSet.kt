package com.ghost.api.hardware

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.KeyEvent
import timber.log.Timber
import java.util.Locale
import java.util.TimeZone
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class SystemToolSet(private val context: Context) : ToolSet {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packageManager: PackageManager = context.packageManager
    private var appListCache: List<AppInfo>? = null

    data class AppInfo(val label: String, val packageName: String)

    @Tool(description = "Opens an app by its name")
    fun app(
        @ToolParam(description = "The name of the app to launch") name: String
    ): Map<String, String> {
        val apps = getInstalledApps()
        val bestMatch = apps.find { it.label.contains(name, ignoreCase = true) }

        return if (bestMatch != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(bestMatch.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    mapOf("result" to "success", "message" to "Launched ${bestMatch.label}")
                } else {
                    mapOf("result" to "error", "message" to "Could not launch ${bestMatch.label}")
                }
            } catch (e: Exception) {
                mapOf("result" to "error", "message" to "Failed to launch: ${e.message}")
            }
        } else {
            mapOf("result" to "error", "message" to "App not found matching '$name'")
        }
    }

    @Tool(description = "Controls media playback")
    fun media(
        @ToolParam(description = "PLAY, PAUSE, NEXT, PREV") action: String
    ): Map<String, String> {
        val keyEvent = when (action.uppercase(Locale.ROOT)) {
            "PLAY", "PAUSE", "TOGGLE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS", "PREV" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return mapOf("result" to "error", "message" to "Unknown command")
        }
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEvent))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEvent))
        return mapOf("result" to "success", "message" to "Media Action Sent: $action")
    }

    private fun getInstalledApps(): List<AppInfo> {
        if (appListCache == null) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            appListCache = packageManager.queryIntentActivities(intent, 0).map {
                AppInfo(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
            }
        }
        return appListCache ?: emptyList()
    }

    @Tool(description = "Sets an alarm for a specific time via the system Clock app")
    fun alarm(
        @ToolParam(description = "Strictly 24-hour format hour (e.g. 14 for 2 PM)") hour: Int, 
        @ToolParam(description = "Minutes") minutes: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        return try {
            // v4.1.7: Reverted to AlarmClock intent (system handles alarm lifecycle)
            // Old GhostAlarmReceiver approach was unreliable — model would confirm alarm but nothing fired
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val timeStr = "${hour.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
            mapOf("result" to "success", "message" to "Alarm set for $timeStr${if (label.isNotBlank()) " ($label)" else ""}")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to set alarm: ${e.message}")
        }
    }

    @Tool(description = "Sets a timer for the specified duration")
    fun timer(
        @ToolParam(description = "Total duration in precise seconds (e.g. 5 minutes = 300)") seconds: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Timer scheduled for $seconds seconds in system Clock app")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to set timer: ${e.message}")
        }
    }

    @Tool(description = "Creates a calendar event")
    fun calendar(
        @ToolParam(description = "Event title") title: String, 
        @ToolParam(description = "Event description") description: String = "", 
        @ToolParam(description = "Duration in minutes") minutes: Int = 30
    ): Map<String, String> {
        return try {
            var calId: Long = 1
            val projection = arrayOf(android.provider.CalendarContract.Calendars._ID)
            val selection = "${android.provider.CalendarContract.Calendars.IS_PRIMARY} = 1"
            context.contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                projection, selection, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    calId = cursor.getLong(0)
                } else {
                    // Fallback to first available calendar if no primary is found
                    context.contentResolver.query(
                        android.provider.CalendarContract.Calendars.CONTENT_URI,
                        projection, null, null, null
                    )?.use { fallbackCursor ->
                        if (fallbackCursor.moveToFirst()) calId = fallbackCursor.getLong(0)
                    }
                }
            }

            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.DTSTART, System.currentTimeMillis())
                put(android.provider.CalendarContract.Events.DTEND, System.currentTimeMillis() + minutes * 60 * 1000)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            mapOf("result" to "success", "message" to "Calendar event created silently on calendar $calId")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to create event: ${e.message}")
        }
    }

    @Tool(description = "Reads upcoming calendar events")
    fun read_calendar(
        @ToolParam(description = "Days ahead to read") days: Int = 7
    ): Map<String, String> {
        return try {
            val now = System.currentTimeMillis()
            val later = now + days * 24 * 60 * 60 * 1000L
            val projection = arrayOf(
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART
            )
            val selection = "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(now.toString(), later.toString())
            
            val cursor = context.contentResolver.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs,
                "${android.provider.CalendarContract.Events.DTSTART} ASC"
            )
            
            val events = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0)
                    val date = java.util.Date(it.getLong(1)).toString()
                    events.add("- $title at $date")
                }
            }
            mapOf("result" to "success", "events" to if (events.isEmpty()) "No upcoming events" else events.joinToString("\n"))
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to read calendar: ${e.message}")
        }
    }

    // Automation tools moved to UiMacroToolSet

    @Tool(description = "Saves a semantic fact memory")
    fun remember(
        @ToolParam(description = "Memory title/subject") title: String, 
        @ToolParam(description = "Memory content/fact") content: String
    ): Map<String, String> {
        kotlinx.coroutines.runBlocking {
            com.ghost.api.database.MemoryManager(context).storeSemanticFact(title, content)
        }
        return mapOf("result" to "success", "message" to "Factored into Semantic Memory: $title")
    }

    @Tool(description = "Recalls memory from both Semantic DB and Calendar")
    fun recall(
        @ToolParam(description = "Search query keyword") query: String
    ): Map<String, String> {
        // 1. Query Episodic Memory (Calendar)
        val episodicMemories = DiaryManager(context).searchMemories(query)
        
        // 2. Query Semantic Memory (FTS4 DB)
        val semanticMemories = kotlinx.coroutines.runBlocking {
            com.ghost.api.database.MemoryManager(context).searchSemanticFacts(query)
        }
        
        val merged = buildString {
            if (episodicMemories.isNotEmpty()) {
                append("[EPISODIC / CALENDAR]\n")
                episodicMemories.forEach { append("- $it\n") }
                append("\n")
            }
            if (semanticMemories.isNotEmpty()) {
                append("[SEMANTIC / FACTS]\n")
                semanticMemories.forEach { append("- ${it.subject}: ${it.object_}\n") }
            }
        }.trim()
        
        if (merged.isEmpty()) {
            return mapOf("result" to "success", "memories" to "No memories found for '$query'.")
        }
        return mapOf("result" to "success", "memories" to merged)
    }

}
