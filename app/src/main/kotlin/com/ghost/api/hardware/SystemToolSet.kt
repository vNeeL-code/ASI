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

    @Tool(description = "Sets an alarm for a specific time")
    fun alarm(
        @ToolParam(description = "Hour in 24h format") hour: Int, 
        @ToolParam(description = "Minutes") minutes: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, com.ghost.api.hardware.GhostAlarmReceiver::class.java).apply {
                putExtra("LABEL", label)
            }
            
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, (hour * 60) + minutes, intent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minutes)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                
                // If the time has already passed today, set it for tomorrow
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
            
            mapOf("result" to "success", "message" to "Internal background alarm scheduled for $hour:$minutes")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to set alarm: ${e.message}")
        }
    }

    @Tool(description = "Sets a timer")
    fun timer(
        @ToolParam(description = "Length in seconds") seconds: Int, 
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
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.DTSTART, System.currentTimeMillis())
                put(android.provider.CalendarContract.Events.DTEND, System.currentTimeMillis() + minutes * 60 * 1000)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                put(android.provider.CalendarContract.Events.CALENDAR_ID, 1) // Default primary calendar
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            mapOf("result" to "success", "message" to "Calendar event created silently")
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
