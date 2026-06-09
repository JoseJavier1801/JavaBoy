package com.emulator.memory.cartridge;

/**
 * MBC5 — hasta 8 MB ROM, 128 KB RAM.
 * Usado por Pokémon Cristal, Mario Bros DX…
 */
public class MBC5 extends Cartridge {

    private int     romLo      = 1;
    private int     romHi      = 0;
    private int     ramBank    = 0;
    private boolean ramEnabled = false;

    public MBC5(int[] rom, int[] ram) { super(rom, ram); }

    @Override
    public int read(int addr) {
        if (addr < 0x4000) return rom[addr];
        if (addr < 0x8000) {
            int bank = ((romHi << 8) | romLo) % Math.max(2, rom.length / 0x4000);
            return rom[bank * 0x4000 + (addr - 0x4000)];
        }
        return 0xFF;
    }

    @Override
    public void write(int addr, int val) {
        if      (addr < 0x2000) ramEnabled = (val & 0x0F) == 0x0A;
        else if (addr < 0x3000) romLo = val & 0xFF;
        else if (addr < 0x4000) romHi = val & 0x01;
        else if (addr < 0x6000) ramBank = val & 0x0F;
    }

    @Override
    public int readRAM(int addr) {
        if (!ramEnabled || ram.length == 0) return 0xFF;
        int off = (ramBank * 0x2000 + (addr - 0xA000)) % ram.length;
        return ram[off];
    }

    @Override
    public void writeRAM(int addr, int val) {
        if (!ramEnabled || ram.length == 0) return;
        int off = (ramBank * 0x2000 + (addr - 0xA000)) % ram.length;
        ram[off] = val;
        scheduleSave();  // persistir en .sav
    }
}
