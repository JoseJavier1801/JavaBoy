package com.emulator.memory.cartridge;

/**
 * MBC1 — hasta 2 MB ROM, 32 KB RAM.
 * Mode 0: ROM banking. Mode 1: RAM banking.
 */
public class MBC1 extends Cartridge {

    private int     romBank    = 1;
    private int     ramBank    = 0;
    private boolean ramEnabled = false;
    private boolean mode1      = false;

    public MBC1(int[] rom, int[] ram) { super(rom, ram); }

    @Override
    public int read(int addr) {
        if (addr < 0x4000) {
            int bank = mode1 ? (ramBank << 5) & (romBanks() - 1) : 0;
            return rom[bank * 0x4000 + addr];
        }
        if (addr < 0x8000) {
            int bank = ((ramBank << 5) | romBank) & (romBanks() - 1);
            return rom[bank * 0x4000 + (addr - 0x4000)];
        }
        return 0xFF;
    }

    @Override
    public void write(int addr, int val) {
        if      (addr < 0x2000) ramEnabled = (val & 0x0F) == 0x0A;
        else if (addr < 0x4000) { romBank = val & 0x1F; if (romBank == 0) romBank = 1; }
        else if (addr < 0x6000) ramBank = val & 0x03;
        else                    mode1 = (val & 0x01) != 0;
    }

    @Override
    public int readRAM(int addr) {
        if (!ramEnabled || ram.length == 0) return 0xFF;
        int bank = mode1 ? ramBank : 0;
        int off  = (bank * 0x2000 + (addr - 0xA000)) % ram.length;
        return ram[off];
    }

    @Override
    public void writeRAM(int addr, int val) {
        if (!ramEnabled || ram.length == 0) return;
        int bank = mode1 ? ramBank : 0;
        int off  = (bank * 0x2000 + (addr - 0xA000)) % ram.length;
        ram[off] = val;
        scheduleSave();  // persistir en .sav
    }

    private int romBanks() { return Math.max(2, rom.length / 0x4000); }
}
