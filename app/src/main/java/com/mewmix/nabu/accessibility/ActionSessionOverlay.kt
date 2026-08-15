package com.mewmix.nabu.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mewmix.nabu.uiagent.ActionRequestDispatcher
import com.mewmix.nabu.uiagent.ActionSession
import com.mewmix.nabu.uiagent.ActionSessionMode
import com.mewmix.nabu.uiagent.ActionSessionStatus
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ActionSessionOverlay(private val service: NabuAccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var overlayView: View? = null
    private var observerJob: Job? = null
    private var currentMode: ActionSessionMode = ActionSessionMode.SINGLE_TURN

    fun show() {
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            return
        }

        val root = FrameLayout(service).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(24, 24, 24, 48)
        }

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val density = service.resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                setColor(0xEE121212.toInt())
                cornerRadius = 16f * density
                setStroke((1.5f * density).toInt(), 0x44FFFFFF.toInt())
            }
            background = bg
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Header
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val title = TextView(service).apply {
            text = "NABU ACTION ASSISTANT"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val modeToggle = Button(service).apply {
            text = if (currentMode == ActionSessionMode.SINGLE_TURN) "Single-Turn" else "Conversation"
            textSize = 11f
            setTextColor(0xFF00E5FF.toInt())
            setBackgroundColor(0x00000000)
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                currentMode = if (currentMode == ActionSessionMode.SINGLE_TURN) {
                    ActionSessionMode.TEMPORARY_CONVERSATION
                } else {
                    ActionSessionMode.SINGLE_TURN
                }
                text = if (currentMode == ActionSessionMode.SINGLE_TURN) "Single-Turn" else "Conversation"
            }
        }

        val closeBtn = Button(service).apply {
            text = "✕"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            setBackgroundColor(0x00000000)
            setPadding(8, 0, 8, 0)
            setOnClickListener { hide() }
        }

        header.addView(title)
        header.addView(modeToggle)
        header.addView(closeBtn)
        card.addView(header)

        // Status / progress container
        val statusContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val statusText = TextView(service).apply {
            text = "Ready"
            setTextColor(0xFF00E5FF.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val progressBar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }

        val actionButtonsRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val handoffBtn = Button(service).apply {
            text = "Open in Nabu ↗"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x33FFFFFF.toInt())
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                ActionRequestDispatcher.activeSession.value?.let { session ->
                    ActionRequestDispatcher.handoffToChat(service, session.id)
                    hide()
                }
            }
        }

        val cancelBtn = Button(service).apply {
            text = "Stop"
            textSize = 11f
            setTextColor(0xFFFF5252.toInt())
            setBackgroundColor(0x00000000)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                ActionRequestDispatcher.activeSession.value?.let { session ->
                    ActionRequestDispatcher.cancelSession(session.id)
                }
            }
        }

        actionButtonsRow.addView(handoffBtn)
        actionButtonsRow.addView(cancelBtn)

        statusContainer.addView(statusText)
        statusContainer.addView(progressBar)
        statusContainer.addView(actionButtonsRow)
        card.addView(statusContainer)

        // Input row
        val inputRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val inputField = EditText(service).apply {
            hint = "Ask Nabu to do something..."
            setHintTextColor(0x88AAAAAA.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            maxLines = 3
            val density = service.resources.displayMetrics.density
            val fieldBg = GradientDrawable().apply {
                setColor(0x44222222.toInt())
                cornerRadius = 8f * density
                setStroke((1f * density).toInt(), 0x33FFFFFF.toInt())
            }
            background = fieldBg
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
        }

        val submitBtn = Button(service).apply {
            text = "Run"
            textSize = 13f
            setTextColor(0xFF000000.toInt())
            val density = service.resources.displayMetrics.density
            val btnBg = GradientDrawable().apply {
                setColor(0xFF00E5FF.toInt())
                cornerRadius = 8f * density
            }
            background = btnBg
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }

        fun doSubmit() {
            val text = inputField.text.toString().trim()
            if (text.isBlank()) return
            inputField.text.clear()
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(inputField.windowToken, 0)

            val currentSession = ActionRequestDispatcher.activeSession.value
            if (currentMode == ActionSessionMode.TEMPORARY_CONVERSATION && currentSession != null && currentSession.status in listOf(ActionSessionStatus.COMPLETED, ActionSessionStatus.IDLE)) {
                ActionRequestDispatcher.submitFollowUp(
                    context = service,
                    sessionId = currentSession.id,
                    followUpRequest = text
                )
            } else {
                ActionRequestDispatcher.submitRequest(
                    context = service,
                    request = text,
                    mode = currentMode
                )
            }
        }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                doSubmit()
                true
            } else false
        }
        submitBtn.setOnClickListener { doSubmit() }

        inputRow.addView(inputField)
        inputRow.addView(submitBtn)
        card.addView(inputRow)
        root.addView(card)

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        windowManager.addView(root, params)
        overlayView = root

        observerJob?.cancel()
        observerJob = scope.launch {
            ActionRequestDispatcher.activeSession.collectLatest { session ->
                if (session == null) {
                    statusContainer.visibility = View.GONE
                    statusText.text = ""
                    return@collectLatest
                }

                statusContainer.visibility = View.VISIBLE
                val phaseText = when (session.status) {
                    ActionSessionStatus.PLANNING -> "Planning: ${session.currentGoal}"
                    ActionSessionStatus.OBSERVING -> "Observing screen..."
                    ActionSessionStatus.EXECUTING -> "Acting: ${session.pendingObjective ?: session.currentGoal}"
                    ActionSessionStatus.VERIFYING -> "Verifying: ${session.pendingObjective ?: ""}"
                    ActionSessionStatus.COMPLETED -> "Done: ${session.lastVerificationResult ?: "Complete"}"
                    ActionSessionStatus.FAILED -> "Failed: ${session.lastVerificationResult ?: "Action could not complete"}"
                    ActionSessionStatus.CANCELLED -> "Stopped"
                    else -> session.status.name
                }
                statusText.text = phaseText
                progressBar.visibility = if (session.status in listOf(ActionSessionStatus.PLANNING, ActionSessionStatus.OBSERVING, ActionSessionStatus.EXECUTING, ActionSessionStatus.VERIFYING)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                if (session.status == ActionSessionStatus.COMPLETED && session.mode == ActionSessionMode.SINGLE_TURN) {
                    // In single-turn mode, hide overlay shortly after verified success
                    kotlinx.coroutines.delay(1800L)
                    if (ActionRequestDispatcher.activeSession.value?.status == ActionSessionStatus.COMPLETED) {
                        hide()
                    }
                }
            }
        }
    }

    fun hide() {
        observerJob?.cancel()
        observerJob = null
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
        }
    }
}
