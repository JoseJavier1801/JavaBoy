package com.emulator.savestate;

import com.emulator.cpu.CPU;
import com.emulator.cpu.Registers;
import com.emulator.cpu.Interrupts;
import com.emulator.ppu.PPU;
import com.emulator.timer.Timer;
import com.emulator.memory.MemoryBus;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Save State system — serializes and restores the full emulator state.
 *
 * File format (.sav):
 *   [4 bytes] Magic: 0x47424553 ("GBES")
 *   [4 bytes] Version: 1
 *   [N bytes] CPU registers
 *   [N bytes] Interrupts
 *   [N bytes] Memory (WRAM, HRAM)
 *   [N bytes] PPU state
 *   [N bytes] Timer state
 */
public class SaveStateManager {

    private static final int MAGIC   = 0x47424553;
    private static final int VERSION = 1;

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path saveDir;

    private final String romName;  // nombre base de la ROM, sin extensión

    public SaveStateManager(String romPath) {
        Path romFile = Paths.get(romPath);
        this.saveDir = romFile.getParent() != null
            ? romFile.getParent()
            : Paths.get(".");
        // Extraer nombre sin extensión y sanitizar (solo alfanuméricos y guiones)
        String name = romFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        this.romName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** Devuelve el nombre de la ROM (para mostrar en la UI) */
    public String getRomName() { return romName; }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Save to slot (1–8). Returns true on success. */
    public boolean save(int slot, SaveData data) {
        Path file = slotPath(slot);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeTimestamp(out);
            writeCPU(out, data);
            writeMemory(out, data);
            writePPU(out, data);
            writeTimer(out, data);
            System.out.printf("[Save] Slot %d saved → %s%n", slot, file);
            return true;
        } catch (IOException e) {
            System.err.println("[Save] Error saving slot " + slot + ": " + e.getMessage());
            return false;
        }
    }

    /** Load from slot. Returns null if slot is empty or corrupt. */
    public SaveData load(int slot) {
        Path file = slotPath(slot);
        if (!Files.exists(file)) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {

            int magic = in.readInt();
            if (magic != MAGIC) throw new IOException("Invalid save file magic");
            int version = in.readInt();
            if (version != VERSION) throw new IOException("Unsupported version: " + version);

            String ts   = in.readUTF();
            SaveData d  = new SaveData();
            d.timestamp = ts;
            readCPU(in, d);
            readMemory(in, d);
            readPPU(in, d);
            readTimer(in, d);
            System.out.printf("[Save] Slot %d loaded (saved: %s)%n", slot, ts);
            return d;
        } catch (IOException e) {
            System.err.println("[Save] Error loading slot " + slot + ": " + e.getMessage());
            return null;
        }
    }

    /** Returns timestamp string for a slot, or null if empty */
    public String slotTimestamp(int slot) {
        Path file = slotPath(slot);
        if (!Files.exists(file)) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) return null;
            in.readInt(); // version
            return in.readUTF();
        } catch (IOException e) { return null; }
    }

    public boolean slotExists(int slot) { return Files.exists(slotPath(slot)); }

    private Path slotPath(int slot) {
        return saveDir.resolve(romName + "_slot" + slot + ".ss");
    }

    // ── Write helpers ─────────────────────────────────────────────────────────

    private void writeTimestamp(DataOutputStream out) throws IOException {
        out.writeUTF(LocalDateTime.now().format(FMT));
    }

    private void writeCPU(DataOutputStream out, SaveData d) throws IOException {
        out.writeByte(d.A); out.writeByte(d.F);
        out.writeByte(d.B); out.writeByte(d.C);
        out.writeByte(d.D); out.writeByte(d.E);
        out.writeByte(d.H); out.writeByte(d.L);
        out.writeShort(d.SP); out.writeShort(d.PC);
        out.writeBoolean(d.IME); out.writeByte(d.IE); out.writeByte(d.IF);
        out.writeBoolean(d.halted);
    }

    private void writeMemory(DataOutputStream out, SaveData d) throws IOException {
        writeIntArray(out, d.wram);
        writeIntArray(out, d.hram);
        writeIntArray(out, d.cartRam);
    }

    private void writePPU(DataOutputStream out, SaveData d) throws IOException {
        out.writeByte(d.LCDC); out.writeByte(d.STAT);
        out.writeByte(d.SCY);  out.writeByte(d.SCX);
        out.writeByte(d.LY);   out.writeByte(d.LYC);
        out.writeByte(d.BGP);  out.writeByte(d.OBP0); out.writeByte(d.OBP1);
        out.writeByte(d.WY);   out.writeByte(d.WX);
        out.writeByte(d.ppuMode); out.writeInt(d.ppuCycles); out.writeInt(d.windowLine);
        writeIntArray(out, d.vram);
        writeIntArray(out, d.oam);
    }

    private void writeTimer(DataOutputStream out, SaveData d) throws IOException {
        out.writeByte(d.DIV); out.writeByte(d.TIMA);
        out.writeByte(d.TMA); out.writeByte(d.TAC);
        out.writeInt(d.divAcc); out.writeInt(d.timaAcc);
    }

    private void writeIntArray(DataOutputStream out, int[] arr) throws IOException {
        out.writeInt(arr.length);
        for (int v : arr) out.writeByte(v);
    }

    // ── Read helpers ──────────────────────────────────────────────────────────

    private void readCPU(DataInputStream in, SaveData d) throws IOException {
        d.A = in.readByte() & 0xFF; d.F = in.readByte() & 0xFF;
        d.B = in.readByte() & 0xFF; d.C = in.readByte() & 0xFF;
        d.D = in.readByte() & 0xFF; d.E = in.readByte() & 0xFF;
        d.H = in.readByte() & 0xFF; d.L = in.readByte() & 0xFF;
        d.SP = in.readShort() & 0xFFFF; d.PC = in.readShort() & 0xFFFF;
        d.IME = in.readBoolean(); d.IE = in.readByte() & 0xFF; d.IF = in.readByte() & 0xFF;
        d.halted = in.readBoolean();
    }

    private void readMemory(DataInputStream in, SaveData d) throws IOException {
        d.wram    = readIntArray(in);
        d.hram    = readIntArray(in);
        d.cartRam = readIntArray(in);
    }

    private void readPPU(DataInputStream in, SaveData d) throws IOException {
        d.LCDC = in.readByte() & 0xFF; d.STAT = in.readByte() & 0xFF;
        d.SCY  = in.readByte() & 0xFF; d.SCX  = in.readByte() & 0xFF;
        d.LY   = in.readByte() & 0xFF; d.LYC  = in.readByte() & 0xFF;
        d.BGP  = in.readByte() & 0xFF; d.OBP0 = in.readByte() & 0xFF; d.OBP1 = in.readByte() & 0xFF;
        d.WY   = in.readByte() & 0xFF; d.WX   = in.readByte() & 0xFF;
        d.ppuMode   = in.readByte() & 0xFF;
        d.ppuCycles = in.readInt();
        d.windowLine = in.readInt();
        d.vram = readIntArray(in);
        d.oam  = readIntArray(in);
    }

    private void readTimer(DataInputStream in, SaveData d) throws IOException {
        d.DIV  = in.readByte() & 0xFF; d.TIMA = in.readByte() & 0xFF;
        d.TMA  = in.readByte() & 0xFF; d.TAC  = in.readByte() & 0xFF;
        d.divAcc  = in.readInt();
        d.timaAcc = in.readInt();
    }

    private int[] readIntArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) arr[i] = in.readByte() & 0xFF;
        return arr;
    }
}
