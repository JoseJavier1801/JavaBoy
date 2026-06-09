package com.emulator.memory;

import com.emulator.GBCMode;
import com.emulator.cpu.Interrupts;
import com.emulator.memory.cartridge.Cartridge;
import com.emulator.ppu.PPU;
import com.emulator.apu.APU;
import com.emulator.timer.Timer;
import com.emulator.input.Joypad;
import com.emulator.savestate.SaveData;

/**
 * Memory Bus — DMG + GBC.
 *
 * Diferencias GBC respecto a DMG:
 *   0xFF4F  VBK   — selección banco VRAM (0/1)
 *   0xFF55  HDMA5 — HDMA general purpose DMA
 *   0xFF56  RP    — infrared (stub)
 *   0xFF68  BCPS  — BG color palette spec
 *   0xFF69  BCPD  — BG color palette data
 *   0xFF6A  OCPS  — OBJ color palette spec
 *   0xFF6B  OCPD  — OBJ color palette data
 *   0xFF6C  OPRI  — object priority mode
 *   0xFF70  SVBK  — selección banco WRAM (1-7)
 *   0xFF4D  KEY1  — switch speed (double-speed mode)
 *   WRAM ampliado a 8 bancos de 4 KB (32 KB total)
 *   VRAM  ampliado a 2 bancos de 8 KB (16 KB total)
 */
public class MemoryBus {

    private final Cartridge  cart;
    private final PPU        ppu;
    private final APU        apu;
    private final Timer      timer;
    private final Joypad     joypad;
    private final GBCMode    mode;
    private Interrupts        irq;

    // ── WRAM GBC: 8 bancos × 4 KB (banco 0 siempre en 0xC000-0xCFFF) ────────
    private final int[] wram;       // DMG: 8 KB; GBC: 32 KB
    private int wramBank = 1;       // SVBK — banco activo (1-7 → banks 1-7)

    // ── HRAM ─────────────────────────────────────────────────────────────────
    private final int[] hram = new int[0x7F];

    // ── Serial (stub, para tests Blargg) ─────────────────────────────────────
    private int serialData = 0, serialControl = 0;

    // ── GBC KEY1 (double-speed) ───────────────────────────────────────────────
    private int  key1 = 0;              // 0x7E + bit0=prepare, bit7=current speed
    public boolean doubleSpeed = false;

    // ── GBC HDMA ──────────────────────────────────────────────────────────────
    private int hdmaSrc = 0, hdmaDst = 0, hdmaLen = 0;
    private boolean hdmaActive = false;
    private int hdmaRemaining = 0;

    public MemoryBus(Cartridge cart, PPU ppu, APU apu, Timer timer,
                     Joypad joypad, GBCMode mode) {
        this.cart   = cart;
        this.ppu    = ppu;
        this.apu    = apu;
        this.timer  = timer;
        this.joypad = joypad;
        this.mode   = mode;
        this.wram   = new int[mode == GBCMode.GBC ? 0x8000 : 0x2000];
        // Inicializar HRAM y registros IO al estado post-boot
        if (mode == GBCMode.GBC) initGBCBootState();
    }

    /** Inicializa registros internos al estado que dejaría el boot ROM del GBC */
    private void initGBCBootState() {
        // El boot ROM del GBC inicializa estos valores antes de saltar al juego
        hram[0xFF80 - 0xFF80] = 0; // placeholder
        // KEY1: velocidad normal
        key1 = 0x7E;
        // WRAM bank inicial = 1
        wramBank = 1;
    }

    public void setInterrupts(Interrupts i) { irq = i; }
    public GBCMode getMode()               { return mode; }

    // ── Read ──────────────────────────────────────────────────────────────────
    public int read(int addr) {
        addr &= 0xFFFF;
        if (addr < 0x8000) return cart.read(addr);
        if (addr < 0xA000) return ppu.readVRAM(addr);
        if (addr < 0xC000) return cart.readRAM(addr);
        // 0xC000-0xCFFF: WRAM banco 0
        if (addr < 0xD000) return wram[addr - 0xC000];
        // 0xD000-0xDFFF: WRAM banco bancado (GBC) o banco 1 fijo (DMG)
        if (addr < 0xE000) {
            int bank = (mode == GBCMode.GBC) ? wramBank : 1;
            return wram[bank * 0x1000 + (addr - 0xD000)];
        }
        if (addr < 0xFE00) return read(addr - 0x2000); // Echo RAM
        if (addr < 0xFEA0) return ppu.readOAM(addr);
        if (addr < 0xFF00) return 0xFF;
        if (addr < 0xFF80) return readIO(addr);
        if (addr < 0xFFFF) return hram[addr - 0xFF80];
        return irq != null ? irq.IE : 0xFF;
    }

    private int readIO(int a) {
        return switch (a) {
            case 0xFF00 -> joypad.read();
            case 0xFF01 -> serialData;
            case 0xFF02 -> serialControl;
            case 0xFF04, 0xFF05, 0xFF06, 0xFF07 -> timer.read(a);
            case 0xFF0F -> irq != null ? (irq.IF | 0xE0) : 0xFF;
            case 0xFF10,0xFF11,0xFF12,0xFF13,0xFF14,
                 0xFF16,0xFF17,0xFF18,0xFF19,
                 0xFF1A,0xFF1B,0xFF1C,0xFF1D,0xFF1E,
                 0xFF20,0xFF21,0xFF22,0xFF23,
                 0xFF24,0xFF25,0xFF26 -> apu.readIO(a);
            case 0xFF30,0xFF31,0xFF32,0xFF33,0xFF34,0xFF35,0xFF36,0xFF37,
                 0xFF38,0xFF39,0xFF3A,0xFF3B,0xFF3C,0xFF3D,0xFF3E,0xFF3F -> apu.readWave(a);
            case 0xFF40,0xFF41,0xFF42,0xFF43,0xFF44,0xFF45,
                 0xFF47,0xFF48,0xFF49,0xFF4A,0xFF4B -> ppu.readIO(a);
            case 0xFF4D -> mode == GBCMode.GBC ? key1 : 0xFF;  // KEY1
            case 0xFF4F -> mode == GBCMode.GBC ? ppu.readVBK() : 0xFF;
            case 0xFF55 -> mode == GBCMode.GBC ? (hdmaActive ? (hdmaRemaining-1) : 0xFF) : 0xFF;
            case 0xFF68 -> mode == GBCMode.GBC ? ppu.readBCPS() : 0xFF;
            case 0xFF69 -> mode == GBCMode.GBC ? ppu.readBCPD() : 0xFF;
            case 0xFF6A -> mode == GBCMode.GBC ? ppu.readOCPS() : 0xFF;
            case 0xFF6B -> mode == GBCMode.GBC ? ppu.readOCPD() : 0xFF;
            case 0xFF6C -> mode == GBCMode.GBC ? ppu.readOPRI() : 0xFF;
            case 0xFF70 -> mode == GBCMode.GBC ? (wramBank | 0xF8) : 0xFF;
            default -> 0xFF;
        };
    }

    // ── Write ─────────────────────────────────────────────────────────────────
    public void write(int addr, int val) {
        addr &= 0xFFFF; val &= 0xFF;
        if (addr < 0x8000) { cart.write(addr, val); return; }
        if (addr < 0xA000) { ppu.writeVRAM(addr, val); return; }
        if (addr < 0xC000) { cart.writeRAM(addr, val); return; }
        if (addr < 0xD000) { wram[addr - 0xC000] = val; return; }
        if (addr < 0xE000) {
            int bank = (mode == GBCMode.GBC) ? wramBank : 1;
            wram[bank * 0x1000 + (addr - 0xD000)] = val; return;
        }
        if (addr < 0xFE00) { write(addr - 0x2000, val); return; }
        if (addr < 0xFEA0) { ppu.writeOAM(addr, val); return; }
        if (addr < 0xFF00) return;
        if (addr < 0xFF80) { writeIO(addr, val); return; }
        if (addr < 0xFFFF) { hram[addr - 0xFF80] = val; return; }
        if (irq != null) irq.IE = val;
    }

    private void writeIO(int a, int v) {
        switch (a) {
            case 0xFF00 -> joypad.write(v);
            case 0xFF01 -> serialData = v;
            case 0xFF02 -> { serialControl = v; if (v == 0x81) System.out.print((char)serialData); }
            case 0xFF04, 0xFF05, 0xFF06, 0xFF07 -> timer.write(a, v);
            case 0xFF0F -> { if (irq != null) irq.IF = v | 0xE0; }
            case 0xFF10,0xFF11,0xFF12,0xFF13,0xFF14,
                 0xFF16,0xFF17,0xFF18,0xFF19,
                 0xFF1A,0xFF1B,0xFF1C,0xFF1D,0xFF1E,
                 0xFF20,0xFF21,0xFF22,0xFF23,
                 0xFF24,0xFF25,0xFF26 -> apu.writeIO(a, v);
            case 0xFF30,0xFF31,0xFF32,0xFF33,0xFF34,0xFF35,0xFF36,0xFF37,
                 0xFF38,0xFF39,0xFF3A,0xFF3B,0xFF3C,0xFF3D,0xFF3E,0xFF3F -> apu.writeWave(a, v);
            case 0xFF40,0xFF41,0xFF42,0xFF43,0xFF45,
                 0xFF47,0xFF48,0xFF49,0xFF4A,0xFF4B -> ppu.writeIO(a, v);
            case 0xFF44 -> {}  // LY read-only
            case 0xFF46 -> dmaOAM(v);
            // GBC-only
            case 0xFF4D -> { if (mode == GBCMode.GBC) key1 = (key1 & 0x80) | (v & 0x01); }
            case 0xFF4F -> { if (mode == GBCMode.GBC) ppu.writeVBK(v); }
            case 0xFF51 -> { if (mode == GBCMode.GBC) hdmaSrc  = (hdmaSrc  & 0x00FF) | (v << 8); }
            case 0xFF52 -> { if (mode == GBCMode.GBC) hdmaSrc  = (hdmaSrc  & 0xFF00) | (v & 0xF0); }
            case 0xFF53 -> { if (mode == GBCMode.GBC) hdmaDst  = (hdmaDst  & 0x00FF) | ((v & 0x1F) << 8); }
            case 0xFF54 -> { if (mode == GBCMode.GBC) hdmaDst  = (hdmaDst  & 0xFF00) | (v & 0xF0); }
            case 0xFF55 -> { if (mode == GBCMode.GBC) startHDMA(v); }
            case 0xFF68 -> { if (mode == GBCMode.GBC) ppu.writeBCPS(v); }
            case 0xFF69 -> { if (mode == GBCMode.GBC) ppu.writeBCPD(v); }
            case 0xFF6A -> { if (mode == GBCMode.GBC) ppu.writeOCPS(v); }
            case 0xFF6B -> { if (mode == GBCMode.GBC) ppu.writeOCPD(v); }
            case 0xFF6C -> { if (mode == GBCMode.GBC) ppu.writeOPRI(v); }
            case 0xFF70 -> {
                if (mode == GBCMode.GBC) {
                    wramBank = v & 0x07;
                    if (wramBank == 0) wramBank = 1; // banco 0 de SVBK → usa banco 1
                }
            }
        }
    }

    // ── OAM DMA (transferencia clásica) ───────────────────────────────────────
    private void dmaOAM(int v) {
        int src = v << 8;
        for (int i = 0; i < 0xA0; i++) ppu.writeOAM(0xFE00 + i, read(src + i));
    }

    // ── HDMA (GBC General Purpose DMA) ───────────────────────────────────────
    private void startHDMA(int v) {
        int blocks = (v & 0x7F) + 1;   // cada bloque = 16 bytes
        boolean hblankMode = (v & 0x80) != 0;

        if (hblankMode) {
            // H-Blank DMA: se transfiere 1 bloque por HBlank
            hdmaRemaining = blocks;
            hdmaActive    = true;
        } else {
            // General-purpose DMA: copia todo de golpe
            int len = blocks * 16;
            for (int i = 0; i < len; i++) {
                ppu.writeVRAM(0x8000 + ((hdmaDst + i) & 0x1FFF),
                              read(hdmaSrc + i));
            }
            hdmaActive = false;
        }
    }

    /** Llamado por el PPU al entrar en HBlank (para HDMA modo HBlank) */
    public void onHBlank() {
        if (!hdmaActive || hdmaRemaining <= 0) return;
        for (int i = 0; i < 16; i++) {
            ppu.writeVRAM(0x8000 + ((hdmaDst + i) & 0x1FFF), read(hdmaSrc + i));
        }
        hdmaSrc += 16; hdmaDst += 16; hdmaRemaining--;
        if (hdmaRemaining == 0) hdmaActive = false;
    }

    /** Cambia a doble velocidad (KEY1 speed switch) */
    public void doSpeedSwitch() {
        if (mode != GBCMode.GBC || (key1 & 0x01) == 0) return;
        doubleSpeed = !doubleSpeed;
        key1 = (doubleSpeed ? 0x80 : 0x00);
    }

    public void requestInterrupt(int bit) { if (irq != null) irq.request(bit); }

    // ── Save state ────────────────────────────────────────────────────────────
    public void captureState(SaveData d) {
        d.wram    = wram.clone();
        d.hram    = hram.clone();
        d.cartRam = cart.getRam();
    }
    public void applyState(SaveData d) {
        System.arraycopy(d.wram, 0, wram, 0, Math.min(d.wram.length, wram.length));
        System.arraycopy(d.hram, 0, hram, 0, Math.min(d.hram.length, hram.length));
        cart.setRam(d.cartRam);
    }
}
