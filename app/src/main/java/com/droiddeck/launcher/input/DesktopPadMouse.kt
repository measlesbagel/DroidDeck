package com.droiddeck.launcher.input

import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.hypot

class DesktopPadMouse(private val listener: Listener) {
    interface Listener {
        fun active(): Boolean
        fun onMoveStart()
        fun onMove(dx: Float, dy: Float)
        fun onScrollStart()
        fun onScroll(dx: Float, dy: Float)
        fun onButton(button: Int, pressed: Boolean)
        fun onKey(evdev: Int, pressed: Boolean)
        fun onKeyboard()
        fun onToggle(on: Boolean)
        fun cursorSpeed(): Float
        fun scrollSpeed(): Float
    }

    private class Sticks {
        var leftX = 0f
        var leftY = 0f
        var rightX = 0f
        var rightY = 0f
    }

    var enabled = true
        private set

    private val choreographer = Choreographer.getInstance()
    private val physical = HashMap<Int, Sticks>()
    private val triggerAxes = HashMap<Int, Boolean>()
    private val wiredLayout = HashMap<Int, Boolean>()
    private val down = HashMap<Int, MutableSet<Int>>()
    private val pressed = LinkedHashSet<Int>()
    private val chordHeld = HashMap<Int, MutableSet<Int>>()
    private val chordFired = HashSet<Int>()
    private val swallowed = HashSet<Long>()
    private var moving = false
    private var scrolling = false
    private var frameAt = 0L
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (!listener.active()) {
                reset()
                return
            }
            val dt = if (frameAt == 0L) 1f / 60f else ((frameTimeNanos - frameAt) / 1e9f).coerceIn(0f, 0.05f)
            frameAt = frameTimeNanos
            step(dt)
            if (sticksMoving()) choreographer.postFrameCallback(this) else stop()
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || event.keyCode == KeyEvent.KEYCODE_BUTTON_START) return chord(event)
        if (!enabled) return false
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.repeatCount == 0 && !hasTriggerAxes(event)) {
                val action = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2) BTN_RIGHT else BTN_LEFT
                set(source(event.deviceId, SOURCE_TRIGGER), action, event.action == KeyEvent.ACTION_DOWN)
            }
            return true
        }
        val action = ACTIONS[event.keyCode] ?: return false
        if (event.repeatCount > 0) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> set(source(event.deviceId, SOURCE_KEYS), action, true)
            KeyEvent.ACTION_UP -> set(source(event.deviceId, SOURCE_KEYS), action, false)
        }
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!enabled) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true
        val sticks = physical.getOrPut(event.deviceId) { Sticks() }
        val wired = wiredLayout.getOrPut(event.deviceId) { PadBridge.wiredXboxLayout(event) }
        sticks.leftX = event.getAxisValue(MotionEvent.AXIS_X)
        sticks.leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        sticks.rightX = event.getAxisValue(if (wired) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z)
        sticks.rightY = event.getAxisValue(if (wired) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ)
        if (hasTriggerAxes(event)) {
            val triggers = source(event.deviceId, SOURCE_TRIGGER)
            trigger(triggers, BTN_RIGHT, PadBridge.leftTrigger(event, wired))
            trigger(triggers, BTN_LEFT, PadBridge.rightTrigger(event, wired))
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hat = source(event.deviceId, SOURCE_HAT)
        set(hat, KEY_UP, hatY < -0.5f)
        set(hat, KEY_DOWN, hatY > 0.5f)
        set(hat, KEY_LEFT, hatX < -0.5f)
        set(hat, KEY_RIGHT, hatX > 0.5f)
        wake()
        return true
    }

    fun reset() {
        release()
        chordHeld.clear()
        chordFired.clear()
        swallowed.clear()
    }

    private fun release() {
        stop()
        physical.clear()
        triggerAxes.clear()
        wiredLayout.clear()
        down.clear()
        moving = false
        scrolling = false
        pressed.toList().reversed().forEach { send(it, false) }
        pressed.clear()
    }

    internal fun step(dt: Float) {
        var lx = 0f
        var ly = 0f
        var rx = 0f
        var ry = 0f
        for (sticks in physical.values) {
            lx += sticks.leftX
            ly += sticks.leftY
            rx += sticks.rightX
            ry += sticks.rightY
        }
        val moveGain = gain(lx, ly)
        if (moveGain != 0f) {
            if (!moving) {
                moving = true
                listener.onMoveStart()
            }
            val speed = listener.cursorSpeed() * dt * if (down[PRECISION]?.isNotEmpty() == true) PRECISION_SCALE else 1f
            listener.onMove(lx * moveGain * speed, ly * moveGain * speed)
        } else moving = false
        val scrollGain = gain(rx, ry)
        if (scrollGain != 0f) {
            if (!scrolling) {
                scrolling = true
                listener.onScrollStart()
            }
            val speed = listener.scrollSpeed() * dt
            listener.onScroll(rx * scrollGain * speed, ry * scrollGain * speed)
        } else scrolling = false
    }

    private fun chord(event: KeyEvent): Boolean {
        val device = event.deviceId
        val id = device.toLong() shl 32 or event.keyCode.toLong()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return id in swallowed
            val held = chordHeld.getOrPut(device) { HashSet() }
            held.add(event.keyCode)
            if (held.size == 2 && device !in chordFired) {
                chordFired.add(device)
                swallowed.add(id)
                toggle()
                return true
            }
            if (!enabled) return false
            swallowed.add(id)
            return true
        }
        if (event.action != KeyEvent.ACTION_UP) return id in swallowed
        val held = chordHeld[device]
        held?.remove(event.keyCode)
        val wasSwallowed = swallowed.remove(id)
        if (enabled && wasSwallowed && device !in chordFired) {
            tap(if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) KEY_LEFTMETA else KEY_TAB)
        }
        if (held.isNullOrEmpty()) {
            chordHeld.remove(device)
            chordFired.remove(device)
        }
        return wasSwallowed
    }

    private fun toggle() {
        release()
        enabled = !enabled
        listener.onToggle(enabled)
    }

    private fun trigger(source: Int, action: Int, value: Float) {
        val held = down[action]?.contains(source) == true
        if (!held && value >= TRIGGER_PRESS) set(source, action, true)
        else if (held && value <= TRIGGER_RELEASE) set(source, action, false)
    }

    private fun set(source: Int, action: Int, on: Boolean) {
        val sources = down.getOrPut(action) { HashSet() }
        val was = sources.isNotEmpty()
        if (on) sources.add(source) else sources.remove(source)
        val now = sources.isNotEmpty()
        if (was == now) return
        when (action) {
            KEYBOARD -> if (now) listener.onKeyboard()
            PRECISION -> Unit
            else -> {
                if (now) pressed.add(action) else pressed.remove(action)
                send(action, now)
            }
        }
    }

    private fun tap(vararg codes: Int) {
        codes.forEach { send(it, true) }
        codes.reversed().forEach { send(it, false) }
    }

    private fun send(code: Int, on: Boolean) {
        if (code >= BTN_LEFT) listener.onButton(code, on) else listener.onKey(code, on)
    }

    private fun hasTriggerAxes(event: android.view.InputEvent): Boolean = triggerAxes.getOrPut(event.deviceId) {
        val device = event.device ?: return@getOrPut true
        device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || device.getMotionRange(MotionEvent.AXIS_BRAKE) != null ||
            (event is MotionEvent && wiredLayout.getOrPut(event.deviceId) { PadBridge.wiredXboxLayout(event) })
    }

    private fun wake() {
        if (sticksMoving() && !running) {
            running = true
            frameAt = 0L
            choreographer.postFrameCallback(frame)
        }
    }

    private fun sticksMoving(): Boolean {
        if (moving || scrolling) return true
        return physical.values.any { gain(it.leftX, it.leftY) != 0f || gain(it.rightX, it.rightY) != 0f }
    }

    private fun stop() {
        running = false
        frameAt = 0L
        choreographer.removeFrameCallback(frame)
    }

    companion object {
        const val BTN_LEFT = PointerGestures.BTN_LEFT
        const val BTN_RIGHT = PointerGestures.BTN_RIGHT
        const val BTN_MIDDLE = PointerGestures.BTN_MIDDLE
        private const val KEY_ESC = 1
        private const val KEY_TAB = 15
        private const val KEY_ENTER = 28
        private const val KEY_LEFTCTRL = 29
        private const val KEY_SPACE = 57
        private const val KEY_LEFTALT = 56
        private const val KEY_UP = 103
        private const val KEY_LEFT = 105
        private const val KEY_RIGHT = 106
        private const val KEY_DOWN = 108
        private const val KEY_LEFTMETA = 125
        private const val KEYBOARD = -1
        private const val PRECISION = -2
        private const val DEAD_ZONE = 0.15f
        private const val PRECISION_SCALE = 0.3f
        private const val TRIGGER_PRESS = 0.5f
        private const val TRIGGER_RELEASE = 0.3f
        private const val SOURCE_KEYS = 0
        private const val SOURCE_HAT = 1
        private const val SOURCE_TRIGGER = 2

        private val ACTIONS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to KEY_ENTER,
            KeyEvent.KEYCODE_BUTTON_B to KEY_ESC,
            KeyEvent.KEYCODE_BUTTON_X to KEYBOARD,
            KeyEvent.KEYCODE_BUTTON_Y to KEY_SPACE,
            KeyEvent.KEYCODE_BUTTON_L1 to KEY_LEFTCTRL,
            KeyEvent.KEYCODE_BUTTON_R1 to KEY_LEFTALT,
            KeyEvent.KEYCODE_BUTTON_THUMBR to BTN_MIDDLE,
            KeyEvent.KEYCODE_BUTTON_THUMBL to PRECISION,
            KeyEvent.KEYCODE_DPAD_UP to KEY_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to KEY_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to KEY_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to KEY_RIGHT,
        )

        private fun gain(x: Float, y: Float): Float {
            val length = hypot(x, y)
            if (length <= DEAD_ZONE) return 0f
            val scaled = (length.coerceAtMost(1f) - DEAD_ZONE) / (1f - DEAD_ZONE)
            return scaled * scaled / length
        }

        private fun source(deviceId: Int, kind: Int): Int = deviceId * 3 + kind
    }
}
