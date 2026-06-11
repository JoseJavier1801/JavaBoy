package com.emulator.cpu;
import com.emulator.memory.MemoryBus;
public class Interrupts {
    public static final int INT_VBLANK=0x01,INT_STAT=0x02,INT_TIMER=0x04,INT_SERIAL=0x08,INT_JOYPAD=0x10;
    private static final int[] VEC={0x0040,0x0048,0x0050,0x0058,0x0060};
    public boolean IME=false,IMEPending=false;
    public int IE=0x00,IF=0xE1;
    public void request(int bit){IF|=bit;}
    public void clear(int bit){IF&=~bit;}
    public boolean hasPending(){return(IE&IF&0x1F)!=0;}
    public int service(Registers r,MemoryBus bus){
        if(IMEPending){IME=true;IMEPending=false;}
        if(!IME)return 0;
        int p=IE&IF&0x1F;if(p==0)return 0;
        for(int i=0;i<5;i++){
            if((p&(1<<i))!=0){
                IME=false;IF&=~(1<<i);
                r.SP=(r.SP-1)&0xFFFF;bus.write(r.SP,(r.PC>>8)&0xFF);
                r.SP=(r.SP-1)&0xFFFF;bus.write(r.SP,r.PC&0xFF);
                r.PC=VEC[i];return 20;
            }
        }
        return 0;
    }
}
