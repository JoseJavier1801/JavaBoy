package com.emulator.memory.cartridge;

/**
 * MBC2 — 256 KB ROM, 512×4-bit RAM interna.
 */
public class MBC2 extends Cartridge {

    private int     romBank    = 1;
    private boolean ramEnabled = false;
    private final int[] intRam = new int[512];

    public MBC2(int[] rom) { super(rom, new int[0]); }

    @Override
    public int read(int addr) {
        if (addr < 0x4000) return rom[addr];
        if (addr < 0x8000) return rom[(romBank * 0x4000 + (addr - 0x4000)) % rom.length];
        return 0xFF;
    }

    @Override
    public void write(int addr, int val) {
        if (addr < 0x4000) {
            if ((addr & 0x0100) == 0) ramEnabled = (val & 0x0F) == 0x0A;
            else { romBank = val & 0x0F; if (romBank == 0) romBank = 1; }
        }
    }

    @Override
    public int readRAM(int addr) {
        return ramEnabled ? (intRam[(addr - 0xA000) & 0x1FF] | 0xF0) : 0xFF;
    }

    @Override
    public void writeRAM(int addr, int val) {
        if (!ramEnabled) return;
        intRam[(addr - 0xA000) & 0x1FF] = val & 0x0F;
        scheduleSave();
    }

    // MBC2 usa intRam en lugar de ram[] — sobreescribir getRam/setRam
    @Override public int[] getRam() { return intRam.clone(); }
    @Override public void  setRam(int[] r) {
        if (r != null) System.arraycopy(r, 0, intRam, 0, Math.min(r.length, intRam.length));
    }
}
