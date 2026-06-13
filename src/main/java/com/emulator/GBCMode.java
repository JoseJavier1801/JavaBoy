package com.emulator;

/**
 * Modo de hardware — ahora incluye GBA.
 * Detección:
 *   GBA:  extensión .gba
 *   GBC:  byte 0x0143 == 0x80 o 0xC0
 *   DMG:  resto
 */
public enum GBCMode {
    DMG, GBC, GBA;

    public static GBCMode fromHeader(int byte0143) {
        return (byte0143 == 0x80 || byte0143 == 0xC0) ? GBC : DMG;
    }
}
