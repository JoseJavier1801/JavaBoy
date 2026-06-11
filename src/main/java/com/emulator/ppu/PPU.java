package com.emulator.ppu;

import com.emulator.GBCMode;
import com.emulator.cpu.Interrupts;
import com.emulator.memory.MemoryBus;
import com.emulator.savestate.SaveData;

import java.util.function.Consumer;

/**
 * PPU — Game Boy (DMG) + Game Boy Color (GBC).
 *
 * Fixes v3:
 *  - Paletas GBC inicializadas a blanco (sin boot ROM los juegos las escriben
 *    pero si empiezan a 0 todo sale negro antes de que llegue la primera
 *    escritura de paleta).
 *  - LCDC bit0 en GBC solo controla prioridad BG, NO visibilidad: el BG
 *    se dibuja siempre en GBC aunque bit0 sea 0.
 *  - writeIO usa bloques {} en cada case para evitar fall-through.
 *  - VRAM/OAM no se bloquean en modo 3/2 (permisivo): muchos juegos GBC
 *    escriben fuera de los intervalos exactos del hardware.
 *  - Sprite bank GBC: bit3 del atributo OAM = banco VRAM (no del mapa).
 */
public class PPU {

    public static final int WIDTH  = 160;
    public static final int HEIGHT = 144;

    // Paleta verde DMG clásica
    private static final int[] DMG_COLORS = {
        0xFF9BBC0F, 0xFF8BAC0F, 0xFF306230, 0xFF0F380F
    };

    // ── Modo hardware ─────────────────────────────────────────────────────────
    private GBCMode gbcMode = GBCMode.DMG;

    public void setMode(GBCMode m) {
        this.gbcMode = m;
        if (m == GBCMode.GBC) initGBCPalettes();
    }

    /**
     * Inicializa todas las paletas GBC a blanco opaco (0x7FFF en RGB555).
     * Sin boot ROM el juego escribe sus paletas antes del primer frame,
     * pero si el valor inicial es 0 (negro) cualquier frame temprano
     * aparece completamente negro.
     */
    private void initGBCPalettes() {
        // 0xFF 0x7F = RGB555 blanco (R=31 G=31 B=31)
        for (int i = 0; i < 64; i += 2) {
            bgPalData[i]  = 0xFF; bgPalData[i+1]  = 0x7F;
            objPalData[i] = 0xFF; objPalData[i+1] = 0x7F;
        }
        bgPalDirty = true; objPalDirty = true;
    }

    // ── VRAM — 2 bancos × 8 KB ────────────────────────────────────────────────
    private final int[][] vram = new int[2][0x2000];
    private int vramBank = 0;

    // ── OAM ───────────────────────────────────────────────────────────────────
    private final int[] oam = new int[0xA0];

    // ── GBC color palettes ────────────────────────────────────────────────────
    private final int[] bgPalData  = new int[64];
    private final int[] objPalData = new int[64];
    private int bcps = 0, ocps = 0;
    private int opri = 0;

    private final int[][] bgArgb  = new int[8][4];
    private final int[][] objArgb = new int[8][4];
    private boolean bgPalDirty = true, objPalDirty = true;

    // ── Registros LCD ─────────────────────────────────────────────────────────
    private int LCDC = 0x91;
    private int STAT = 0x85;
    private int SCY = 0, SCX = 0;
    private int LY  = 0, LYC = 0;
    private int BGP = 0xFC, OBP0 = 0xFF, OBP1 = 0xFF;
    private int WY  = 0, WX  = 0;

    // ── Estado PPU ────────────────────────────────────────────────────────────
    private int  ppuMode    = 1;
    private int  cycleAcc   = 0;
    private int  windowLine = 0;

    // ── Framebuffer ───────────────────────────────────────────────────────────
    private final int[]     framebuffer = new int[WIDTH * HEIGHT];
    private final int[]     bgColIdx    = new int[WIDTH];    // índice color BG por pixel
    private final boolean[] bgPriBit    = new boolean[WIDTH];// bit prioridad BG attr GBC

    private Consumer<int[]> frameCallback;
    private MemoryBus bus;

    public void setFrameCallback(Consumer<int[]> cb) { frameCallback = cb; }

    // ── Step ──────────────────────────────────────────────────────────────────
    public void step(int cycles, MemoryBus bus) {
        this.bus = bus;
        if ((LCDC & 0x80) == 0) { LY = 0; ppuMode = 0; cycleAcc = 0; return; }

        cycleAcc += cycles;

        switch (ppuMode) {
            case 2 -> { if (cycleAcc >= 80)  { cycleAcc -= 80;  setPPUMode(3); } }
            case 3 -> { if (cycleAcc >= 172) { cycleAcc -= 172; renderLine(); setPPUMode(0); } }
            case 0 -> {
                if (cycleAcc >= 204) {
                    cycleAcc -= 204;
                    if (bus != null) bus.onHBlank();
                    LY++;
                    checkLYC();
                    if (LY == 144) {
                        setPPUMode(1);
                        if (frameCallback != null) frameCallback.accept(framebuffer.clone());
                        if (bus != null) bus.requestInterrupt(Interrupts.INT_VBLANK);
                        windowLine = 0;
                    } else {
                        setPPUMode(2);
                    }
                }
            }
            case 1 -> {
                if (cycleAcc >= 456) {
                    cycleAcc -= 456; LY++; checkLYC();
                    if (LY > 153) { LY = 0; setPPUMode(2); }
                }
            }
        }
    }

    private void setPPUMode(int m) {
        ppuMode = m;
        STAT = (STAT & 0xFC) | m;
        boolean irq = switch (m) {
            case 0 -> (STAT & 0x08) != 0;
            case 1 -> (STAT & 0x10) != 0;
            case 2 -> (STAT & 0x20) != 0;
            default -> false;
        };
        if (irq && bus != null) bus.requestInterrupt(Interrupts.INT_STAT);
    }

    private void checkLYC() {
        boolean match = (LY == LYC);
        if (match) STAT |= 0x04; else STAT &= ~0x04;
        if (match && (STAT & 0x40) != 0 && bus != null)
            bus.requestInterrupt(Interrupts.INT_STAT);
    }

    // ── Dispatch render ───────────────────────────────────────────────────────
    private void renderLine() {
        if (gbcMode == GBCMode.GBC) {
            rebuildGBCPalettes();
            renderBGGBC();
            renderWindowGBC();
            renderSpritesGBC();
        } else {
            renderBGDMG();
            if ((LCDC & 0x20) != 0) renderWindowDMG();
            if ((LCDC & 0x02) != 0) renderSpritesDMG();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DMG rendering
    // ─────────────────────────────────────────────────────────────────────────

    private void renderBGDMG() {
        if ((LCDC & 0x01) == 0) {
            for (int x = 0; x < WIDTH; x++) framebuffer[LY * WIDTH + x] = DMG_COLORS[0];
            return;
        }
        int tileMap  = (LCDC & 0x08) != 0 ? 0x9C00 : 0x9800;
        int tileData = (LCDC & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signed = (LCDC & 0x10) == 0;
        int y = (LY + SCY) & 0xFF, tileRow = (y / 8) * 32, lineY = y % 8;
        for (int x = 0; x < WIDTH; x++) {
            int xOff = (x + SCX) & 0xFF;
            int idx  = vram[0][tileMap - 0x8000 + tileRow + xOff / 8];
            if (signed) idx = (byte) idx;
            int ta  = tileAddr(tileData, idx, signed);
            int lo  = vram[0][ta + lineY * 2], hi = vram[0][ta + lineY * 2 + 1];
            int bit = 7 - (xOff & 7);
            int col = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);
            bgColIdx[x] = col;
            framebuffer[LY * WIDTH + x] = DMG_COLORS[(BGP >> (col * 2)) & 3];
        }
    }

    private void renderWindowDMG() {
        if (LY < WY || WX > 166) return;
        int tileMap  = (LCDC & 0x40) != 0 ? 0x9C00 : 0x9800;
        int tileData = (LCDC & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signed = (LCDC & 0x10) == 0;
        int lineY = windowLine & 7, tileRow = (windowLine / 8) * 32;
        for (int x = 0; x < WIDTH; x++) {
            int wx = x - (WX - 7); if (wx < 0) continue;
            int idx = vram[0][tileMap - 0x8000 + tileRow + wx / 8];
            if (signed) idx = (byte) idx;
            int ta  = tileAddr(tileData, idx, signed);
            int lo  = vram[0][ta + lineY * 2], hi = vram[0][ta + lineY * 2 + 1];
            int bit = 7 - (wx & 7);
            int col = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);
            bgColIdx[x] = col;
            framebuffer[LY * WIDTH + x] = DMG_COLORS[(BGP >> (col * 2)) & 3];
        }
        windowLine++;
    }

    private void renderSpritesDMG() {
        int sh = (LCDC & 0x04) != 0 ? 16 : 8;
        int count = 0;
        for (int i = 0; i < 40 && count < 10; i++) {
            int sy = oam[i*4] - 16, sx = oam[i*4+1] - 8;
            int tile = oam[i*4+2] & (sh == 16 ? 0xFE : 0xFF);
            int attr = oam[i*4+3];
            if (LY < sy || LY >= sy + sh) continue;
            count++;
            int lineY = LY - sy;
            if ((attr & 0x40) != 0) lineY = sh - 1 - lineY;
            int ta = tile * 16 + lineY * 2;
            int lo = vram[0][ta], hi = vram[0][ta + 1];
            int[] pal = buildDMGObjPal((attr & 0x10) != 0 ? OBP1 : OBP0);
            boolean xFlip = (attr & 0x20) != 0, behind = (attr & 0x80) != 0;
            for (int bit = 0; bit < 8; bit++) {
                int px = sx + (xFlip ? bit : 7 - bit);
                if (px < 0 || px >= WIDTH) continue;
                int col = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);
                if (col == 0) continue;
                if (behind && bgColIdx[px] != 0) continue;
                framebuffer[LY * WIDTH + px] = pal[col];
            }
        }
    }

    private int[] buildDMGObjPal(int reg) {
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) p[i] = DMG_COLORS[(reg >> (i * 2)) & 3];
        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GBC rendering
    // ─────────────────────────────────────────────────────────────────────────

    private void rebuildGBCPalettes() {
        if (bgPalDirty) {
            for (int p = 0; p < 8; p++)
                for (int c = 0; c < 4; c++)
                    bgArgb[p][c] = rgb555toARGB(bgPalData[p*8 + c*2], bgPalData[p*8 + c*2 + 1]);
            bgPalDirty = false;
        }
        if (objPalDirty) {
            for (int p = 0; p < 8; p++)
                for (int c = 0; c < 4; c++)
                    objArgb[p][c] = rgb555toARGB(objPalData[p*8 + c*2], objPalData[p*8 + c*2 + 1]);
            objPalDirty = false;
        }
    }

    /** RGB555 little-endian → ARGB int */
    private static int rgb555toARGB(int lo, int hi) {
        int v = (lo & 0xFF) | ((hi & 0xFF) << 8);
        int r = (v & 0x1F) << 3;
        int g = ((v >> 5) & 0x1F) << 3;
        int b = ((v >> 10) & 0x1F) << 3;
        // Expansión de 5 a 8 bits replicando los 3 MSB
        r |= r >> 5; g |= g >> 5; b |= b >> 5;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void renderBGGBC() {
        // En GBC LCDC bit0=0 solo deshabilita la prioridad BG sobre sprites,
        // NO la visibilidad del BG. El BG siempre se renderiza.
        boolean bgMasterPri = (LCDC & 0x01) != 0;

        int tileMap  = (LCDC & 0x08) != 0 ? 0x9C00 : 0x9800;
        int tileData = (LCDC & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signed = (LCDC & 0x10) == 0;
        int y = (LY + SCY) & 0xFF, tileRow = (y / 8) * 32, lineY = y & 7;

        for (int x = 0; x < WIDTH; x++) {
            int xOff   = (x + SCX) & 0xFF;
            int mapOff = tileMap - 0x8000 + tileRow + xOff / 8;

            int tileIdx = vram[0][mapOff];
            if (signed) tileIdx = (byte) tileIdx;

            // Atributos GBC del banco 1
            int attr   = vram[1][mapOff];
            int palNum = attr & 0x07;
            int vBank  = (attr >> 3) & 0x01;
            boolean xfl = (attr & 0x20) != 0;
            boolean yfl = (attr & 0x40) != 0;
            boolean pri = (attr & 0x80) != 0;

            int ty  = yfl ? (7 - lineY) : lineY;
            int ta  = tileAddr(tileData, tileIdx, signed);
            int lo  = vram[vBank][ta + ty * 2];
            int hi  = vram[vBank][ta + ty * 2 + 1];
            int bit = xfl ? (xOff & 7) : (7 - (xOff & 7));
            int col = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);

            bgColIdx[x]  = col;
            bgPriBit[x]  = pri && bgMasterPri;
            framebuffer[LY * WIDTH + x] = bgArgb[palNum][col];
        }
    }

    private void renderWindowGBC() {
        if ((LCDC & 0x20) == 0 || LY < WY || WX > 166) return;

        boolean bgMasterPri = (LCDC & 0x01) != 0;
        int tileMap  = (LCDC & 0x40) != 0 ? 0x9C00 : 0x9800;
        int tileData = (LCDC & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signed = (LCDC & 0x10) == 0;
        int lineY = windowLine & 7, tileRow = (windowLine / 8) * 32;

        for (int x = 0; x < WIDTH; x++) {
            int wx = x - (WX - 7); if (wx < 0) continue;
            int mapOff  = tileMap - 0x8000 + tileRow + wx / 8;
            int tileIdx = vram[0][mapOff]; if (signed) tileIdx = (byte) tileIdx;
            int attr    = vram[1][mapOff];
            int palNum  = attr & 0x07, vBank = (attr >> 3) & 0x01;
            boolean xfl = (attr & 0x20) != 0, yfl = (attr & 0x40) != 0, pri = (attr & 0x80) != 0;
            int ty  = yfl ? (7 - lineY) : lineY;
            int ta  = tileAddr(tileData, tileIdx, signed);
            int lo  = vram[vBank][ta + ty * 2], hi = vram[vBank][ta + ty * 2 + 1];
            int bit = xfl ? (wx & 7) : (7 - (wx & 7));
            int col = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);
            bgColIdx[x]  = col;
            bgPriBit[x]  = pri && bgMasterPri;
            framebuffer[LY * WIDTH + x] = bgArgb[palNum][col];
        }
        windowLine++;
    }

    private void renderSpritesGBC() {
        if ((LCDC & 0x02) == 0) return;   // sprites deshabilitados
        int sh = (LCDC & 0x04) != 0 ? 16 : 8;
        int count = 0;
        for (int i = 0; i < 40 && count < 10; i++) {
            int sy   = oam[i*4]   - 16;
            int sx   = oam[i*4+1] - 8;
            int tile = oam[i*4+2] & (sh == 16 ? 0xFE : 0xFF);
            int attr = oam[i*4+3];
            if (LY < sy || LY >= sy + sh) continue;
            count++;
            int lineY = LY - sy;
            if ((attr & 0x40) != 0) lineY = sh - 1 - lineY;
            // En GBC bit3 del atributo OAM = banco VRAM del tile sprite
            int vBank  = (attr >> 3) & 0x01;
            int palNum = attr & 0x07;
            int ta     = tile * 16 + lineY * 2;
            int lo     = vram[vBank][ta], hi = vram[vBank][ta + 1];
            boolean xfl    = (attr & 0x20) != 0;
            boolean behind = (attr & 0x80) != 0;

            for (int bit = 0; bit < 8; bit++) {
                int px = sx + (xfl ? bit : 7 - bit);
                if (px < 0 || px >= WIDTH) continue;
                int col = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);
                if (col == 0) continue;   // transparente
                // Prioridad GBC: BG gana si (bgPriBit activo O sprite behind) Y bgCol != 0
                if ((bgPriBit[px] || behind) && bgColIdx[px] != 0) continue;
                framebuffer[LY * WIDTH + px] = objArgb[palNum][col];
            }
        }
    }

    /** Calcula dirección de tile en VRAM[0] */
    private static int tileAddr(int tileData, int idx, boolean signed) {
        if (signed) return (tileData - 0x8000) + (idx + 128) * 16;
        return (tileData - 0x8000) + idx * 16;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VRAM / OAM access — permisivo (sin bloqueo en modo 3/2)
    // Los juegos GBC escriben fuera de los intervalos exactos del HW.
    // ─────────────────────────────────────────────────────────────────────────

    public int readVRAM(int addr) {
        // Solo bloquear estrictamente en modo 3 para DMG; GBC es permisivo
        if (ppuMode == 3 && gbcMode == GBCMode.DMG) return 0xFF;
        return vram[vramBank][addr - 0x8000];
    }

    public void writeVRAM(int addr, int val) {
        if (ppuMode == 3 && gbcMode == GBCMode.DMG) return;
        vram[vramBank][addr - 0x8000] = val;
    }

    public int readOAM(int addr) {
        if ((ppuMode == 2 || ppuMode == 3) && gbcMode == GBCMode.DMG) return 0xFF;
        return oam[addr - 0xFE00];
    }

    public void writeOAM(int addr, int val) {
        if ((ppuMode == 2 || ppuMode == 3) && gbcMode == GBCMode.DMG) return;
        oam[addr - 0xFE00] = val;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // I/O registers
    // ─────────────────────────────────────────────────────────────────────────

    public int readIO(int addr) {
        return switch (addr) {
            case 0xFF40 -> LCDC;
            case 0xFF41 -> STAT | 0x80;
            case 0xFF42 -> SCY;
            case 0xFF43 -> SCX;
            case 0xFF44 -> LY;
            case 0xFF45 -> LYC;
            case 0xFF47 -> BGP;
            case 0xFF48 -> OBP0;
            case 0xFF49 -> OBP1;
            case 0xFF4A -> WY;
            case 0xFF4B -> WX;
            default -> 0xFF;
        };
    }

    public void writeIO(int addr, int val) {
        switch (addr) {
            case 0xFF40 -> { LCDC = val; if ((val & 0x80) == 0) { LY = 0; ppuMode = 0; } }
            case 0xFF41 -> { STAT = (STAT & 0x07) | (val & 0x78); }
            case 0xFF42 -> { SCY = val; }
            case 0xFF43 -> { SCX = val; }
            case 0xFF45 -> { LYC = val; checkLYC(); }
            case 0xFF47 -> { BGP  = val; }
            case 0xFF48 -> { OBP0 = val; }
            case 0xFF49 -> { OBP1 = val; }
            case 0xFF4A -> { WY = val; }
            case 0xFF4B -> { WX = val; }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GBC registers
    // ─────────────────────────────────────────────────────────────────────────

    public int  readVBK()       { return vramBank | 0xFE; }
    public void writeVBK(int v) { vramBank = v & 0x01; }

    public int  readBCPS()      { return bcps | 0x40; }
    public void writeBCPS(int v){ bcps = v; }
    public int  readBCPD()      { return bgPalData[bcps & 0x3F]; }
    public void writeBCPD(int v){
        bgPalData[bcps & 0x3F] = v & 0xFF;
        if ((bcps & 0x80) != 0) bcps = ((bcps + 1) & 0x3F) | 0x80;
        bgPalDirty = true;
    }

    public int  readOCPS()      { return ocps | 0x40; }
    public void writeOCPS(int v){ ocps = v; }
    public int  readOCPD()      { return objPalData[ocps & 0x3F]; }
    public void writeOCPD(int v){
        objPalData[ocps & 0x3F] = v & 0xFF;
        if ((ocps & 0x80) != 0) ocps = ((ocps + 1) & 0x3F) | 0x80;
        objPalDirty = true;
    }

    public int  readOPRI()      { return opri | 0xFE; }
    public void writeOPRI(int v){ opri = v & 0x01; }

    // ─────────────────────────────────────────────────────────────────────────
    // Save state
    // ─────────────────────────────────────────────────────────────────────────

    public void captureState(SaveData d) {
        d.LCDC = LCDC; d.STAT = STAT; d.SCY = SCY; d.SCX = SCX;
        d.LY   = LY;   d.LYC  = LYC;
        d.BGP  = BGP;  d.OBP0 = OBP0; d.OBP1 = OBP1;
        d.WY   = WY;   d.WX   = WX;
        d.ppuMode = ppuMode; d.ppuCycles = cycleAcc; d.windowLine = windowLine;
        d.vram = new int[vram[0].length + vram[1].length];
        System.arraycopy(vram[0], 0, d.vram, 0,               vram[0].length);
        System.arraycopy(vram[1], 0, d.vram, vram[0].length,  vram[1].length);
        d.oam  = oam.clone();
    }

    public void applyState(SaveData d) {
        LCDC = d.LCDC; STAT = d.STAT; SCY = d.SCY; SCX = d.SCX;
        LY   = d.LY;   LYC  = d.LYC;
        BGP  = d.BGP;  OBP0 = d.OBP0; OBP1 = d.OBP1;
        WY   = d.WY;   WX   = d.WX;
        ppuMode = d.ppuMode; cycleAcc = d.ppuCycles; windowLine = d.windowLine;
        if (d.vram != null) {
            int half = vram[0].length;
            System.arraycopy(d.vram, 0, vram[0], 0, Math.min(half, d.vram.length));
            if (d.vram.length > half)
                System.arraycopy(d.vram, half, vram[1], 0, Math.min(half, d.vram.length - half));
        }
        if (d.oam != null)
            System.arraycopy(d.oam, 0, oam, 0, Math.min(d.oam.length, oam.length));
        bgPalDirty = true; objPalDirty = true;
    }
}
