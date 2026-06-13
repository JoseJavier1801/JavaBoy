package com.emulator.cpu;

import com.emulator.GBCMode;

import com.emulator.cpu.Interrupts;
import com.emulator.cpu.Registers;
import com.emulator.memory.MemoryBus;

/**
 * Sharp LR35902 CPU — complete opcode implementation.
 * Executes one instruction per step() call, returns clock cycles consumed.
 */
public class CPU {

    private final Registers  regs;
    private final Interrupts irq;
    private final MemoryBus  bus;

    private boolean halted = false;
    private boolean haltBug = false;   // HALT bug: next byte read twice

    public CPU(MemoryBus bus) {
        this(bus, GBCMode.DMG);
    }

    public CPU(MemoryBus bus, GBCMode mode) {
        this.bus  = bus;
        this.regs = new Registers();
        this.irq  = new Interrupts();
        if (mode == GBCMode.GBC) regs.resetGBC(); else regs.reset();
        bus.setInterrupts(irq);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public int step() {
        // Handle pending IME enable
        int irqCycles = irq.service(regs, bus);
        if (irqCycles > 0) {
            halted = false;
            return irqCycles;
        }

        if (halted) {
            if (irq.hasPending()) halted = false;
            return 4;   // NOP while halted
        }

        int opcode = fetchByte();
        if (haltBug) { regs.PC = (regs.PC - 1) & 0xFFFF; haltBug = false; }

        return execute(opcode);
    }

    public Registers getRegisters() { return regs; }
    public Interrupts getInterrupts(){ return irq; }

    // ── Fetch helpers ────────────────────────────────────────────────────────

    private int fetchByte() {
        int v = bus.read(regs.PC);
        regs.PC = (regs.PC + 1) & 0xFFFF;
        return v;
    }

    private int fetchWord() {
        int lo = fetchByte();
        int hi = fetchByte();
        return (hi << 8) | lo;
    }

    // ── Stack helpers ────────────────────────────────────────────────────────

    private void push(int val) {
        regs.SP = (regs.SP - 1) & 0xFFFF;
        bus.write(regs.SP, (val >> 8) & 0xFF);
        regs.SP = (regs.SP - 1) & 0xFFFF;
        bus.write(regs.SP, val & 0xFF);
    }

    private int pop() {
        int lo = bus.read(regs.SP); regs.SP = (regs.SP + 1) & 0xFFFF;
        int hi = bus.read(regs.SP); regs.SP = (regs.SP + 1) & 0xFFFF;
        return (hi << 8) | lo;
    }

    // ── ALU helpers ──────────────────────────────────────────────────────────

    private int add8(int a, int b) {
        int r = a + b;
        regs.setFlags((r & 0xFF) == 0, false,
            ((a & 0xF) + (b & 0xF)) > 0xF,
            r > 0xFF);
        return r & 0xFF;
    }

    private int adc8(int a, int b) {
        int c = regs.isC() ? 1 : 0;
        int r = a + b + c;
        regs.setFlags((r & 0xFF) == 0, false,
            ((a & 0xF) + (b & 0xF) + c) > 0xF,
            r > 0xFF);
        return r & 0xFF;
    }

    private int sub8(int a, int b) {
        int r = a - b;
        regs.setFlags((r & 0xFF) == 0, true,
            ((a & 0xF) - (b & 0xF)) < 0,
            r < 0);
        return r & 0xFF;
    }

    private int sbc8(int a, int b) {
        int c = regs.isC() ? 1 : 0;
        int r = a - b - c;
        regs.setFlags((r & 0xFF) == 0, true,
            ((a & 0xF) - (b & 0xF) - c) < 0,
            r < 0);
        return r & 0xFF;
    }

    private void and8(int v) {
        regs.A &= v;
        regs.setFlags(regs.A == 0, false, true, false);
    }

    private void xor8(int v) {
        regs.A ^= v;
        regs.setFlags(regs.A == 0, false, false, false);
    }

    private void or8(int v) {
        regs.A |= v;
        regs.setFlags(regs.A == 0, false, false, false);
    }

    private void cp8(int v) {
        int r = regs.A - v;
        regs.setFlags((r & 0xFF) == 0, true,
            ((regs.A & 0xF) - (v & 0xF)) < 0,
            r < 0);
    }

    private int inc8(int v) {
        int r = (v + 1) & 0xFF;
        regs.setZ(r == 0);
        regs.setN(false);
        regs.setH((v & 0xF) == 0xF);
        return r;
    }

    private int dec8(int v) {
        int r = (v - 1) & 0xFF;
        regs.setZ(r == 0);
        regs.setN(true);
        regs.setH((v & 0xF) == 0x0);
        return r;
    }

    private int addHL(int v) {
        int hl = regs.getHL();
        int r  = hl + v;
        regs.setN(false);
        regs.setH(((hl & 0xFFF) + (v & 0xFFF)) > 0xFFF);
        regs.setC(r > 0xFFFF);
        return r & 0xFFFF;
    }

    // ── CB-prefix bit operations ─────────────────────────────────────────────

    private int rlc(int v) {
        int r = ((v << 1) | (v >> 7)) & 0xFF;
        regs.setFlags(r == 0, false, false, (v & 0x80) != 0);
        return r;
    }

    private int rrc(int v) {
        int r = ((v >> 1) | ((v & 1) << 7)) & 0xFF;
        regs.setFlags(r == 0, false, false, (v & 0x01) != 0);
        return r;
    }

    private int rl(int v) {
        int c = regs.isC() ? 1 : 0;
        int r = ((v << 1) | c) & 0xFF;
        regs.setFlags(r == 0, false, false, (v & 0x80) != 0);
        return r;
    }

    private int rr(int v) {
        int c = regs.isC() ? 0x80 : 0;
        int r = ((v >> 1) | c) & 0xFF;
        regs.setFlags(r == 0, false, false, (v & 0x01) != 0);
        return r;
    }

    private int sla(int v) {
        int r = (v << 1) & 0xFF;
        regs.setFlags(r == 0, false, false, (v & 0x80) != 0);
        return r;
    }

    private int sra(int v) {
        int r = ((v >> 1) | (v & 0x80)) & 0xFF;
        regs.setFlags(r == 0, false, false, (v & 0x01) != 0);
        return r;
    }

    private int swap(int v) {
        int r = ((v & 0x0F) << 4) | ((v & 0xF0) >> 4);
        regs.setFlags(r == 0, false, false, false);
        return r;
    }

    private int srl(int v) {
        int r = (v >> 1) & 0xFF;
        regs.setFlags(r == 0, false, false, (v & 0x01) != 0);
        return r;
    }

    private void bit(int bit, int v) {
        regs.setZ(((v >> bit) & 1) == 0);
        regs.setN(false);
        regs.setH(true);
    }

    // ── Register by index (for CB ops) ───────────────────────────────────────

    private int getReg(int idx) {
        return switch (idx) {
            case 0 -> regs.B; case 1 -> regs.C; case 2 -> regs.D;
            case 3 -> regs.E; case 4 -> regs.H; case 5 -> regs.L;
            case 6 -> bus.read(regs.getHL());
            case 7 -> regs.A;
            default -> 0;
        };
    }

    private void setReg(int idx, int v) {
        switch (idx) {
            case 0 -> regs.B = v; case 1 -> regs.C = v;
            case 2 -> regs.D = v; case 3 -> regs.E = v;
            case 4 -> regs.H = v; case 5 -> regs.L = v;
            case 6 -> bus.write(regs.getHL(), v);
            case 7 -> regs.A = v;
        }
    }

    // ── CB prefix ───────────────────────────────────────────────────────────

    private int executeCB() {
        int op  = fetchByte();
        int reg = op & 0x07;
        int v   = getReg(reg);
        boolean isHL = (reg == 6);
        int cycles   = isHL ? 16 : 8;

        if (op < 0x08)      { setReg(reg, rlc(v));  }
        else if (op < 0x10) { setReg(reg, rrc(v));  }
        else if (op < 0x18) { setReg(reg, rl(v));   }
        else if (op < 0x20) { setReg(reg, rr(v));   }
        else if (op < 0x28) { setReg(reg, sla(v));  }
        else if (op < 0x30) { setReg(reg, sra(v));  }
        else if (op < 0x38) { setReg(reg, swap(v)); }
        else if (op < 0x40) { setReg(reg, srl(v));  }
        else if (op < 0x80) {
            // BIT
            bit((op - 0x40) >> 3, v);
            cycles = isHL ? 12 : 8;
        } else if (op < 0xC0) {
            // RES
            setReg(reg, v & ~(1 << ((op - 0x80) >> 3)));
        } else {
            // SET
            setReg(reg, v | (1 << ((op - 0xC0) >> 3)));
        }
        return cycles;
    }

    // ── Main execute ─────────────────────────────────────────────────────────

    @SuppressWarnings("all")
    private int execute(int op) {
        int n, nn, v, r;

        switch (op) {
            // ── 0x00 – 0x0F ─────────────────────────────────────────────────
            case 0x00: return 4;   // NOP
            case 0x01: regs.setBC(fetchWord()); return 12;
            case 0x02: bus.write(regs.getBC(), regs.A); return 8;
            case 0x03: regs.setBC((regs.getBC() + 1) & 0xFFFF); return 8;
            case 0x04: regs.B = inc8(regs.B); return 4;
            case 0x05: regs.B = dec8(regs.B); return 4;
            case 0x06: regs.B = fetchByte(); return 8;
            case 0x07: {    // RLCA
                int ca = (regs.A & 0x80) != 0 ? 1 : 0;
                regs.A = ((regs.A << 1) | ca) & 0xFF;
                regs.setFlags(false, false, false, ca == 1);
                return 4;
            }
            case 0x08: nn = fetchWord(); bus.write(nn, regs.SP & 0xFF); bus.write(nn+1, regs.SP >> 8); return 20;
            case 0x09: regs.setHL(addHL(regs.getBC())); return 8;
            case 0x0A: regs.A = bus.read(regs.getBC()); return 8;
            case 0x0B: regs.setBC((regs.getBC() - 1) & 0xFFFF); return 8;
            case 0x0C: regs.C = inc8(regs.C); return 4;
            case 0x0D: regs.C = dec8(regs.C); return 4;
            case 0x0E: regs.C = fetchByte(); return 8;
            case 0x0F: {    // RRCA
                int ca = regs.A & 0x01;
                regs.A = ((regs.A >> 1) | (ca << 7)) & 0xFF;
                regs.setFlags(false, false, false, ca == 1);
                return 4;
            }

            // ── 0x10 – 0x1F ─────────────────────────────────────────────────
            case 0x10: return 4;   // STOP
            case 0x11: regs.setDE(fetchWord()); return 12;
            case 0x12: bus.write(regs.getDE(), regs.A); return 8;
            case 0x13: regs.setDE((regs.getDE() + 1) & 0xFFFF); return 8;
            case 0x14: regs.D = inc8(regs.D); return 4;
            case 0x15: regs.D = dec8(regs.D); return 4;
            case 0x16: regs.D = fetchByte(); return 8;
            case 0x17: {    // RLA
                int ca = regs.isC() ? 1 : 0;
                int nc = (regs.A & 0x80) != 0 ? 1 : 0;
                regs.A = ((regs.A << 1) | ca) & 0xFF;
                regs.setFlags(false, false, false, nc == 1);
                return 4;
            }
            case 0x18: n = (byte) fetchByte(); regs.PC = (regs.PC + n) & 0xFFFF; return 12;
            case 0x19: regs.setHL(addHL(regs.getDE())); return 8;
            case 0x1A: regs.A = bus.read(regs.getDE()); return 8;
            case 0x1B: regs.setDE((regs.getDE() - 1) & 0xFFFF); return 8;
            case 0x1C: regs.E = inc8(regs.E); return 4;
            case 0x1D: regs.E = dec8(regs.E); return 4;
            case 0x1E: regs.E = fetchByte(); return 8;
            case 0x1F: {    // RRA
                int ca = regs.isC() ? 0x80 : 0;
                int nc = regs.A & 0x01;
                regs.A = ((regs.A >> 1) | ca) & 0xFF;
                regs.setFlags(false, false, false, nc == 1);
                return 4;
            }

            // ── 0x20 – 0x2F ─────────────────────────────────────────────────
            case 0x20: n = (byte) fetchByte(); if (!regs.isZ()) { regs.PC = (regs.PC+n)&0xFFFF; return 12; } return 8;
            case 0x21: regs.setHL(fetchWord()); return 12;
            case 0x22: bus.write(regs.getHL(), regs.A); regs.setHL((regs.getHL()+1)&0xFFFF); return 8;
            case 0x23: regs.setHL((regs.getHL()+1)&0xFFFF); return 8;
            case 0x24: regs.H = inc8(regs.H); return 4;
            case 0x25: regs.H = dec8(regs.H); return 4;
            case 0x26: regs.H = fetchByte(); return 8;
            case 0x27: {    // DAA
                int a = regs.A;
                if (!regs.isN()) {
                    if (regs.isH() || (a & 0x0F) > 9) a += 0x06;
                    if (regs.isC() || a > 0x99) { a += 0x60; regs.setC(true); }
                } else {
                    if (regs.isH()) a -= 0x06;
                    if (regs.isC()) a -= 0x60;
                }
                regs.A = a & 0xFF;
                regs.setZ(regs.A == 0);
                regs.setH(false);
                return 4;
            }
            case 0x28: n = (byte) fetchByte(); if (regs.isZ())  { regs.PC = (regs.PC+n)&0xFFFF; return 12; } return 8;
            case 0x29: regs.setHL(addHL(regs.getHL())); return 8;
            case 0x2A: regs.A = bus.read(regs.getHL()); regs.setHL((regs.getHL()+1)&0xFFFF); return 8;
            case 0x2B: regs.setHL((regs.getHL()-1)&0xFFFF); return 8;
            case 0x2C: regs.L = inc8(regs.L); return 4;
            case 0x2D: regs.L = dec8(regs.L); return 4;
            case 0x2E: regs.L = fetchByte(); return 8;
            case 0x2F: regs.A ^= 0xFF; regs.setN(true); regs.setH(true); return 4; // CPL

            // ── 0x30 – 0x3F ─────────────────────────────────────────────────
            case 0x30: n = (byte) fetchByte(); if (!regs.isC()) { regs.PC = (regs.PC+n)&0xFFFF; return 12; } return 8;
            case 0x31: regs.SP = fetchWord(); return 12;
            case 0x32: bus.write(regs.getHL(), regs.A); regs.setHL((regs.getHL()-1)&0xFFFF); return 8;
            case 0x33: regs.SP = (regs.SP + 1) & 0xFFFF; return 8;
            case 0x34: v = inc8(bus.read(regs.getHL())); bus.write(regs.getHL(), v); return 12;
            case 0x35: v = dec8(bus.read(regs.getHL())); bus.write(regs.getHL(), v); return 12;
            case 0x36: bus.write(regs.getHL(), fetchByte()); return 12;
            case 0x37: regs.setN(false); regs.setH(false); regs.setC(true); return 4; // SCF
            case 0x38: n = (byte) fetchByte(); if (regs.isC())  { regs.PC = (regs.PC+n)&0xFFFF; return 12; } return 8;
            case 0x39: regs.setHL(addHL(regs.SP)); return 8;
            case 0x3A: regs.A = bus.read(regs.getHL()); regs.setHL((regs.getHL()-1)&0xFFFF); return 8;
            case 0x3B: regs.SP = (regs.SP - 1) & 0xFFFF; return 8;
            case 0x3C: regs.A = inc8(regs.A); return 4;
            case 0x3D: regs.A = dec8(regs.A); return 4;
            case 0x3E: regs.A = fetchByte(); return 8;
            case 0x3F: regs.setN(false); regs.setH(false); regs.setC(!regs.isC()); return 4; // CCF

            // ── 0x40-0x7F: LD r,r ───────────────────────────────────────────
            case 0x40: return 4; case 0x41: regs.B = regs.C; return 4;
            case 0x42: regs.B = regs.D; return 4; case 0x43: regs.B = regs.E; return 4;
            case 0x44: regs.B = regs.H; return 4; case 0x45: regs.B = regs.L; return 4;
            case 0x46: regs.B = bus.read(regs.getHL()); return 8;
            case 0x47: regs.B = regs.A; return 4;
            case 0x48: regs.C = regs.B; return 4; case 0x49: return 4;
            case 0x4A: regs.C = regs.D; return 4; case 0x4B: regs.C = regs.E; return 4;
            case 0x4C: regs.C = regs.H; return 4; case 0x4D: regs.C = regs.L; return 4;
            case 0x4E: regs.C = bus.read(regs.getHL()); return 8;
            case 0x4F: regs.C = regs.A; return 4;
            case 0x50: regs.D = regs.B; return 4; case 0x51: regs.D = regs.C; return 4;
            case 0x52: return 4; case 0x53: regs.D = regs.E; return 4;
            case 0x54: regs.D = regs.H; return 4; case 0x55: regs.D = regs.L; return 4;
            case 0x56: regs.D = bus.read(regs.getHL()); return 8;
            case 0x57: regs.D = regs.A; return 4;
            case 0x58: regs.E = regs.B; return 4; case 0x59: regs.E = regs.C; return 4;
            case 0x5A: regs.E = regs.D; return 4; case 0x5B: return 4;
            case 0x5C: regs.E = regs.H; return 4; case 0x5D: regs.E = regs.L; return 4;
            case 0x5E: regs.E = bus.read(regs.getHL()); return 8;
            case 0x5F: regs.E = regs.A; return 4;
            case 0x60: regs.H = regs.B; return 4; case 0x61: regs.H = regs.C; return 4;
            case 0x62: regs.H = regs.D; return 4; case 0x63: regs.H = regs.E; return 4;
            case 0x64: return 4; case 0x65: regs.H = regs.L; return 4;
            case 0x66: regs.H = bus.read(regs.getHL()); return 8;
            case 0x67: regs.H = regs.A; return 4;
            case 0x68: regs.L = regs.B; return 4; case 0x69: regs.L = regs.C; return 4;
            case 0x6A: regs.L = regs.D; return 4; case 0x6B: regs.L = regs.E; return 4;
            case 0x6C: regs.L = regs.H; return 4; case 0x6D: return 4;
            case 0x6E: regs.L = bus.read(regs.getHL()); return 8;
            case 0x6F: regs.L = regs.A; return 4;
            case 0x70: bus.write(regs.getHL(), regs.B); return 8;
            case 0x71: bus.write(regs.getHL(), regs.C); return 8;
            case 0x72: bus.write(regs.getHL(), regs.D); return 8;
            case 0x73: bus.write(regs.getHL(), regs.E); return 8;
            case 0x74: bus.write(regs.getHL(), regs.H); return 8;
            case 0x75: bus.write(regs.getHL(), regs.L); return 8;
            case 0x76: {    // HALT
                if (!irq.IME && irq.hasPending()) haltBug = true;
                else halted = true;
                return 4;
            }
            case 0x77: bus.write(regs.getHL(), regs.A); return 8;
            case 0x78: regs.A = regs.B; return 4; case 0x79: regs.A = regs.C; return 4;
            case 0x7A: regs.A = regs.D; return 4; case 0x7B: regs.A = regs.E; return 4;
            case 0x7C: regs.A = regs.H; return 4; case 0x7D: regs.A = regs.L; return 4;
            case 0x7E: regs.A = bus.read(regs.getHL()); return 8;
            case 0x7F: return 4;

            // ── 0x80 – 0xBF: ALU r ──────────────────────────────────────────
            case 0x80: regs.A = add8(regs.A, regs.B); return 4;
            case 0x81: regs.A = add8(regs.A, regs.C); return 4;
            case 0x82: regs.A = add8(regs.A, regs.D); return 4;
            case 0x83: regs.A = add8(regs.A, regs.E); return 4;
            case 0x84: regs.A = add8(regs.A, regs.H); return 4;
            case 0x85: regs.A = add8(regs.A, regs.L); return 4;
            case 0x86: regs.A = add8(regs.A, bus.read(regs.getHL())); return 8;
            case 0x87: regs.A = add8(regs.A, regs.A); return 4;
            case 0x88: regs.A = adc8(regs.A, regs.B); return 4;
            case 0x89: regs.A = adc8(regs.A, regs.C); return 4;
            case 0x8A: regs.A = adc8(regs.A, regs.D); return 4;
            case 0x8B: regs.A = adc8(regs.A, regs.E); return 4;
            case 0x8C: regs.A = adc8(regs.A, regs.H); return 4;
            case 0x8D: regs.A = adc8(regs.A, regs.L); return 4;
            case 0x8E: regs.A = adc8(regs.A, bus.read(regs.getHL())); return 8;
            case 0x8F: regs.A = adc8(regs.A, regs.A); return 4;
            case 0x90: regs.A = sub8(regs.A, regs.B); return 4;
            case 0x91: regs.A = sub8(regs.A, regs.C); return 4;
            case 0x92: regs.A = sub8(regs.A, regs.D); return 4;
            case 0x93: regs.A = sub8(regs.A, regs.E); return 4;
            case 0x94: regs.A = sub8(regs.A, regs.H); return 4;
            case 0x95: regs.A = sub8(regs.A, regs.L); return 4;
            case 0x96: regs.A = sub8(regs.A, bus.read(regs.getHL())); return 8;
            case 0x97: regs.A = sub8(regs.A, regs.A); return 4;
            case 0x98: regs.A = sbc8(regs.A, regs.B); return 4;
            case 0x99: regs.A = sbc8(regs.A, regs.C); return 4;
            case 0x9A: regs.A = sbc8(regs.A, regs.D); return 4;
            case 0x9B: regs.A = sbc8(regs.A, regs.E); return 4;
            case 0x9C: regs.A = sbc8(regs.A, regs.H); return 4;
            case 0x9D: regs.A = sbc8(regs.A, regs.L); return 4;
            case 0x9E: regs.A = sbc8(regs.A, bus.read(regs.getHL())); return 8;
            case 0x9F: regs.A = sbc8(regs.A, regs.A); return 4;
            case 0xA0: and8(regs.B); return 4; case 0xA1: and8(regs.C); return 4;
            case 0xA2: and8(regs.D); return 4; case 0xA3: and8(regs.E); return 4;
            case 0xA4: and8(regs.H); return 4; case 0xA5: and8(regs.L); return 4;
            case 0xA6: and8(bus.read(regs.getHL())); return 8;
            case 0xA7: and8(regs.A); return 4;
            case 0xA8: xor8(regs.B); return 4; case 0xA9: xor8(regs.C); return 4;
            case 0xAA: xor8(regs.D); return 4; case 0xAB: xor8(regs.E); return 4;
            case 0xAC: xor8(regs.H); return 4; case 0xAD: xor8(regs.L); return 4;
            case 0xAE: xor8(bus.read(regs.getHL())); return 8;
            case 0xAF: xor8(regs.A); return 4;
            case 0xB0: or8(regs.B); return 4; case 0xB1: or8(regs.C); return 4;
            case 0xB2: or8(regs.D); return 4; case 0xB3: or8(regs.E); return 4;
            case 0xB4: or8(regs.H); return 4; case 0xB5: or8(regs.L); return 4;
            case 0xB6: or8(bus.read(regs.getHL())); return 8;
            case 0xB7: or8(regs.A); return 4;
            case 0xB8: cp8(regs.B); return 4; case 0xB9: cp8(regs.C); return 4;
            case 0xBA: cp8(regs.D); return 4; case 0xBB: cp8(regs.E); return 4;
            case 0xBC: cp8(regs.H); return 4; case 0xBD: cp8(regs.L); return 4;
            case 0xBE: cp8(bus.read(regs.getHL())); return 8;
            case 0xBF: cp8(regs.A); return 4;

            // ── 0xC0 – 0xFF: Control flow ────────────────────────────────────
            case 0xC0: if (!regs.isZ()) { regs.PC = pop(); return 20; } return 8;
            case 0xC1: regs.setBC(pop()); return 12;
            case 0xC2: nn = fetchWord(); if (!regs.isZ()) { regs.PC = nn; return 16; } return 12;
            case 0xC3: regs.PC = fetchWord(); return 16;
            case 0xC4: nn = fetchWord(); if (!regs.isZ()) { push(regs.PC); regs.PC = nn; return 24; } return 12;
            case 0xC5: push(regs.getBC()); return 16;
            case 0xC6: regs.A = add8(regs.A, fetchByte()); return 8;
            case 0xC7: push(regs.PC); regs.PC = 0x00; return 16;
            case 0xC8: if (regs.isZ())  { regs.PC = pop(); return 20; } return 8;
            case 0xC9: regs.PC = pop(); return 16;
            case 0xCA: nn = fetchWord(); if (regs.isZ())  { regs.PC = nn; return 16; } return 12;
            case 0xCB: return executeCB();
            case 0xCC: nn = fetchWord(); if (regs.isZ())  { push(regs.PC); regs.PC = nn; return 24; } return 12;
            case 0xCD: nn = fetchWord(); push(regs.PC); regs.PC = nn; return 24;
            case 0xCE: regs.A = adc8(regs.A, fetchByte()); return 8;
            case 0xCF: push(regs.PC); regs.PC = 0x08; return 16;
            case 0xD0: if (!regs.isC()) { regs.PC = pop(); return 20; } return 8;
            case 0xD1: regs.setDE(pop()); return 12;
            case 0xD2: nn = fetchWord(); if (!regs.isC()) { regs.PC = nn; return 16; } return 12;
            case 0xD4: nn = fetchWord(); if (!regs.isC()) { push(regs.PC); regs.PC = nn; return 24; } return 12;
            case 0xD5: push(regs.getDE()); return 16;
            case 0xD6: regs.A = sub8(regs.A, fetchByte()); return 8;
            case 0xD7: push(regs.PC); regs.PC = 0x10; return 16;
            case 0xD8: if (regs.isC())  { regs.PC = pop(); return 20; } return 8;
            case 0xD9: regs.PC = pop(); irq.IME = true; return 16;  // RETI
            case 0xDA: nn = fetchWord(); if (regs.isC())  { regs.PC = nn; return 16; } return 12;
            case 0xDC: nn = fetchWord(); if (regs.isC())  { push(regs.PC); regs.PC = nn; return 24; } return 12;
            case 0xDE: regs.A = sbc8(regs.A, fetchByte()); return 8;
            case 0xDF: push(regs.PC); regs.PC = 0x18; return 16;
            case 0xE0: bus.write(0xFF00 | fetchByte(), regs.A); return 12;
            case 0xE1: regs.setHL(pop()); return 12;
            case 0xE2: bus.write(0xFF00 | regs.C, regs.A); return 8;
            case 0xE5: push(regs.getHL()); return 16;
            case 0xE6: and8(fetchByte()); return 8;
            case 0xE7: push(regs.PC); regs.PC = 0x20; return 16;
            case 0xE8: {    // ADD SP, e
                int e = (byte) fetchByte();
                regs.setFlags(false, false,
                    ((regs.SP & 0xF)  + (e & 0xF))  > 0xF,
                    ((regs.SP & 0xFF) + (e & 0xFF)) > 0xFF);
                regs.SP = (regs.SP + e) & 0xFFFF;
                return 16;
            }
            case 0xE9: regs.PC = regs.getHL(); return 4;
            case 0xEA: bus.write(fetchWord(), regs.A); return 16;
            case 0xEE: xor8(fetchByte()); return 8;
            case 0xEF: push(regs.PC); regs.PC = 0x28; return 16;
            case 0xF0: regs.A = bus.read(0xFF00 | fetchByte()); return 12;
            case 0xF1: regs.setAF(pop()); return 12;
            case 0xF2: regs.A = bus.read(0xFF00 | regs.C); return 8;
            case 0xF3: irq.IME = false; irq.IMEPending = false; return 4; // DI
            case 0xF5: push(regs.getAF()); return 16;
            case 0xF6: or8(fetchByte()); return 8;
            case 0xF7: push(regs.PC); regs.PC = 0x30; return 16;
            case 0xF8: {    // LD HL, SP+e
                int e = (byte) fetchByte();
                regs.setFlags(false, false,
                    ((regs.SP & 0xF)  + (e & 0xF))  > 0xF,
                    ((regs.SP & 0xFF) + (e & 0xFF)) > 0xFF);
                regs.setHL((regs.SP + e) & 0xFFFF);
                return 12;
            }
            case 0xF9: regs.SP = regs.getHL(); return 8;
            case 0xFA: regs.A = bus.read(fetchWord()); return 16;
            case 0xFB: irq.IMEPending = true; return 4; // EI
            case 0xFE: cp8(fetchByte()); return 8;
            case 0xFF: push(regs.PC); regs.PC = 0x38; return 16;

            default:
                // Undefined opcode – treat as NOP
                System.out.printf("[CPU] Undefined opcode 0x%02X at PC=0x%04X%n", op, (regs.PC - 1) & 0xFFFF);
                return 4;
        }
    }

    // ── Save / Load state ────────────────────────────────────────────────────

    public void captureState(com.emulator.savestate.SaveData d) {
        d.A = regs.A; d.F = regs.F;
        d.B = regs.B; d.C = regs.C;
        d.D = regs.D; d.E = regs.E;
        d.H = regs.H; d.L = regs.L;
        d.SP = regs.SP; d.PC = regs.PC;
        d.IME    = irq.IME;
        d.IE     = irq.IE;
        d.IF     = irq.IF;
        d.halted = halted;
    }

    public void applyState(com.emulator.savestate.SaveData d) {
        regs.A = d.A; regs.F = d.F;
        regs.B = d.B; regs.C = d.C;
        regs.D = d.D; regs.E = d.E;
        regs.H = d.H; regs.L = d.L;
        regs.SP = d.SP; regs.PC = d.PC;
        irq.IME = d.IME;
        irq.IE  = d.IE;
        irq.IF  = d.IF;
        halted  = d.halted;
        haltBug = false;
        irq.IMEPending = false;
    }
}
