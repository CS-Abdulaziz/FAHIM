package com.example.testandroidenv

object DemoConfig {
    const val MAX_TREE_DEPTH = 40
    const val MAX_TREE_NODES = 500
    const val MAX_TREE_CHARS = 96_000
    const val MAX_NODE_TEXT_CHARS = 1_000
    const val SNAPSHOT_FRESHNESS_MS = 60_000L
    const val MAX_ACTION_HISTORY = 20
    const val PROGRESS_FEEDBACK_DELAY_MS = 7_000L

    const val PLANNER_ENDPOINT =
        "https://luckiness-thyself-subpanel.ngrok-free.dev/api/v1/predict"
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val MAX_PLANNER_RESPONSE_CHARS = 64_000
    const val MAX_ERROR_BODY_CHARS = 4_000
}
