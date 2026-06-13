package com.emulator.gba.cpu;

import com.emulator.gba.memory.GBAMemory;

/**
 * ARM7TDMI CPU — Game Boy Advance.
 * 16.78 MHz, ARM32 + THUMB16 instruction sets.
 *
 * Modes: USR(0x10) FIQ(0x11) IRQ(0x12) SVC(0x13) ABT(0x17) UND(0x1B) SYS(0x1F)
 * Registers: r0-r12 shared, r13(SP)/r14(LR)/r15(PC) banked per mode, CPSR/SPSR
 */
public class ARM7TDMI {

    // ── Registros ─────────────────────────────────────────────────────────────
    public final int[] r = new int[16];   // r0-r12, r13(SP), r14(LR), r15(PC)
    public int cpsr = 0xD3;               // Supervisor mode, IRQ/FIQ disabled
    public int spsr = 0;

    // Banked registers per mode
    private final int[] r13_irq = new int[1], r14_irq = new int[1], spsr_irq = new int[1];
    private final int[] r13_svc = new int[1], r14_svc = new int[1], spsr_svc = new int[1];
    private final int[] r13_usr = new int[1], r14_usr = new int[1];
    private final int[] r8_fiq  = new int[5], r13_fiq = new int[1],
                         r14_fiq = new int[1], spsr_fiq = new int[1];

    // ── Flags en CPSR ─────────────────────────────────────────────────────────
    private static final int N = 1 << 31, Z = 1 << 30, C = 1 << 29, V = 1 << 28;
    private static final int T = 1 << 5,  I = 1 << 7,  F = 1 << 6;

    // Modos
    private static final int M_USR=0x10, M_FIQ=0x11, M_IRQ=0x12,
                               M_SVC=0x13, M_ABT=0x17, M_UND=0x1B, M_SYS=0x1F;

    // Excepciones — vector addresses
    private static final int VEC_RESET=0x00, VEC_UND=0x04, VEC_SWI=0x08,
                               VEC_PAB=0x0C,  VEC_DAB=0x10, VEC_IRQ=0x18, VEC_FIQ=0x1C;

    private final GBAMemory mem;
    private boolean halted = false;

    // Pipeline
    private int fetchedARM = 0;
    private boolean pipelineFilled = false;

    public ARM7TDMI(GBAMemory mem) {
        this.mem = mem;
        reset();
    }

    public void reset() {
        for (int i = 0; i < 16; i++) r[i] = 0;
        r[13] = 0x03007F00;  // IWRAM stack top (user mode)
        r[15] = 0x08000000;  // ROM entry point
        cpsr  = 0x5F;         // System mode, FIQ/IRQ enabled
        halted = false;
        pipelineFilled = false;
    }

    // ── Step — returns cycles consumed ────────────────────────────────────────
    public int step() {
        // Handle IRQ
        if ((cpsr & I) == 0 && mem.hasPendingIRQ()) {
            triggerException(VEC_IRQ, M_IRQ);
            halted = false;
            return 4;
        }
        if (halted) return 4;

        if (isThumb()) return stepThumb();
        else           return stepARM();
    }

    private boolean isThumb() { return (cpsr & T) != 0; }

    // ─────────────────────────────────────────────────────────────────────────
    // ARM32 execution
    // ─────────────────────────────────────────────────────────────────────────
    private int stepARM() {
        int pc   = r[15] - 4;
        int inst = mem.read32(pc & ~3);
        r[15] += 4;
        if (!checkCond(inst >>> 28)) return 4;
        return execARM(inst);
    }

    private boolean checkCond(int cond) {
        boolean n = (cpsr & N) != 0, z = (cpsr & Z) != 0,
                c = (cpsr & C) != 0, v = (cpsr & V) != 0;
        return switch (cond) {
            case 0  ->  z;
            case 1  -> !z;
            case 2  ->  c;
            case 3  -> !c;
            case 4  ->  n;
            case 5  -> !n;
            case 6  ->  v;
            case 7  -> !v;
            case 8  ->  c && !z;
            case 9  -> !c || z;
            case 10 ->  n == v;
            case 11 ->  n != v;
            case 12 -> !z && (n == v);
            case 13 ->  z || (n != v);
            case 14 ->  true;
            default ->  false;
        };
    }

    @SuppressWarnings("all")
    private int execARM(int inst) {
        int type = (inst >> 25) & 7;
        switch (type) {
            case 0, 1 -> {  // Data processing / Multiply / PSR transfer
                if ((inst & 0x0FC000F0) == 0x00000090) return armMultiply(inst);
                if ((inst & 0x0FB00FF0) == 0x01000090) return armSwap(inst);
                if ((inst & 0x0E400F90) == 0x00000090) return armHalfwordTransfer(inst);
                if ((inst & 0x0FBF0FFF) == 0x010F0000) return armMRS(inst);
                if ((inst & 0x0FB0FFF0) == 0x0120F000) return armMSR(inst);
                return armDataProc(inst);
            }
            case 2, 3 -> { return armSDT(inst); }   // Single data transfer
            case 4    -> { return armBDT(inst); }   // Block data transfer
            case 5    -> { return armBranch(inst); } // Branch
            case 6    -> { return armCoProc(inst); } // Coprocessor
            case 7    -> {
                if ((inst & 0x0F000000) == 0x0F000000) return armSWI(inst);
                return armCoProc(inst);
            }
        }
        return 4;
    }

    // ARM: Data Processing
    private int armDataProc(int inst) {
        int opcode = (inst >> 21) & 0xF;
        int s      = (inst >> 20) & 1;
        int rn     = (inst >> 16) & 0xF;
        int rd     = (inst >> 12) & 0xF;
        boolean imm = (inst & 0x02000000) != 0;

        long op1 = r[rn]; if (rn == 15) op1 = r[15] + 4;
        int[] shiftResult = imm ? armImmShift(inst) : armRegShift(inst);
        long op2 = shiftResult[0];
        int  shiftCarry = shiftResult[1];

        long result = 0;
        boolean setC = false, setV = false;

        switch (opcode) {
            case 0x0 -> result = op1 & op2;                      // AND
            case 0x1 -> result = op1 ^ op2;                      // EOR
            case 0x2 -> { result = op1 - op2; setC=true; setV=true; }  // SUB
            case 0x3 -> { result = op2 - op1; setC=true; setV=true; }  // RSB
            case 0x4 -> { result = op1 + op2; setC=true; setV=true; }  // ADD
            case 0x5 -> { result = op1 + op2 + ((cpsr&C)!=0?1:0); setC=true; setV=true; } // ADC
            case 0x6 -> { result = op1 - op2 - ((cpsr&C)!=0?0:1); setC=true; setV=true; } // SBC
            case 0x7 -> { result = op2 - op1 - ((cpsr&C)!=0?0:1); setC=true; setV=true; } // RSC
            case 0x8 -> { result = op1 & op2; s=1; rd=-1; }     // TST
            case 0x9 -> { result = op1 ^ op2; s=1; rd=-1; }     // TEQ
            case 0xA -> { result = op1 - op2; setC=true; setV=true; s=1; rd=-1; } // CMP
            case 0xB -> { result = op1 + op2; setC=true; setV=true; s=1; rd=-1; } // CMN
            case 0xC -> result = op1 | op2;                      // ORR
            case 0xD -> result = op2;                             // MOV
            case 0xE -> result = op1 & ~op2;                     // BIC
            case 0xF -> result = ~op2;                            // MVN
        }

        if (s != 0) {
            if (rd == 15) {
                cpsr = spsr; return 4;
            }
            boolean n = (result & 0x80000000L) != 0;
            boolean z = (result & 0xFFFFFFFFL) == 0;
            boolean c = setC ? (opcode >= 2 && opcode <= 7
                ? (opcode == 2 || opcode == 6 ? op1 >= op2 : result > 0xFFFFFFFFL)
                : shiftCarry != 0) : ((cpsr & C) != 0);
            boolean v = setV && (opcode >= 2 && opcode <= 7
                ? ((op1 ^ op2 ^ result) & 0x80000000L) == 0 && ((op1 ^ result) & 0x80000000L) != 0
                : (cpsr & V) != 0);
            setFlags(n, z, c, v);
        }
        if (rd >= 0) {
            r[rd] = (int)(result & 0xFFFFFFFFL);
            if (rd == 15) { r[15] = r[15] & (isThumb() ? ~1 : ~3); pipelineFilled = false; return 6; }
        }
        return 4;
    }

    private int[] armImmShift(int inst) {
        int imm = inst & 0xFF, rot = ((inst >> 8) & 0xF) * 2;
        int val = Integer.rotateRight(imm, rot);
        return new int[]{val, rot != 0 ? (val >> 31) & 1 : ((cpsr & C) != 0 ? 1 : 0)};
    }

    private int[] armRegShift(int inst) {
        int rm = inst & 0xF; int val = r[rm]; if (rm == 15) val += 4;
        int type = (inst >> 5) & 3;
        int amount;
        if ((inst & 0x10) != 0) amount = r[(inst >> 8) & 0xF] & 0xFF;
        else                     amount = (inst >> 7) & 0x1F;
        if (amount == 0) return new int[]{val, (cpsr & C) != 0 ? 1 : 0};
        int carry = 0;
        switch (type) {
            case 0 -> { carry = amount < 32 ? (val >> (32-amount)) & 1 : 0; val = amount < 32 ? val << amount : 0; }
            case 1 -> { carry = amount < 32 ? (val >>> (amount-1)) & 1 : 0; val = amount < 32 ? val >>> amount : 0; }
            case 2 -> { carry = (val >> (Math.min(amount,32)-1)) & 1; val = val >> Math.min(amount,31); }
            case 3 -> { carry = (val >> (amount-1)) & 1; val = Integer.rotateRight(val, amount); }
        }
        return new int[]{val, carry};
    }

    // ARM: Multiply
    private int armMultiply(int inst) {
        int rd = (inst >> 16)&0xF, rn = (inst>>12)&0xF, rs = (inst>>8)&0xF, rm = inst&0xF;
        boolean acc = (inst & 0x00200000) != 0, s = (inst & 0x00100000) != 0;
        long result = (long)r[rm] * r[rs];
        if (acc) result += r[rn];
        r[rd] = (int)result;
        if (s) setFlags((result & 0x80000000L)!=0, result==0, (cpsr&C)!=0, (cpsr&V)!=0);
        return 4;
    }

    // ARM: Single Data Transfer (LDR/STR)
    private int armSDT(int inst) {
        int rn = (inst>>16)&0xF, rd = (inst>>12)&0xF;
        boolean pre  = (inst & 0x01000000) != 0;
        boolean up   = (inst & 0x00800000) != 0;
        boolean byte_= (inst & 0x00400000) != 0;
        boolean wb   = (inst & 0x00200000) != 0;
        boolean load = (inst & 0x00100000) != 0;

        int base = r[rn]; if (rn == 15) base += 4;
        int[] off = (inst & 0x02000000) != 0 ? armRegShift(inst) : new int[]{inst & 0xFFF, 0};
        int offset = up ? off[0] : -off[0];
        int addr = pre ? base + offset : base;

        if (load) {
            r[rd] = byte_ ? mem.read8(addr) : Integer.rotateRight(mem.read32(addr), (addr & 3) * 8);
            if (rd == 15) { r[15] &= ~3; pipelineFilled = false; return 8; }
        } else {
            int val = r[rd]; if (rd == 15) val += 4;
            if (byte_) mem.write8(addr, val); else mem.write32(addr, val);
        }
        if (wb || !pre) r[rn] = pre ? base + offset : addr + offset;
        return 4;
    }

    // ARM: Block Data Transfer (LDM/STM)
    private int armBDT(int inst) {
        int rn = (inst>>16)&0xF;
        boolean pre = (inst&0x01000000)!=0, up=(inst&0x00800000)!=0,
                s=(inst&0x00400000)!=0, wb=(inst&0x00200000)!=0, load=(inst&0x00100000)!=0;
        int regs = inst & 0xFFFF;
        int base = r[rn], addr = base;
        if (!up) { int cnt = Integer.bitCount(regs); addr = base - cnt*4 + (pre?-4:0); }
        else     { addr = pre ? base + 4 : base; }

        int firstAddr = up ? (pre ? base+4 : base) : (base - Integer.bitCount(regs)*4 + (pre?0:4));
        addr = firstAddr;

        for (int i = 0; i < 16; i++) {
            if ((regs & (1 << i)) == 0) continue;
            if (load) { r[i] = mem.read32(addr); if (i == 15) { r[15] &= ~3; pipelineFilled = false; } }
            else      { int val = r[i]; if (i==15) val+=4; mem.write32(addr, val); }
            addr += 4;
        }
        if (wb) r[rn] = up ? base + Integer.bitCount(regs)*4 : base - Integer.bitCount(regs)*4;
        if (load && s && (regs & 0x8000) != 0) cpsr = spsr;
        return 4;
    }

    // ARM: Branch / Branch-Link
    private int armBranch(int inst) {
        boolean link = (inst & 0x01000000) != 0;
        int offset = (inst << 8) >> 6;   // sign-extend and *4
        if (link) r[14] = r[15] - 4;
        r[15] += offset;
        pipelineFilled = false;
        return 4;
    }

    private int armSwap(int inst) {
        int rn=(inst>>16)&0xF, rd=(inst>>12)&0xF, rm=inst&0xF;
        boolean byte_=(inst&0x00400000)!=0;
        int addr=r[rn];
        if (byte_) { int tmp=mem.read8(addr); mem.write8(addr,r[rm]); r[rd]=tmp; }
        else       { int tmp=mem.read32(addr); mem.write32(addr,r[rm]); r[rd]=tmp; }
        return 4;
    }

    private int armHalfwordTransfer(int inst) {
        int rn=(inst>>16)&0xF, rd=(inst>>12)&0xF;
        boolean pre=(inst&0x01000000)!=0, up=(inst&0x00800000)!=0,
                imm=(inst&0x00400000)!=0, wb=(inst&0x00200000)!=0, load=(inst&0x00100000)!=0;
        int type=(inst>>5)&3;
        int off = imm ? (((inst>>8)&0xF)<<4)|(inst&0xF) : r[inst&0xF];
        int base=r[rn], addr=pre?(up?base+off:base-off):base;
        if (load) {
            r[rd] = switch(type) {
                case 1 -> mem.read16(addr);
                case 2 -> (byte)mem.read8(addr);
                case 3 -> (short)mem.read16(addr);
                default -> 0;
            };
        } else mem.write16(addr, r[rd]);
        if (wb||!pre) r[rn]=pre?(up?base+off:base-off):(up?base+off:base-off);
        return 4;
    }

    private int armMRS(int inst) {
        int rd=(inst>>12)&0xF;
        r[rd] = (inst&0x00400000)!=0 ? spsr : cpsr;
        return 4;
    }

    private int armMSR(int inst) {
        boolean spsr_=(inst&0x00400000)!=0;
        int val = (inst&0x02000000)!=0 ? armImmShift(inst)[0] : r[inst&0xF];
        int mask = 0;
        if ((inst&0x00080000)!=0) mask |= 0xFF000000;
        if ((inst&0x00010000)!=0) mask |= 0x000000FF;
        if (spsr_) spsr = (spsr & ~mask) | (val & mask);
        else       cpsr = (cpsr & ~mask) | (val & mask);
        return 4;
    }

    private int armSWI(int inst) { return triggerException(VEC_SWI, M_SVC); }
    private int armCoProc(int inst) { return 4; }

    // ─────────────────────────────────────────────────────────────────────────
    // THUMB execution
    // ─────────────────────────────────────────────────────────────────────────
    private int stepThumb() {
        int pc   = r[15] - 2;
        int inst = mem.read16(pc & ~1) & 0xFFFF;
        r[15] += 2;
        return execThumb(inst);
    }

    @SuppressWarnings("all")
    private int execThumb(int inst) {
        int op = inst >> 13;
        switch (op) {
            case 0 -> { // Shift / ADD / SUB immediate
                int op2 = (inst >> 11) & 3;
                if (op2 < 3) { // LSL/LSR/ASR
                    int rd = inst & 7, rs = (inst>>3)&7, amt = (inst>>6)&0x1F;
                    int val = r[rs]; int carry;
                    switch(op2) {
                        case 0 -> { carry=amt>0?(val>>(32-amt))&1:((cpsr&C)!=0?1:0); val=amt>0?val<<amt:val; }
                        case 1 -> { carry=amt>0?(val>>>(amt-1))&1:((cpsr&C)!=0?1:0); val=amt>0?val>>>amt:0; }
                        default -> { carry=(val>>(Math.min(amt,32)-1))&1; val=val>>Math.min(amt,31); }
                    }
                    r[rd]=val; setFlags((val>>31)!=0,val==0,carry!=0,(cpsr&V)!=0);
                } else { // ADD/SUB
                    int rd=inst&7, rs=(inst>>3)&7;
                    boolean sub=(inst&0x200)!=0, imm=(inst&0x400)!=0;
                    int op1=r[rs], op2v=imm?((inst>>6)&7):r[(inst>>6)&7];
                    int res=sub?op1-op2v:op1+op2v;
                    r[rd]=res; setFlags((res>>31)!=0,res==0,sub?op1>=op2v:((long)op1+op2v)>0xFFFFFFFFL,
                        ((op1^op2v^res)&0x80000000)==0&&((op1^res)&0x80000000)!=0);
                }
            }
            case 1 -> { // MOV/CMP/ADD/SUB #imm8
                int op2=(inst>>11)&3, rd=(inst>>8)&7, imm=inst&0xFF;
                int base=r[rd];
                switch(op2) {
                    case 0 -> { r[rd]=imm; setFlags(false,imm==0,(cpsr&C)!=0,(cpsr&V)!=0); }
                    case 1 -> { int res=base-imm; setFlags((res>>31)!=0,res==0,base>=imm,((base^imm^res)&0x80000000)==0&&((base^res)&0x80000000)!=0); }
                    case 2 -> { int res=base+imm; r[rd]=res; setFlags((res>>31)!=0,res==0,((long)base+imm)>0xFFFFFFFFL,((base^~imm^res)&0x80000000)==0&&((base^res)&0x80000000)!=0); }
                    case 3 -> { int res=base-imm; r[rd]=res; setFlags((res>>31)!=0,res==0,base>=imm,((base^imm^res)&0x80000000)==0&&((base^res)&0x80000000)!=0); }
                }
            }
            case 2 -> { // ALU / Hi-reg / PC-relative load / Load/Store
                int sub = (inst >> 10) & 7;
                if (sub < 2) { // ALU
                    thumbALU(inst);
                } else if (sub == 2 || sub == 3) { // Hi reg ops / BX
                    int op2=(inst>>8)&3, rs=(inst>>3)&0xF, rd=(inst&7)|((inst>>4)&8);
                    switch(op2) {
                        case 0 -> r[rd] += r[rs];
                        case 1 -> { int res=r[rd]-r[rs]; setFlags((res>>31)!=0,res==0,r[rd]>=(r[rs]&0xFFFFFFFFL),((r[rd]^r[rs]^res)&0x80000000)==0&&((r[rd]^res)&0x80000000)!=0); }
                        case 2 -> { r[rd]=r[rs]; if(rd==15){r[15]&=~1;pipelineFilled=false;} }
                        case 3 -> { // BX/BLX
                            int target=r[rs]; boolean thumb=(target&1)!=0;
                            if ((inst&0x80)!=0) r[14]=r[15]-1; // BLX
                            r[15]=target&(thumb?~1:~3);
                            if(thumb) cpsr|=T; else cpsr&=~T;
                            pipelineFilled=false;
                        }
                    }
                } else if (sub == 4 || sub == 5) { // PC-relative LDR
                    int rd=(inst>>8)&7;
                    r[rd]=mem.read32((r[15]&~3)+((inst&0xFF)<<2));
                } else { // Load/Store register offset
                    int opcode=(inst>>9)&7, ro=(inst>>6)&7, rb=(inst>>3)&7, rd=inst&7;
                    int addr=r[rb]+r[ro];
                    switch(opcode){
                        case 0->mem.write32(addr,r[rd]);case 1->mem.write16(addr,r[rd]);
                        case 2->mem.write8(addr,r[rd]);case 3->r[rd]=(byte)mem.read8(addr);
                        case 4->r[rd]=mem.read32(addr);case 5->r[rd]=mem.read16(addr);
                        case 6->r[rd]=mem.read8(addr);case 7->r[rd]=(short)mem.read16(addr);
                    }
                }
            }
            case 3 -> { // Load/Store word/byte immediate
                int l=(inst>>11)&1,b=(inst>>12)&1,off=((inst>>6)&0x1F)<<(b!=0?0:2);
                int rb=(inst>>3)&7,rd=inst&7,addr=r[rb]+off;
                if(l!=0) r[rd]=b!=0?mem.read8(addr):mem.read32(addr);
                else if(b!=0) mem.write8(addr,r[rd]); else mem.write32(addr,r[rd]);
            }
            case 4 -> {
                if((inst&0x1000)!=0){ // SP-relative load/store
                    int l=(inst>>11)&1,rd=(inst>>8)&7,off=(inst&0xFF)<<2;
                    int addr=r[13]+off;
                    if(l!=0) r[rd]=mem.read32(addr); else mem.write32(addr,r[rd]);
                } else { // Load/Store halfword
                    int l=(inst>>11)&1,off=((inst>>6)&0x1F)<<1,rb=(inst>>3)&7,rd=inst&7;
                    int addr=r[rb]+off;
                    if(l!=0) r[rd]=mem.read16(addr); else mem.write16(addr,r[rd]);
                }
            }
            case 5 -> {
                int sub2=(inst>>11)&3;
                if(sub2==0||sub2==1){ // ADD PC/SP
                    int rd=(inst>>8)&7;
                    boolean spRel=(sub2==1);
                    r[rd]=(spRel?r[13]:((r[15]+2)&~3))+((inst&0xFF)<<2);
                } else if(sub2==2){ // MISC: PUSH/POP/ADD SP
                    int sub3=(inst>>8)&0xF;
                    if(sub3==0){ r[13]+=(inst&0x7F)<<2*(((inst>>7)&1)!=0?-1:1); } // ADD SP,#n
                    else if(sub3==4||sub3==5){ // PUSH
                        int regs=inst&0xFF; if((inst&0x0100)!=0) regs|=0x4000; // include LR
                        for(int i=15;i>=0;i--) if((regs&(1<<i))!=0){r[13]-=4;mem.write32(r[13],r[i]);}
                    } else if(sub3==12||sub3==13){ // POP
                        int regs=inst&0xFF; if((inst&0x0100)!=0) regs|=0x8000; // include PC
                        for(int i=0;i<16;i++) if((regs&(1<<i))!=0){r[i]=mem.read32(r[13]);r[13]+=4;}
                        if((regs&0x8000)!=0){r[15]&=~1;pipelineFilled=false;}
                    }
                } else { // LDMIA/STMIA
                    int l=(inst>>11)&1,rb=(inst>>8)&7,regs=inst&0xFF;
                    for(int i=0;i<8;i++) if((regs&(1<<i))!=0){
                        if(l!=0){r[i]=mem.read32(r[rb]);r[rb]+=4;}
                        else{mem.write32(r[rb],r[i]);r[rb]+=4;}
                    }
                }
            }
            case 6 -> { // Conditional branch / SWI
                int cond=(inst>>8)&0xF;
                if(cond==0xF){ triggerException(VEC_SWI, M_SVC); }
                else if(cond==0xE){ /* BKPT */ }
                else if(checkCond(cond)){
                    int off=(byte)(inst&0xFF);
                    r[15]+=(off<<1); pipelineFilled=false;
                }
            }
            case 7 -> { // Unconditional branch / BL/BLX
                if((inst&0x1800)==0){ // B
                    int off=((inst&0x3FF)<<1); if((inst&0x400)!=0) off|=0xFFFFF800;
                    r[15]+=off; pipelineFilled=false;
                } else if((inst&0x1000)==0){ // BL/BLX prefix
                    int off=((inst&0x7FF)<<12); if((inst&0x400)!=0) off|=0xFF800000;
                    r[14]=r[15]+off;
                } else { // BL/BLX suffix
                    int target=r[14]+((inst&0x7FF)<<1);
                    boolean blx=(inst&0x1800)==0x1800;
                    r[14]=(r[15]-2)|1;
                    r[15]=blx?(target&~3):(target&~1);
                    if(blx) cpsr&=~T; else {}
                    pipelineFilled=false;
                }
            }
        }
        return 2;
    }

    private void thumbALU(int inst) {
        int op=(inst>>6)&0xF, rd=inst&7, rs=(inst>>3)&7;
        int a=r[rd], b=r[rs]; long res;
        boolean c=(cpsr&C)!=0, v=(cpsr&V)!=0;
        switch(op){
            case 0->{ res=a&b; r[rd]=(int)res; }
            case 1->{ res=a^b; r[rd]=(int)res; }
            case 2->{ int amt=b&0xFF; c=amt>0&&(a>>(32-amt)&1)!=0; res=a<<amt; r[rd]=(int)res; }
            case 3->{ int amt=b&0xFF; c=amt>0&&(a>>>(amt-1)&1)!=0; res=amt<32?a>>>amt:0; r[rd]=(int)res; }
            case 4->{ int amt=b&0xFF; c=amt>0&&(a>>(Math.min(amt,32)-1)&1)!=0; res=a>>Math.min(amt,31); r[rd]=(int)res; }
            case 5->{ res=(long)a*b; r[rd]=(int)res; res&=0xFFFFFFFFL; }
            case 6->{ res=a&b; r[rd]=(int)res; c=((cpsr&C)!=0); } // AND with BIC-style, actually BIC
            case 7->{ res=a^b; r[rd]=(int)res; } // keep, adjusted below
            case 8->{ res=a+b+(c?1:0); r[rd]=(int)res; c=res>0xFFFFFFFFL; v=((a^~b^(int)res)&0x80000000)==0&&((a^(int)res)&0x80000000)!=0; }
            case 9->{ res=(long)a+b; r[rd]=(int)res; c=res>0xFFFFFFFFL; v=((a^~b^(int)res)&0x80000000)==0&&((a^(int)res)&0x80000000)!=0; }
            case 10->{ res=(long)a-b; c=a>=b; v=((a^b^(int)res)&0x80000000)==0&&((a^(int)res)&0x80000000)!=0; }  // CMP
            case 11->{ res=a|b; r[rd]=(int)res; } // CMN ← actually ORR
            case 12->{ res=Integer.rotateRight(a,b&0x1F); r[rd]=(int)res; c=((res>>31)&1)!=0; }
            case 13->{ res=~b; r[rd]=(int)res; } // MUL simplified to MVN, fix below
            case 14->{ res=a&~b; r[rd]=(int)res; } // BIC
            case 15->{ res=~b; r[rd]=(int)res; } // MVN
            default->{ res=0; }
        }
        // Fix ops that were simplified above
        switch(op){
            case 5->{ res=(long)r[rd]; c=false; v=false; }
            case 13->{ long m=(long)a*b; r[rd]=(int)m; res=m; }
        }
        // All ALU ops except shifts update N,Z flags; TST-style (CMP/CMN) don't write rd
        if(op!=8&&op!=10) setFlags(((int)res>>31)!=0,(int)res==0,c,v);
        else              setFlags(((long)(a-b)>>31)!=0,(int)(a-b)==0,a>=(b&0xFFFFFFFFL),v);
    }

    // ── Exception handling ────────────────────────────────────────────────────
    private int triggerException(int vector, int newMode) {
        int savedCPSR = cpsr;
        int savedPC   = r[15] + (isThumb() ? 0 : 4);
        switchMode(newMode);
        spsr = savedCPSR;
        r[14] = savedPC;
        r[15] = vector;
        cpsr = (cpsr & ~T) | I;
        if (newMode == M_FIQ) cpsr |= F;
        pipelineFilled = false;
        return 4;
    }

    private void switchMode(int newMode) {
        int curMode = cpsr & 0x1F;
        // Save banked regs for current mode
        if (curMode == M_IRQ) { r13_irq[0]=r[13]; r14_irq[0]=r[14]; }
        else if (curMode == M_SVC) { r13_svc[0]=r[13]; r14_svc[0]=r[14]; }
        else if (curMode == M_USR || curMode == M_SYS) { r13_usr[0]=r[13]; r14_usr[0]=r[14]; }
        // Switch mode in CPSR
        cpsr = (cpsr & ~0x1F) | (newMode & 0x1F);
        // Restore banked regs for new mode
        if (newMode == M_IRQ) { r[13]=r13_irq[0]; r[14]=r14_irq[0]; spsr=spsr_irq[0]; }
        else if (newMode == M_SVC) { r[13]=r13_svc[0]; r[14]=r14_svc[0]; spsr=spsr_svc[0]; }
        else if (newMode == M_USR || newMode == M_SYS) { r[13]=r13_usr[0]; r[14]=r14_usr[0]; }
    }

    // ── Flags ─────────────────────────────────────────────────────────────────
    private void setFlags(boolean n, boolean z, boolean c, boolean v) {
        cpsr = (cpsr & ~(N|Z|C|V))
             | (n ? N : 0) | (z ? Z : 0) | (c ? C : 0) | (v ? V : 0);
    }

    public void setHalted(boolean h) { halted = h; }
    public boolean isHalted()        { return halted; }
    public int getPC()                { return r[15]; }
}
