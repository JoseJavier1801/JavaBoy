package com.emulator.cpu;

/**
 * Registros Sharp LR35902.
 *
 * Estado post-boot según hardware:
 *   DMG: A=0x01, F=0xB0, BC=0x0013, DE=0x00D8, HL=0x014D, SP=0xFFFE, PC=0x0100
 *   GBC: A=0x11, F=0x80, BC=0x0000, DE=0xFF56, HL=0x000D, SP=0xFFFE, PC=0x0100
 *
 * El valor de A es crítico: muchos juegos GBC leen A al inicio para
 * detectar si están corriendo en DMG (0x01) o GBC (0x11) y cargar
 * distintas rutinas de inicialización (paletas, modo gráfico, etc.).
 * Sin esto la pantalla queda en negro aunque el audio funcione.
 */
public class Registers {
    public int A, F, B, C, D, E, H, L, SP, PC;

    public static final int FLAG_Z = 0x80;
    public static final int FLAG_N = 0x40;
    public static final int FLAG_H = 0x20;
    public static final int FLAG_C = 0x10;

    public int  getAF() { return (A << 8) | (F & 0xF0); }
    public int  getBC() { return (B << 8) | C; }
    public int  getDE() { return (D << 8) | E; }
    public int  getHL() { return (H << 8) | L; }

    public void setAF(int v) { A = (v >> 8) & 0xFF; F = v & 0xF0; }
    public void setBC(int v) { B = (v >> 8) & 0xFF; C = v & 0xFF; }
    public void setDE(int v) { D = (v >> 8) & 0xFF; E = v & 0xFF; }
    public void setHL(int v) { H = (v >> 8) & 0xFF; L = v & 0xFF; }

    public boolean isZ() { return (F & FLAG_Z) != 0; }
    public boolean isN() { return (F & FLAG_N) != 0; }
    public boolean isH() { return (F & FLAG_H) != 0; }
    public boolean isC() { return (F & FLAG_C) != 0; }

    public void setZ(boolean v) { if (v) F |= FLAG_Z; else F &= ~FLAG_Z; }
    public void setN(boolean v) { if (v) F |= FLAG_N; else F &= ~FLAG_N; }
    public void setH(boolean v) { if (v) F |= FLAG_H; else F &= ~FLAG_H; }
    public void setC(boolean v) { if (v) F |= FLAG_C; else F &= ~FLAG_C; }
    public void setFlags(boolean z, boolean n, boolean h, boolean c) {
        setZ(z); setN(n); setH(h); setC(c);
    }

    /** Estado post-boot DMG */
    public void reset() {
        A = 0x01; F = 0xB0;
        B = 0x00; C = 0x13;
        D = 0x00; E = 0xD8;
        H = 0x01; L = 0x4D;
        SP = 0xFFFE; PC = 0x0100;
    }

    /** Estado post-boot GBC — A=0x11 es la clave para que los juegos
     *  GBC detecten el hardware y activen las rutinas de color */
    public void resetGBC() {
        A = 0x11; F = 0x80;
        B = 0x00; C = 0x00;
        D = 0xFF; E = 0x56;
        H = 0x00; L = 0x0D;
        SP = 0xFFFE; PC = 0x0100;
    }
}
