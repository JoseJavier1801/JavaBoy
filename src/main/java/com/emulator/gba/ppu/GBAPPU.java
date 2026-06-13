package com.emulator.gba.ppu;

import com.emulator.gba.memory.GBAMemory;
import java.util.function.Consumer;

/**
 * GBA PPU — 240×160 pixels, 6 BG modes, sprites.
 *
 * Timing: 280896 cycles/frame = 16.78 MHz / 59.727 fps
 *   H-draw:  1006 cycles (240 px)
 *   H-blank:  226 cycles
 *   V-draw:  160 lines
 *   V-blank:   68 lines
 */
public class GBAPPU {

    public static final int WIDTH  = 240;
    public static final int HEIGHT = 160;

    // ── VRAM regions ──────────────────────────────────────────────────────────
    private final int[] palette = new int[0x400];   // 1 KB palette RAM (RGB555)
    private final int[] vram    = new int[0x18000]; // 96 KB
    private final int[] oam     = new int[0x400];   // 1 KB OAM

    // ── LCD registers ─────────────────────────────────────────────────────────
    private int DISPCNT  = 0;
    private int DISPSTAT = 0;
    private int VCOUNT   = 0;
    private final int[] BGCNT  = new int[4];
    private final int[] BGHOFS = new int[4];
    private final int[] BGVOFS = new int[4];
    private final int[] BGX    = new int[2];  // BG2/BG3 reference X (affine)
    private final int[] BGY    = new int[2];  // BG2/BG3 reference Y
    private final int[] BGB    = new int[2];  // rotation B
    private final int[] BGC    = new int[2];  // rotation C
    private int BLDCNT = 0, BLDALPHA = 0, BLDY = 0;
    private final int[] WIN0   = new int[4];
    private final int[] WIN1   = new int[4];
    private int WININ = 0, WINOUT = 0;

    // ── State machine ─────────────────────────────────────────────────────────
    private int  cycleAcc   = 0;
    private boolean hblank  = false;
    private boolean vblank  = false;

    private final int[] framebuffer = new int[WIDTH * HEIGHT];
    private Consumer<int[]> frameCallback;
    private GBAMemory mem;

    public void setFrameCallback(Consumer<int[]> cb) { frameCallback = cb; }
    public void setMemory(GBAMemory m)               { mem = m; }

    // ── Step (called with CPU cycles) ─────────────────────────────────────────
    public void step(int cycles) {
        cycleAcc += cycles;

        if (!hblank) {
            if (cycleAcc >= 1006) {
                cycleAcc -= 1006;
                hblank = true;
                DISPSTAT |= 0x02;
                if ((DISPSTAT & 0x10) != 0 && mem != null) mem.requestInterrupt(1); // HBlank IRQ
                if (VCOUNT < HEIGHT) renderLine(VCOUNT);
            }
        } else {
            if (cycleAcc >= 226) {
                cycleAcc -= 226;
                hblank = false;
                DISPSTAT &= ~0x02;
                VCOUNT++;
                if (VCOUNT == HEIGHT) {
                    vblank = true;
                    DISPSTAT |= 0x01;
                    if ((DISPSTAT & 0x08) != 0 && mem != null) mem.requestInterrupt(0); // VBlank IRQ
                    if (frameCallback != null) frameCallback.accept(framebuffer.clone());
                } else if (VCOUNT >= HEIGHT + 68) {
                    VCOUNT = 0;
                    vblank = false;
                    DISPSTAT &= ~0x01;
                }
                // LYC compare
                int lyc = (DISPSTAT >> 8) & 0xFF;
                if (VCOUNT == lyc) {
                    DISPSTAT |= 0x04;
                    if ((DISPSTAT & 0x20) != 0 && mem != null) mem.requestInterrupt(2);
                } else {
                    DISPSTAT &= ~0x04;
                }
            }
        }
    }

    // ── Line renderer ─────────────────────────────────────────────────────────
    private void renderLine(int y) {
        int bgMode = DISPCNT & 7;
        // Fill line with backdrop color (palette[0])
        int backdrop = rgb555toARGB(palette[0] | (palette[1] << 8));
        int base = y * WIDTH;
        for (int x = 0; x < WIDTH; x++) framebuffer[base + x] = backdrop;

        if ((DISPCNT & 0x0080) == 0) return; // forced blank

        switch (bgMode) {
            case 0 -> renderMode0(y);
            case 1 -> renderMode1(y);
            case 2 -> renderMode2(y);
            case 3 -> renderMode3(y);
            case 4 -> renderMode4(y);
            case 5 -> renderMode5(y);
        }
        if ((DISPCNT & 0x1000) != 0) renderSprites(y);
    }

    // Mode 0: 4 text BGs
    private void renderMode0(int y) {
        for (int bg = 3; bg >= 0; bg--) {
            if ((DISPCNT & (1 << (8 + bg))) == 0) continue;
            renderTextBG(bg, y);
        }
    }

    // Mode 1: BG0+BG1 text, BG2 affine
    private void renderMode1(int y) {
        if ((DISPCNT & 0x0400) != 0) renderAffineBG(0, y);
        if ((DISPCNT & 0x0200) != 0) renderTextBG(1, y);
        if ((DISPCNT & 0x0100) != 0) renderTextBG(0, y);
    }

    // Mode 2: BG2+BG3 affine
    private void renderMode2(int y) {
        if ((DISPCNT & 0x0800) != 0) renderAffineBG(1, y);
        if ((DISPCNT & 0x0400) != 0) renderAffineBG(0, y);
    }

    // Mode 3: 240×160 15-bit bitmap, single frame
    private void renderMode3(int y) {
        int base = y * WIDTH * 2;
        int fbBase = y * WIDTH;
        for (int x = 0; x < WIDTH; x++) {
            int lo = vram[base + x * 2], hi = vram[base + x * 2 + 1];
            framebuffer[fbBase + x] = rgb555toARGB(lo | (hi << 8));
        }
    }

    // Mode 4: 240×160 8-bit palette bitmap, 2 frames
    private void renderMode4(int y) {
        int frameBase = ((DISPCNT & 0x0010) != 0) ? 0xA000 : 0;
        int base = frameBase + y * WIDTH;
        int fbBase = y * WIDTH;
        for (int x = 0; x < WIDTH; x++) {
            int palIdx = vram[base + x] * 2;
            framebuffer[fbBase + x] = rgb555toARGB(palette[palIdx] | (palette[palIdx+1] << 8));
        }
    }

    // Mode 5: 160×128 15-bit bitmap, 2 frames
    private void renderMode5(int y) {
        if (y >= 128) return;
        int frameBase = ((DISPCNT & 0x0010) != 0) ? 0xA000 : 0;
        int base = frameBase + y * 160 * 2;
        int fbBase = y * WIDTH;
        for (int x = 0; x < 160; x++) {
            int lo = vram[base + x*2], hi = vram[base + x*2+1];
            framebuffer[fbBase + x] = rgb555toARGB(lo | (hi << 8));
        }
    }

    // ── Text BG renderer ──────────────────────────────────────────────────────
    private void renderTextBG(int bg, int y) {
        int cnt    = BGCNT[bg];
        int tileBase = ((cnt >> 2) & 3) * 0x4000;
        int mapBase  = ((cnt >> 8) & 0x1F) * 0x800;
        boolean col256 = (cnt & 0x80) != 0;
        int size  = (cnt >> 14) & 3;

        int scrollX = BGHOFS[bg];
        int scrollY = BGVOFS[bg];
        int py = (y + scrollY) & 0x1FF;
        int tileRow = (py / 8) & 31;
        int lineY = py & 7;

        int fbBase = y * WIDTH;
        for (int x = 0; x < WIDTH; x++) {
            int px = (x + scrollX) & 0x1FF;
            int tileCol = (px / 8) & 31;
            int mapX = tileCol, mapY = tileRow;

            // Map screen selection for larger maps
            int screen = 0;
            if (size == 1 || size == 3) { if (px >= 256) { screen += 1; mapX &= 31; } }
            if (size == 2 || size == 3) { if (py >= 256) { screen += size==3?2:1; mapY &= 31; } }

            int mapAddr = mapBase + screen * 0x800 + mapY * 64 + mapX * 2;
            if (mapAddr + 1 >= vram.length) continue;
            int entry = vram[mapAddr] | (vram[mapAddr+1] << 8);
            int tileIdx = entry & 0x3FF;
            boolean hflip = (entry & 0x0400) != 0;
            boolean vflip = (entry & 0x0800) != 0;
            int palNum  = (entry >> 12) & 0xF;

            int tx = hflip ? (7 - (px & 7)) : (px & 7);
            int ty = vflip ? (7 - lineY)   : lineY;

            int color;
            if (col256) {
                int tileAddr = tileBase + tileIdx * 64 + ty * 8 + tx;
                if (tileAddr >= vram.length) continue;
                int palIdx = vram[tileAddr] * 2;
                if (vram[tileAddr] == 0) continue; // transparent
                color = rgb555toARGB(palette[palIdx] | (palette[palIdx+1] << 8));
            } else {
                int tileAddr = tileBase + tileIdx * 32 + ty * 4 + tx / 2;
                if (tileAddr >= vram.length) continue;
                int nibble = (tx & 1) == 0 ? vram[tileAddr] & 0xF : vram[tileAddr] >> 4;
                if (nibble == 0) continue; // transparent
                int palIdx = (palNum * 16 + nibble) * 2;
                color = rgb555toARGB(palette[palIdx] | (palette[palIdx+1] << 8));
            }
            framebuffer[fbBase + x] = color;
        }
    }

    // ── Affine BG renderer ────────────────────────────────────────────────────
    private void renderAffineBG(int bgIdx, int y) {
        int bg = bgIdx + 2;
        int cnt = BGCNT[bg];
        int tileBase = ((cnt >> 2) & 3) * 0x4000;
        int mapBase  = ((cnt >> 8) & 0x1F) * 0x800;
        boolean wrap = (cnt & 0x2000) != 0;
        int mapSize  = 16 << ((cnt >> 14) & 3); // 16,32,64,128 tiles

        int pa = BGX[bgIdx], pc = BGY[bgIdx];
        int pb = BGB[bgIdx], pd = BGC[bgIdx]; // actually B and C swapped — simplified
        int refX = pa, refY = pc; // use accumulated reference

        int fbBase = y * WIDTH;
        for (int x = 0; x < WIDTH; x++) {
            int srcX = (refX >> 8), srcY = (refY >> 8);
            if (wrap) { srcX = ((srcX % (mapSize*8)) + mapSize*8) % (mapSize*8);
                        srcY = ((srcY % (mapSize*8)) + mapSize*8) % (mapSize*8); }
            else if (srcX < 0 || srcX >= mapSize*8 || srcY < 0 || srcY >= mapSize*8) {
                refX += 0x100; refY += 0; // advance by 1 (dx=1 simplified)
                continue;
            }
            int tileCol = srcX / 8, tileRow2 = srcY / 8;
            int mapAddr = mapBase + tileRow2 * mapSize + tileCol;
            if (mapAddr >= vram.length) { refX += 0x100; continue; }
            int tileIdx = vram[mapAddr];
            int tx = srcX & 7, ty = srcY & 7;
            int tileAddr = tileBase + tileIdx * 64 + ty * 8 + tx;
            if (tileAddr >= vram.length) { refX += 0x100; continue; }
            int palIdx = vram[tileAddr] * 2;
            if (vram[tileAddr] != 0)
                framebuffer[fbBase + x] = rgb555toARGB(palette[palIdx] | (palette[palIdx+1]<<8));
            refX += 0x100; // simplified: dx=1, dy=0 (no rotation)
        }
    }

    // ── Sprite renderer ───────────────────────────────────────────────────────
    private static final int[][] SPRITE_SIZE = {
        {8,8},{16,16},{32,32},{64,64},     // square
        {16,8},{32,8},{32,16},{64,32},     // wide
        {8,16},{8,32},{16,32},{32,64}      // tall
    };

    private void renderSprites(int y) {
        int fbBase = y * WIDTH;
        for (int i = 127; i >= 0; i--) {
            int attr0 = oam[i*8]   | (oam[i*8+1]<<8);
            int attr1 = oam[i*8+2] | (oam[i*8+3]<<8);
            int attr2 = oam[i*8+4] | (oam[i*8+5]<<8);
            if ((attr0 & 0x0300) == 0x0200) continue; // disabled

            int sy = attr0 & 0xFF; if (sy >= 160) sy -= 256;
            int shape = (attr0 >> 14) & 3, size2 = (attr1 >> 14) & 3;
            int sizeIdx = shape * 4 + size2;
            int sw = SPRITE_SIZE[sizeIdx][0], sh = SPRITE_SIZE[sizeIdx][1];

            if (y < sy || y >= sy + sh) continue;

            int sx = attr1 & 0x1FF; if (sx >= 240) sx -= 512;
            boolean hflip = (attr0&0x0100)==0 && (attr1&0x1000)!=0;
            boolean vflip = (attr0&0x0100)==0 && (attr1&0x2000)!=0;
            boolean col256 = (attr0 & 0x2000) != 0;
            int palNum = (attr2 >> 12) & 0xF;
            int tileIdx = attr2 & 0x3FF;
            boolean priority_over = ((attr2 >> 10) & 3) == 0;

            int ly = y - sy; if (vflip) ly = sh - 1 - ly;
            int tilesPerRow = sw / 8;
            int tileBase = ((DISPCNT & 0x40) != 0) ? 0 : 0x10000; // obj tile base

            for (int sx2 = 0; sx2 < sw; sx2++) {
                int px = sx + sx2;
                if (px < 0 || px >= WIDTH) continue;
                int lx = hflip ? (sw - 1 - sx2) : sx2;

                int tileX = lx / 8, tileY = ly / 8;
                int tx = lx & 7, ty = ly & 7;
                int tile;
                if ((DISPCNT & 0x40) != 0) // 1D mapping
                    tile = tileIdx + tileY * tilesPerRow * (col256 ? 2 : 1) + tileX * (col256 ? 2 : 1);
                else // 2D mapping
                    tile = tileIdx + tileY * (col256 ? 16 : 32) + tileX;

                int color;
                if (col256) {
                    int addr = tileBase + tile * 64 + ty * 8 + tx;
                    if (addr < 0 || addr >= vram.length) continue;
                    if (vram[addr] == 0) continue;
                    int palIdx = vram[addr] * 2;
                    color = rgb555toARGB(palette[0x200 + palIdx] | (palette[0x201 + palIdx] << 8));
                } else {
                    int addr = tileBase + tile * 32 + ty * 4 + tx / 2;
                    if (addr < 0 || addr >= vram.length) continue;
                    int nibble = (tx & 1) == 0 ? vram[addr] & 0xF : vram[addr] >> 4;
                    if (nibble == 0) continue;
                    int palIdx = (palNum * 16 + nibble) * 2 + 0x200;
                    if (palIdx + 1 >= palette.length) continue;
                    color = rgb555toARGB(palette[palIdx] | (palette[palIdx+1] << 8));
                }
                framebuffer[fbBase + px] = color;
            }
        }
    }

    // ── Color conversion ──────────────────────────────────────────────────────
    private static int rgb555toARGB(int rgb555) {
        int r = (rgb555 & 0x1F) << 3;
        int g = ((rgb555 >> 5) & 0x1F) << 3;
        int b = ((rgb555 >> 10) & 0x1F) << 3;
        r |= r >> 5; g |= g >> 5; b |= b >> 5;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ── Memory access ─────────────────────────────────────────────────────────
    public int  readPalette8(int off)         { return off < palette.length ? palette[off] : 0; }
    public void writePalette8(int off, int v) { if (off < palette.length) palette[off] = v & 0xFF; }
    public int  readVRAM8(int off)            { return off < vram.length ? vram[off & 0x17FFF] : 0; }
    public void writeVRAM8(int off, int v)    { if (off < vram.length) vram[off & 0x17FFF] = v & 0xFF; }
    public int  readOAM8(int off)             { return off < oam.length ? oam[off] : 0; }
    public void writeOAM8(int off, int v)     { if (off < oam.length) oam[off] = v & 0xFF; }

    // ── I/O ───────────────────────────────────────────────────────────────────
    public int readIO8(int off) {
        return switch (off) {
            case 0x00 -> DISPCNT & 0xFF;
            case 0x01 -> (DISPCNT >> 8) & 0xFF;
            case 0x04 -> DISPSTAT & 0xFF;
            case 0x05 -> (DISPSTAT >> 8) & 0xFF;
            case 0x06 -> VCOUNT & 0xFF;
            default -> {
                if (off >= 0x08 && off <= 0x57) yield readBGIO(off);
                yield 0;
            }
        };
    }

    public void writeIO8(int off, int v) {
        switch (off) {
            case 0x00 -> DISPCNT = (DISPCNT & 0xFF00) | v;
            case 0x01 -> DISPCNT = (DISPCNT & 0x00FF) | (v << 8);
            case 0x04 -> DISPSTAT = (DISPSTAT & ~0xF8) | (v & 0xF8);
            case 0x05 -> DISPSTAT = (DISPSTAT & 0xFF) | (v << 8);
            default  -> writeBGIO(off, v);
        }
    }

    private int readBGIO(int off) {
        int bg = (off - 0x08) / 4, r = (off - 0x08) % 4;
        if (bg < 4 && r < 2) return (r == 0) ? BGCNT[bg] & 0xFF : (BGCNT[bg] >> 8) & 0xFF;
        return 0;
    }

    private void writeBGIO(int off, int v) {
        if (off >= 0x08 && off <= 0x0F) {
            int bg = (off - 0x08) / 2;
            boolean lo = (off & 1) == 0;
            if (lo) BGCNT[bg] = (BGCNT[bg] & 0xFF00) | v;
            else    BGCNT[bg] = (BGCNT[bg] & 0x00FF) | (v << 8);
        } else if (off >= 0x10 && off <= 0x1F) {
            int bg = (off - 0x10) / 4, r = (off - 0x10) % 4;
            if (r == 0)      BGHOFS[bg] = (BGHOFS[bg] & 0xFF00) | v;
            else if (r == 1) BGHOFS[bg] = (BGHOFS[bg] & 0x00FF) | ((v & 1) << 8);
            else if (r == 2) BGVOFS[bg] = (BGVOFS[bg] & 0xFF00) | v;
            else             BGVOFS[bg] = (BGVOFS[bg] & 0x00FF) | ((v & 1) << 8);
        }
    }

    public int getVCOUNT() { return VCOUNT; }
    public boolean isVBlank() { return vblank; }
}
