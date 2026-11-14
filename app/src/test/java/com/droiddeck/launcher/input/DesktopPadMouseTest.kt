package com.droiddeck.launcher.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DesktopPadMouseTest {
    private val events = mutableListOf<String>()
    private var movedX = 0f
    private var movedY = 0f
    private var scrolledY = 0f
    private lateinit var pad: DesktopPadMouse

    private val listener = object : DesktopPadMouse.Listener {
        override fun active() = true
        override fun onMoveStart() { events += "move start" }
        override fun onMove(dx: Float, dy: Float) {
            movedX += dx
            movedY += dy
        }
        override fun onScrollStart() { events += "scroll start" }
        override fun onScroll(dx: Float, dy: Float) { scrolledY += dy }
        override fun onButton(button: Int, pressed: Boolean) { events += "button $button ${if (pressed) "down" else "up"}" }
        override fun onKey(evdev: Int, pressed: Boolean) { events += "key $evdev ${if (pressed) "down" else "up"}" }
        override fun onKeyboard() { events += "keyboard" }
        override fun onToggle(on: Boolean) { events += "mouse ${if (on) "on" else "off"}" }
        override fun cursorSpeed() = 1000f
        override fun scrollSpeed() = 2000f
    }

    @Before fun setUp() {
        pad = DesktopPadMouse(listener)
    }

    @Test fun leftStickMovesTheCursorByHowFarItIsPushed() {
        motion(leftX = 0.5f)
        pad.step(0.1f)
        assertEquals(16.96f, movedX, 0.1f)
        motion(leftX = 1f, leftY = 1f)
        pad.step(0.1f)
        assertEquals(16.96f + 70.7f, movedX, 0.1f)
        assertEquals(70.7f, movedY, 0.1f)
        assertEquals(listOf("move start"), events)
    }

    @Test fun smallStickDriftDoesNotMoveTheCursor() {
        motion(leftX = 0.1f, rightY = -0.1f)
        pad.step(0.1f)
        assertEquals(0f, movedX, 0f)
        assertEquals(0f, scrolledY, 0f)
        assertTrue(events.isEmpty())
    }

    @Test fun holdingTheLeftStickClickSlowsTheCursor() {
        key(KeyEvent.KEYCODE_BUTTON_THUMBL, true)
        motion(leftX = 1f)
        pad.step(0.1f)
        assertEquals(30f, movedX, 0.1f)
    }

    @Test fun rightStickScrolls() {
        motion(rightY = 1f)
        pad.step(0.1f)
        assertEquals(200f, scrolledY, 0.1f)
        assertEquals(listOf("scroll start"), events)
    }

    @Test fun faceButtonsAndTriggersClick() {
        key(KeyEvent.KEYCODE_BUTTON_A, true)
        key(KeyEvent.KEYCODE_BUTTON_A, false)
        key(KeyEvent.KEYCODE_BUTTON_B, true)
        key(KeyEvent.KEYCODE_BUTTON_B, false)
        key(KeyEvent.KEYCODE_BUTTON_THUMBR, true)
        key(KeyEvent.KEYCODE_BUTTON_THUMBR, false)
        motion(rightTrigger = 1f)
        motion(rightTrigger = 0.4f)
        motion()
        motion(leftTrigger = 1f)
        motion()
        assertEquals(listOf(
            "button 272 down", "button 272 up",
            "button 273 down", "button 273 up",
            "button 274 down", "button 274 up",
            "button 272 down", "button 272 up",
            "button 273 down", "button 273 up",
        ), events)
    }

    @Test fun aAndTheTriggerShareOneHeldLeftButtonForDragging() {
        key(KeyEvent.KEYCODE_BUTTON_A, true)
        motion(rightTrigger = 1f)
        key(KeyEvent.KEYCODE_BUTTON_A, false)
        assertEquals(listOf("button 272 down"), events)
        motion()
        assertEquals(listOf("button 272 down", "button 272 up"), events)
    }

    @Test fun buttonsTypeKeysAndYOpensTheKeyboard() {
        for (code in intArrayOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_UP)) {
            key(code, true)
            key(code, false)
        }
        key(KeyEvent.KEYCODE_BUTTON_Y, true)
        key(KeyEvent.KEYCODE_BUTTON_Y, false)
        motion(hatX = 1f)
        motion()
        assertEquals(listOf(
            "key 14 down", "key 14 up", "key 1 down", "key 1 up", "key 28 down", "key 28 up", "key 103 down", "key 103 up",
            "keyboard", "key 106 down", "key 106 up",
        ), events)
    }

    @Test fun startOpensTheMenuAndSelectSwitchesWindows() {
        key(KeyEvent.KEYCODE_BUTTON_START, true)
        key(KeyEvent.KEYCODE_BUTTON_START, false)
        key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        assertEquals(listOf("key 125 down", "key 125 up", "key 56 down", "key 15 down", "key 15 up", "key 56 up"), events)
    }

    @Test fun selectWithStartHandsTheControllerBackToGamesAndBack() {
        key(KeyEvent.KEYCODE_BUTTON_A, true)
        key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
        key(KeyEvent.KEYCODE_BUTTON_START, true)
        key(KeyEvent.KEYCODE_BUTTON_START, false)
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        assertEquals(listOf("button 272 down", "button 272 up", "mouse off"), events)
        assertFalse(pad.enabled)
        assertFalse(pad.onKey(keyEvent(KeyEvent.KEYCODE_BUTTON_A, true)))
        assertFalse(pad.onKey(keyEvent(KeyEvent.KEYCODE_BUTTON_START, true)))
        assertTrue(pad.onKey(keyEvent(KeyEvent.KEYCODE_BUTTON_SELECT, true)))
        assertTrue(pad.enabled)
        assertFalse(pad.onKey(keyEvent(KeyEvent.KEYCODE_BUTTON_START, false)))
        assertTrue(pad.onKey(keyEvent(KeyEvent.KEYCODE_BUTTON_SELECT, false)))
        assertEquals(listOf("button 272 down", "button 272 up", "mouse off", "mouse on"), events)
    }

    @Test fun resetLetsGoOfHeldButtons() {
        key(KeyEvent.KEYCODE_BUTTON_A, true)
        key(KeyEvent.KEYCODE_DPAD_LEFT, true)
        pad.reset()
        assertEquals(listOf("button 272 down", "key 105 down", "key 105 up", "button 272 up"), events)
    }

    private fun keyEvent(code: Int, down: Boolean, device: Int = 0) =
        KeyEvent(0, 0, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0, device, 0)

    private fun key(code: Int, down: Boolean, device: Int = 0) {
        assertTrue(pad.onKey(keyEvent(code, down, device)))
    }

    private fun motion(leftX: Float = 0f, leftY: Float = 0f, rightX: Float = 0f, rightY: Float = 0f, hatX: Float = 0f, hatY: Float = 0f,
                       leftTrigger: Float = 0f, rightTrigger: Float = 0f, device: Int = 0) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, leftX)
            setAxisValue(MotionEvent.AXIS_Y, leftY)
            setAxisValue(MotionEvent.AXIS_Z, rightX)
            setAxisValue(MotionEvent.AXIS_RZ, rightY)
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, leftTrigger)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, rightTrigger)
        })
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0, 1f, 1f, device, 0,
            android.view.InputDevice.SOURCE_JOYSTICK, 0)
        pad.onMotion(event)
        event.recycle()
    }
}
