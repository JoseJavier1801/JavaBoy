package com.emulator.gba.input;

/**
 * GBA Joypad — registro KEYINPUT 0x04000130.
 * 10 botones: A B Select Start Right Left Up Down R L
 * 0 = presionado, 1 = suelto (activo bajo).
 */
public class GBAJoypad {

    public enum Button { A, B, SELECT, START, RIGHT, LEFT, UP, DOWN, R, L }

    // Máscara de bits: bit0=A, bit1=B, bit2=Select, bit3=Start,
    //                  bit4=Right, bit5=Left, bit6=Up, bit7=Down, bit8=R, bit9=L
    private int state = 0x03FF;  // todos sueltos

    public synchronized void press(Button b)   { state &= ~mask(b); }
    public synchronized void release(Button b) { state |=  mask(b); }

    /** Devuelve el valor de KEYINPUT (0x04000130–0x04000131) */
    public synchronized int readLow() { return state & 0x03FF; }

    private int mask(Button b) {
        return switch (b) {
            case A      -> 0x001;
            case B      -> 0x002;
            case SELECT -> 0x004;
            case START  -> 0x008;
            case RIGHT  -> 0x010;
            case LEFT   -> 0x020;
            case UP     -> 0x040;
            case DOWN   -> 0x080;
            case R      -> 0x100;
            case L      -> 0x200;
        };
    }
}
