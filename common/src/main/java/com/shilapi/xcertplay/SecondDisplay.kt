package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/**
 * Which ordinary Android display (HDMI, USB-C DP, a head unit's passenger or cluster panel that is
 * not one of the measured BYD ones) shows the dashboard map, following Open Headunit's second screen.
 *
 * The measured BYD cluster displays always win: this is only asked when none of them is there.
 * Display ids are handed out again on every attach, so a saved id going stale is the normal case.
 */
internal object SecondDisplayPolicy {
    /** Stored when the map should take whichever presentation display Android reports first. */
    const val AUTO = -1

    /** `Display.DEFAULT_DISPLAY`, repeated so the rules need no Android import. */
    const val DEFAULT_DISPLAY_ID = 0

    data class DisplayInfo(
        val displayId: Int,
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
        val isPresentation: Boolean,
        val isUsable: Boolean,
    )

    data class Choice(val displayId: Int, val reason: String)

    /** The displays that could carry the map: usable, never the built-in one or CarPlay's own. */
    fun candidates(displays: List<DisplayInfo>, excludedIds: Set<Int>): List<DisplayInfo> =
        displays.filter { it.isUsable && it.displayId != DEFAULT_DISPLAY_ID && it.displayId !in excludedIds }
            .sortedWith(compareByDescending<DisplayInfo> { it.isPresentation }.thenBy { it.displayId })

    /**
     * The saved display while it is attached; otherwise the first presentation display, the way
     * `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)[0]` answers. Null: nowhere to go.
     */
    fun choose(savedDisplayId: Int, displays: List<DisplayInfo>, excludedIds: Set<Int>): Choice? {
        val usable = candidates(displays, excludedIds)
        if (savedDisplayId != AUTO) {
            usable.firstOrNull { it.displayId == savedDisplayId }?.let {
                return Choice(it.displayId, "the chosen display ${it.name} is attached")
            }
        }
        val first = usable.firstOrNull { it.isPresentation } ?: return null
        val why = if (savedDisplayId == AUTO) "it is the first presentation display"
            else "the chosen display $savedDisplayId is gone, and it is the first presentation display"
        return Choice(first.displayId, "${first.name} because $why")
    }

    private const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1080
    private const val MIN_EDGE = 240

    /**
     * The stream size for a panel of [width]x[height]: its shape, at most 1920x1080, so a 4K monitor
     * does not ask the iPhone for a 4K map. Null for a panel too small or stretched to be a map.
     */
    fun streamSize(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
        val w = (width * scale).toInt()
        val h = (height * scale).toInt()
        return if (w < MIN_EDGE || h < MIN_EDGE) null else w to h
    }
}

/** Reads Android's displays for [SecondDisplayPolicy]. */
internal object SecondDisplay {
    fun enabled(context: Context): Boolean = AirPlayPersistence.loadSecondDisplayEnabled(context)

    fun list(context: Context): List<SecondDisplayPolicy.DisplayInfo> =
        runCatching { context.getSystemService(DisplayManager::class.java)?.displays.orEmpty().map(::info) }
            .getOrDefault(emptyList())

    /** The displays offered in the settings, CarPlay's own excluded. */
    fun candidates(context: Context, ownDisplayId: Int? = null): List<SecondDisplayPolicy.DisplayInfo> =
        SecondDisplayPolicy.candidates(list(context), setOfNotNull(ownDisplayId))

    /** The display the map goes on, or null when the option is off or nothing is attached. */
    fun choose(context: Context, ownDisplayId: Int? = null): Display? {
        if (!enabled(context)) return null
        val displays = list(context)
        val choice = SecondDisplayPolicy.choose(AirPlayPersistence.loadSecondDisplayId(context),
            displays, setOfNotNull(ownDisplayId))
        val line = if (choice == null) "no second display among ${displays.joinToString { describe(it) }} (own=$ownDisplayId)"
            else "second display ${choice.displayId}: ${choice.reason}"
        if (line != lastLogged) { lastLogged = line; android.util.Log.i(TAG, line) }
        choice ?: return null
        return context.getSystemService(DisplayManager::class.java)?.getDisplay(choice.displayId)?.takeIf { it.isValid }
    }

    private const val TAG = "DiPlay-SecondDisplay"
    @Volatile private var lastLogged: String? = null

    fun describe(info: SecondDisplayPolicy.DisplayInfo): String =
        "${info.displayId}: ${info.name} ${info.widthPx}x${info.heightPx}"

    fun diagnosticReport(context: Context): String = buildString {
        appendLine("secondDisplayEnabled=${enabled(context)} savedId=${AirPlayPersistence.loadSecondDisplayId(context)}")
        append("secondDisplayCandidates=${candidates(context).joinToString { describe(it) + if (it.isPresentation) " presentation" else "" }}")
    }

    /** The display an activity is on, so the map never covers CarPlay itself. */
    fun displayIdOf(activity: android.app.Activity): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.displayId
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.displayId
    }.getOrNull()

    private fun info(display: Display): SecondDisplayPolicy.DisplayInfo {
        val size = ClusterMapPresentation.sizeOf(display)
        return SecondDisplayPolicy.DisplayInfo(
            displayId = display.displayId,
            name = display.name ?: "display ${display.displayId}",
            widthPx = size.x,
            heightPx = size.y,
            isPresentation = (display.flags and Display.FLAG_PRESENTATION) != 0,
            isUsable = display.isValid,
        )
    }
}
