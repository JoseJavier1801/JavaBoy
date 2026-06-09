package com.emulator.memory.cartridge;

/**
 * MBC3 — hasta 2 MB ROM, 32 KB RAM, RTC stub.
 * Usado por Pokémon Oro/Plata/Cristal, Zelda Link's Awakening DX…
 */
public class MBC3 extends Cartridge {

    private int     romBank    = 1;
    private int     ramBank    = 0;
    private boolean ramEnabled = false;
    private final int[] rtc    = new int[5];   // S, M, H, DL, DH

    public MBC3(int[] rom, int[] ram) { super(rom, ram); }

    @Override
    public int read(int addr) {
        if (addr < 0x4000) return rom[addr];
        if (addr < 0x8000) {
            int bank = romBank & (Math.max(2, rom.length / 0x4000) - 1);
            return rom[bank * 0x4000 + (addr - 0x4000)];
        }
        return 0xFF;
    }

    @Override
    public void write(int addr, int val) {
        if      (addr < 0x2000) ramEnabled = (val & 0x0F) == 0x0A;
        else if (addr < 0x4000) { romBank = val & 0x7F; if (romBank == 0) romBank = 1; }
        else if (addr < 0x6000) ramBank = val;
        // 0x6000–0x7FFF: latch RTC (stub — no implementado)
    }

    @Override
    public int readRAM(int addr) {
        if (!ramEnabled) return 0xFF;
        if (ramBank >= 0x08 && ramBank <= 0x0C) return rtc[ramBank - 0x08];
        if (ram.length == 0) return 0xFF;
        int off = (ramBank * 0x2000 + (addr - 0xA000)) % ram.length;
        return ram[off];
    }

    @Override
    public void writeRAM(int addr, int val) {
        if (!ramEnabled) return;
        if (ramBank >= 0x08 && ramBank <= 0x0C) {
            rtc[ramBank - 0x08] = val;
            scheduleSave();
            return;
        }
        if (ram.length == 0) return;
        int off = (ramBank * 0x2000 + (addr - 0xA000)) % ram.length;
        ram[off] = val;
        scheduleSave();  // persistir en .sav
    }
}
