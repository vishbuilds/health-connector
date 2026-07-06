package com.vishaal.healthconnector.logic

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

const val ACTION_OPEN_LOG_PROMPT = "com.vishaal.healthconnector.action.OPEN_LOG_PROMPT"
const val EXTRA_LOG_PROMPT = "com.vishaal.healthconnector.extra.LOG_PROMPT"

private const val CLAUDE_HOME_URL = "https://claude.ai"

fun copyLogPrompt(context: Context, prompt: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Claude log prompt", prompt))
}

fun openClaude(context: Context, prompt: String) {
    val deepLink = "claude://claude.ai/new?q=" + Uri.encode(prompt)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLAUDE_HOME_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Clipboard fallback already makes the action useful.
        }
    }
}

fun handleLogPromptIntent(context: Context, intent: Intent?): Boolean {
    if (intent?.action != ACTION_OPEN_LOG_PROMPT) return false
    val prompt = intent.getStringExtra(EXTRA_LOG_PROMPT)?.takeIf { it.isNotBlank() } ?: return false
    copyLogPrompt(context, prompt)
    openClaude(context, prompt)
    intent.removeExtra(EXTRA_LOG_PROMPT)
    return true
}
