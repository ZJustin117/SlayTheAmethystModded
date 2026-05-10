package io.stamethyst.input

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputHandlerGamepadClassificationTest {
    @Test
    fun letterKeyWithGamepadSourceStaysKeyboard() {
        assertFalse(
            GameInputHandler.isGamepadKeyEventSource(
                keyCode = KeyEvent.KEYCODE_A,
                eventSource = InputDevice.SOURCE_GAMEPAD,
                deviceSources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD
            )
        )
    }

    @Test
    fun mixedKeyboardGamepadSourceStaysKeyboard() {
        assertFalse(
            GameInputHandler.isGamepadKeyEventSource(
                keyCode = KeyEvent.KEYCODE_DPAD_UP,
                eventSource = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD,
                deviceSources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD
            )
        )
    }

    @Test
    fun mixedKeyboardGamepadButtonUsesGamepadPath() {
        assertTrue(
            GameInputHandler.isGamepadKeyEventSource(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                eventSource = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD,
                deviceSources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD
            )
        )
    }

    @Test
    fun mixedKeyboardBackKeyStaysKeyboard() {
        assertFalse(
            GameInputHandler.isGamepadKeyEventSource(
                keyCode = KeyEvent.KEYCODE_BACK,
                eventSource = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD,
                deviceSources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD
            )
        )
    }

    @Test
    fun pureGamepadButtonUsesGamepadPath() {
        assertTrue(
            GameInputHandler.isGamepadKeyEventSource(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                eventSource = InputDevice.SOURCE_GAMEPAD,
                deviceSources = InputDevice.SOURCE_GAMEPAD
            )
        )
    }

    @Test
    fun pureDpadSourceUsesGamepadPath() {
        assertTrue(
            GameInputHandler.isGamepadKeyEventSource(
                keyCode = KeyEvent.KEYCODE_DPAD_UP,
                eventSource = InputDevice.SOURCE_DPAD,
                deviceSources = InputDevice.SOURCE_DPAD
            )
        )
    }
}
