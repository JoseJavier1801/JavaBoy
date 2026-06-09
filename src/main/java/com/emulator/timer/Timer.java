package com.emulator.timer;
import com.emulator.cpu.Interrupts;
import com.emulator.memory.MemoryBus;
import com.emulator.savestate.SaveData;

public class Timer {
    private static final int[] THR={1024,16,64,256};
    private int DIV=0,TIMA=0,TMA=0,TAC=0xF8;
    private int divAcc=0,timaAcc=0;
    private boolean overflow=false;
    private int overflowDelay=0;

    public void step(int cycles,MemoryBus bus){
        divAcc+=cycles;
        while(divAcc>=256){divAcc-=256;DIV=(DIV+1)&0xFF;}
        if(overflow){overflowDelay-=cycles;if(overflowDelay<=0){overflow=false;TIMA=TMA;bus.requestInterrupt(Interrupts.INT_TIMER);}return;}
        if((TAC&4)==0)return;
        int thr=THR[TAC&3];
        timaAcc+=cycles;
        while(timaAcc>=thr){timaAcc-=thr;TIMA++;if(TIMA>0xFF){TIMA=0;overflow=true;overflowDelay=4;}}
    }
    public int read(int a){return switch(a){case 0xFF04->DIV;case 0xFF05->TIMA;case 0xFF06->TMA;case 0xFF07->TAC|0xF8;default->0xFF;};}
    public void write(int a,int v){switch(a){case 0xFF04->{DIV=0;divAcc=0;}case 0xFF05->TIMA=v;case 0xFF06->TMA=v;case 0xFF07->{TAC=v&7;timaAcc=0;}}}

    public void captureState(SaveData d){d.DIV=DIV;d.TIMA=TIMA;d.TMA=TMA;d.TAC=TAC;d.divAcc=divAcc;d.timaAcc=timaAcc;}
    public void applyState(SaveData d){DIV=d.DIV;TIMA=d.TIMA;TMA=d.TMA;TAC=d.TAC;divAcc=d.divAcc;timaAcc=d.timaAcc;}
}
