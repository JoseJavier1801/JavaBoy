package com.emulator.gba.memory;

import com.emulator.gba.ppu.GBAPPU;
import com.emulator.gba.apu.GBAAPU;
import com.emulator.gba.input.GBAJoypad;

import java.io.*;
import java.nio.file.*;

/**
 * GBA Memory Map (all addresses 32-bit):
 *   0x00000000–0x00003FFF  BIOS (16 KB) — usar open-source bios stub
 *   0x02000000–0x0203FFFF  EWRAM (256 KB)
 *   0x03000000–0x03007FFF  IWRAM (32 KB)
 *   0x04000000–0x040003FF  I/O registers
 *   0x05000000–0x050003FF  Palette RAM (1 KB)
 *   0x06000000–0x06017FFF  VRAM (96 KB)
 *   0x07000000–0x070003FF  OAM (1 KB)
 *   0x08000000–0x0DFFFFFF  ROM (up to 32 MB, mirrored)
 *   0x0E000000–0x0E00FFFF  SRAM/Flash (64 KB)
 */
public class GBAMemory {

    // ── RAM regions ───────────────────────────────────────────────────────────
    private final int[] bios   = new int[0x4000];     // 16 KB
    private final int[] ewram  = new int[0x40000];    // 256 KB
    private final int[] iwram  = new int[0x8000];     // 32 KB
    private final int[] sram   = new int[0x10000];    // 64 KB

    // ROM
    private int[] rom = new int[0];

    // Connected components
    private GBAPPU   ppu;
    private GBAAPU   apu;
    private GBAJoypad joypad;

    // ── I/O registers (0x04000000–0x040003FF) ─────────────────────────────────
    private final int[] ioReg = new int[0x400];

    // Interrupt registers
    public int IE = 0, IF = 0, IME = 0;
    public boolean halted = false;

    // DMA channels (4 channels)
    private final int[][] dmaRegs = new int[4][12]; // SAD, DAD, CNT_L, CNT_H per channel
    private final int[]   dmaSrc  = new int[4];
    private final int[]   dmaDst  = new int[4];
    private final int[]   dmaCount= new int[4];

    // Timer registers (4 timers)
    private final int[]  timerReload  = new int[4];
    private final int[]  timerCounter = new int[4];
    private final int[]  timerControl = new int[4];
    private final long[] timerCycles  = new long[4];

    // Cycle counter for timers
    private long totalCycles = 0;

    // WAITSTATE/POSTFLG
    private int waitcnt = 0;

    public GBAMemory() { installMiniBIOS(); }

    public void setPPU(GBAPPU p)    { this.ppu    = p; }
    public void setAPU(GBAAPU a)    { this.apu    = a; }
    public void setJoypad(GBAJoypad j){ this.joypad = j; }

    // ── Mini BIOS (open-source replacement) ───────────────────────────────────
    private void installMiniBIOS() {
        // SWI handler stubs — minimal BIOS that handles common SWI calls
        // and sets correct registers at ROM entry point
        // Real GBA boots at 0x08000000; BIOS initializes stack and jumps there
        // We jump directly to 0x08000000 by setting PC in CPU reset
        int[] stub = {
            0xEA000006, // B 0x20 (skip header)
            0xEAFFFFFE, // B $ (halt loop — IRQ vector)
            0xEAFFFFFE, 0xEAFFFFFE, 0xEAFFFFFE, 0xEAFFFFFE, 0xEAFFFFFE, 0xEAFFFFFE,
            // SWI dispatcher at 0x0000_0020
            0xE92D500F, // PUSH {r0-r3,r12,lr}
            0xE1A0000F, // MOV r0, r15
            0xE3A02004, // MOV r2, #4
            0xE0200002, // AND r0,r0,r2... simplified
            0xE8BD900F, // POP {r0-r3,r12,pc}
        };
        System.arraycopy(stub, 0, bios, 0, Math.min(stub.length, bios.length));
        for (int i = stub.length; i < bios.length; i++) bios[i] = 0xEAFFFFFE; // B $
    }

    // ── ROM loading ───────────────────────────────────────────────────────────
    public void loadROM(String path) throws IOException {
        byte[] raw = Files.readAllBytes(Paths.get(path));
        rom = new int[raw.length];
        for (int i = 0; i < raw.length; i++) rom[i] = raw[i] & 0xFF;
        System.out.printf("[GBA] ROM: %s (%d KB)%n", Paths.get(path).getFileName(), raw.length / 1024);
    }

    public int[] getRom() { return rom; }

    // ── SRAM save ─────────────────────────────────────────────────────────────
    public int[] getSRAM() { return sram; }
    public void  setSRAM(int[] data) {
        System.arraycopy(data, 0, sram, 0, Math.min(data.length, sram.length));
    }

    // ── Read ──────────────────────────────────────────────────────────────────
    public int read8(int addr) {
        addr &= 0x0FFFFFFF; // ignore top nibble for ROM mirrors
        int region = addr >>> 24;
        return switch (region) {
            case 0x00 -> { int i = addr & 0x3FFF; yield bios[i]; }
            case 0x02 -> ewram[addr & 0x3FFFF];
            case 0x03 -> iwram[addr & 0x7FFF];
            case 0x04 -> readIO8(addr & 0x3FF);
            case 0x05 -> ppu != null ? ppu.readPalette8(addr & 0x3FF) : 0;
            case 0x06 -> ppu != null ? ppu.readVRAM8(addr & 0x1FFFF) : 0;
            case 0x07 -> ppu != null ? ppu.readOAM8(addr & 0x3FF) : 0;
            case 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> {
                int off = addr & 0x1FFFFFF;
                yield off < rom.length ? rom[off] : 0;
            }
            case 0x0E -> sram[addr & 0xFFFF];
            default -> 0;
        };
    }

    public int read16(int addr) {
        addr &= ~1;
        return read8(addr) | (read8(addr + 1) << 8);
    }

    public int read32(int addr) {
        addr &= ~3;
        return read8(addr) | (read8(addr+1) << 8) | (read8(addr+2) << 16) | (read8(addr+3) << 24);
    }

    // ── Write ─────────────────────────────────────────────────────────────────
    public void write8(int addr, int val) {
        val &= 0xFF;
        int region = (addr >>> 24) & 0x0F;
        switch (region) {
            case 0x02 -> ewram[addr & 0x3FFFF] = val;
            case 0x03 -> iwram[addr & 0x7FFF]  = val;
            case 0x04 -> writeIO8(addr & 0x3FF, val);
            case 0x05 -> { if (ppu != null) ppu.writePalette8(addr & 0x3FF, val); }
            case 0x06 -> { if (ppu != null) ppu.writeVRAM8(addr & 0x1FFFF, val); }
            case 0x07 -> { if (ppu != null) ppu.writeOAM8(addr & 0x3FF, val); }
            case 0x0E -> sram[addr & 0xFFFF] = val;
        }
    }

    public void write16(int addr, int val) {
        addr &= ~1;
        write8(addr,   val & 0xFF);
        write8(addr+1, (val >> 8) & 0xFF);
    }

    public void write32(int addr, int val) {
        addr &= ~3;
        write8(addr,   val & 0xFF);
        write8(addr+1, (val >> 8) & 0xFF);
        write8(addr+2, (val >> 16) & 0xFF);
        write8(addr+3, (val >> 24) & 0xFF);
    }

    // ── I/O ───────────────────────────────────────────────────────────────────
    private int readIO8(int off) {
        // PPU regs 0x000–0x057
        if (off < 0x058 && ppu != null) return ppu.readIO8(off);
        // Sound regs 0x060–0x0A7
        if (off >= 0x060 && off < 0x0A8 && apu != null) return apu.readIO8(off);
        // DMA 0x0B0–0x0DF
        // Timer 0x100–0x10F
        if (off >= 0x100 && off <= 0x10F) return readTimer8(off - 0x100);
        // Serial/comm 0x120–0x12B
        // Keypad 0x130–0x133
        if (off == 0x130) return joypad != null ? (joypad.readLow() & 0xFF) : 0xFF;
        if (off == 0x131) return joypad != null ? ((joypad.readLow() >> 8) & 0xFF) : 0x03;
        // Interrupt
        if (off == 0x200) return IE & 0xFF;
        if (off == 0x201) return (IE >> 8) & 0xFF;
        if (off == 0x202) return IF & 0xFF;
        if (off == 0x203) return (IF >> 8) & 0xFF;
        if (off == 0x208) return IME & 0xFF;
        if (off == 0x300) return halted ? 0x80 : 0;  // HALTCNT
        return off < ioReg.length ? ioReg[off] : 0;
    }

    private void writeIO8(int off, int val) {
        if (off < ioReg.length) ioReg[off] = val;
        if (off < 0x058 && ppu != null)           { ppu.writeIO8(off, val); return; }
        if (off >= 0x060 && off < 0x0A8 && apu != null) { apu.writeIO8(off, val); return; }
        if (off >= 0x0B0 && off <= 0x0DF)          { writeDMA8(off - 0x0B0, val); return; }
        if (off >= 0x100 && off <= 0x10F)          { writeTimer8(off - 0x100, val); return; }
        if (off == 0x200) IE = (IE & 0xFF00) | val;
        if (off == 0x201) IE = (IE & 0x00FF) | (val << 8);
        if (off == 0x202) IF &= ~val;          // Writing 1 clears IF bits
        if (off == 0x203) IF &= ~(val << 8);
        if (off == 0x208) IME = val & 1;
        if (off == 0x301) halted = (val & 0x80) == 0; // HALTCNT
    }

    // ── DMA ───────────────────────────────────────────────────────────────────
    private void writeDMA8(int off, int val) {
        int ch = off / 12, reg = off % 12;
        dmaRegs[ch][reg] = val;
        // CNT_H byte 1 (offset 11) = DMA enable trigger
        if (reg == 11 && (val & 0x80) != 0) triggerDMA(ch);
    }

    private void triggerDMA(int ch) {
        int srcBase = ch * 12;
        int src  = dmaRegs[ch][0] | (dmaRegs[ch][1]<<8) | (dmaRegs[ch][2]<<16) | (dmaRegs[ch][3]<<24);
        int dst  = dmaRegs[ch][4] | (dmaRegs[ch][5]<<8) | (dmaRegs[ch][6]<<16) | (dmaRegs[ch][7]<<24);
        int cnt  = dmaRegs[ch][8] | (dmaRegs[ch][9]<<8);
        int ctrl = dmaRegs[ch][10]| (dmaRegs[ch][11]<<8);
        if (cnt == 0) cnt = (ch == 3) ? 0x10000 : 0x4000;
        boolean word = (ctrl & 0x0400) != 0;
        int srcAdj = (ctrl >> 7) & 3, dstAdj = (ctrl >> 5) & 3;
        int step = word ? 4 : 2;

        for (int i = 0; i < cnt; i++) {
            if (word) write32(dst, read32(src));
            else      write16(dst, read16(src));
            src += srcAdj == 0 ? step : srcAdj == 1 ? -step : 0;
            dst += dstAdj == 0 ? step : dstAdj == 1 ? -step : 0;
        }
        // Clear enable bit unless repeat
        if ((ctrl & 0x0200) == 0) dmaRegs[ch][11] &= ~0x80;
        // Request IRQ if enabled
        if ((ctrl & 0x4000) != 0) requestInterrupt(8 + ch);
    }

    // ── Timers ────────────────────────────────────────────────────────────────
    private static final int[] TIMER_PRESCALER = {1, 64, 256, 1024};

    private int readTimer8(int off) {
        int t = off / 4, r = off % 4;
        return switch (r) {
            case 0 -> timerCounter[t] & 0xFF;
            case 1 -> (timerCounter[t] >> 8) & 0xFF;
            case 2 -> timerControl[t] & 0xFF;
            default -> 0;
        };
    }

    private void writeTimer8(int off, int val) {
        int t = off / 4, r = off % 4;
        switch (r) {
            case 0 -> timerReload[t] = (timerReload[t] & 0xFF00) | val;
            case 1 -> timerReload[t] = (timerReload[t] & 0x00FF) | (val << 8);
            case 2 -> {
                boolean wasEnabled = (timerControl[t] & 0x80) != 0;
                timerControl[t] = val;
                if (!wasEnabled && (val & 0x80) != 0)
                    timerCounter[t] = timerReload[t];
            }
        }
    }

    public void stepTimers(int cycles) {
        totalCycles += cycles;
        for (int t = 0; t < 4; t++) {
            if ((timerControl[t] & 0x80) == 0) continue;
            if ((timerControl[t] & 0x04) != 0 && t > 0) continue; // cascade
            int prescaler = TIMER_PRESCALER[timerControl[t] & 3];
            timerCycles[t] += cycles;
            while (timerCycles[t] >= prescaler) {
                timerCycles[t] -= prescaler;
                timerCounter[t] = (timerCounter[t] + 1) & 0xFFFF;
                if (timerCounter[t] == 0) {
                    timerCounter[t] = timerReload[t];
                    if ((timerControl[t] & 0x40) != 0) requestInterrupt(3 + t);
                    // Cascade to next timer
                    if (t < 3 && (timerControl[t+1] & 0x84) == 0x84) {
                        timerCounter[t+1] = (timerCounter[t+1] + 1) & 0xFFFF;
                        if (timerCounter[t+1] == 0) {
                            timerCounter[t+1] = timerReload[t+1];
                            if ((timerControl[t+1] & 0x40) != 0) requestInterrupt(4 + t);
                        }
                    }
                }
            }
        }
    }

    // ── Interrupts ────────────────────────────────────────────────────────────
    public void requestInterrupt(int bit) {
        IF |= (1 << bit);
    }

    public boolean hasPendingIRQ() {
        return IME != 0 && (IE & IF) != 0;
    }
}
