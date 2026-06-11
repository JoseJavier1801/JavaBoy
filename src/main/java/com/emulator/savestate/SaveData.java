package com.emulator.savestate;
public class SaveData {
    // CPU
    public int A,F,B,C,D,E,H,L,SP,PC,IE,IF;
    public boolean IME,halted;
    // Memory
    public int[] wram,hram,cartRam;
    // PPU
    public int LCDC,STAT,SCY,SCX,LY,LYC,BGP,OBP0,OBP1,WY,WX,ppuMode,ppuCycles,windowLine;
    public int[] vram,oam;
    // Timer
    public int DIV,TIMA,TMA,TAC,divAcc,timaAcc;
    // Meta
    public String timestamp="";
}
