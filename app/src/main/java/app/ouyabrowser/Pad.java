package app.ouyabrowser;

import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * OUYA controller mapping. The OUYA ODK defines its buttons on top of the standard Android gamepad
 * key codes: O = BUTTON_A, U = BUTTON_X, Y = BUTTON_Y, A = BUTTON_B, L1/R1/L2/R2 = BUTTON_L1.., and
 * the stick clicks are BUTTON_THUMBL / BUTTON_THUMBR. Sticks arrive as joystick motion events
 * (left = AXIS_X/Y, right = AXIS_Z/RZ), the D-pad as DPAD key events.
 */
final class Pad {
    /** Same value as OuyaController.STICK_DEADZONE. */
    static final float DEADZONE = 0.25f;

    private Pad() {}

    /** Returns the logical button for a key event, or null if it is an ordinary keyboard key. */
    static Btn fromKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: return Btn.O;
            case KeyEvent.KEYCODE_BUTTON_X: return Btn.U;
            case KeyEvent.KEYCODE_BUTTON_Y: return Btn.Y;
            case KeyEvent.KEYCODE_BUTTON_B: return Btn.A;
            case KeyEvent.KEYCODE_BACK: return Btn.A;
            case KeyEvent.KEYCODE_BUTTON_L1: return Btn.L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return Btn.R1;
            case KeyEvent.KEYCODE_BUTTON_L2: return Btn.L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return Btn.R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return Btn.L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return Btn.R3;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU: return Btn.MENU;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return Btn.U;
            case KeyEvent.KEYCODE_DPAD_UP: return fromPad(e) ? Btn.UP : null;
            case KeyEvent.KEYCODE_DPAD_DOWN: return fromPad(e) ? Btn.DOWN : null;
            case KeyEvent.KEYCODE_DPAD_LEFT: return fromPad(e) ? Btn.LEFT : null;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return fromPad(e) ? Btn.RIGHT : null;
            case KeyEvent.KEYCODE_DPAD_CENTER: return fromPad(e) ? Btn.O : null;
            default: return null;
        }
    }

    /**
     * D-pad keys also come from USB/Bluetooth keyboards (arrow keys). Those should keep working
     * inside web pages, so only treat them as controller input when the device is a gamepad.
     */
    private static boolean fromPad(KeyEvent e) {
        InputDevice d = e.getDevice();
        if (d == null) return true;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Applies the dead zone and rescales so the output still reaches +-1. */
    static float dz(float v) {
        float a = Math.abs(v);
        if (a < DEADZONE) return 0f;
        float m = (a - DEADZONE) / (1f - DEADZONE);
        return v < 0 ? -m : m;
    }
}
