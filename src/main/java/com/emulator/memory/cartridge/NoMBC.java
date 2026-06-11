package com.emulator.memory.cartridge;
public class NoMBC extends Cartridge {
    public NoMBC(int[] rom,int[] ram){super(rom,ram);}
    public int  read(int a){return a<rom.length?rom[a]:0xFF;}
    public void write(int a,int v){}
}
