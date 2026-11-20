package com.artemchep.keyguard.feature.agent

import android.view.KeyEvent
import android.view.MotionEvent

/** Reports a touch that the activity dispatches. Call it before passing the event on. */
fun AgentApprovalInputGuard.onTouchEvent(ev: MotionEvent) {
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_POINTER_DOWN,
        -> onPress()

        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL,
        -> onRelease()
    }
}

/** Reports a key that the activity dispatches. Call it before passing the event on. */
fun AgentApprovalInputGuard.onKeyEvent(event: KeyEvent) {
    if (event.action == KeyEvent.ACTION_DOWN) {
        onInput()
    }
}
