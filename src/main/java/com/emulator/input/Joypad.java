package com.emulator.input;
public class Joypad {
    public enum Button{RIGHT,LEFT,UP,DOWN,A,B,SELECT,START}
    private int buttons=0xFF,directions=0xFF,select=0x30;
    public synchronized void press(Button b){
        switch(b){case RIGHT->directions&=~1;case LEFT->directions&=~2;case UP->directions&=~4;case DOWN->directions&=~8;
                   case A->buttons&=~1;case B->buttons&=~2;case SELECT->buttons&=~4;case START->buttons&=~8;}
    }
    public synchronized void release(Button b){
        switch(b){case RIGHT->directions|=1;case LEFT->directions|=2;case UP->directions|=4;case DOWN->directions|=8;
                   case A->buttons|=1;case B->buttons|=2;case SELECT->buttons|=4;case START->buttons|=8;}
    }
    public synchronized int read(){
        int r=select|0xCF;
        if((select&0x10)==0)r=(r&0xF0)|(directions&0xF);
        if((select&0x20)==0)r=(r&0xF0)|(buttons&0xF);
        return r;
    }
    public synchronized void write(int v){select=v&0x30;}
}
