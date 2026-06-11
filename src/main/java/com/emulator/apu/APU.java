package com.emulator.apu;

import javax.sound.sampled.*;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Game Boy APU — audio sin crackling.
 *
 * Fixes vs versión anterior:
 *  1. Hilo de audio dedicado con cola no-bloqueante (si llena, descarta en lugar de bloquear CPU).
 *  2. Timers de canal en ciclos de CPU reales (no en "muestras").
 *  3. High-pass filter de DC + low-pass filter suavizador.
 *  4. Volúmenes normalizados (máx ~0.25 por canal → no hay overflow).
 *  5. Frame sequencer correcto (512 Hz) para envelopes.
 *  6. Buffer de audio grande en SourceDataLine para absorber jitter del SO.
 */
public class APU {

    // ── Constantes ───────────────────────────────────────────────────────────
    private static final int    CPU_HZ        = 4194304;
    private static final int    SAMPLE_RATE   = 44100;
    private static final int    CHUNK_FRAMES  = 256;   // samples per chunk (low latency)
    private static final double CPS           = (double) CPU_HZ / SAMPLE_RATE; // ~95.1

    // ── Tabla de ciclos de duty ───────────────────────────────────────────────
    private static final int[][] DUTY = {
        {0,0,0,0,0,0,0,1},
        {1,0,0,0,0,0,0,1},
        {1,0,0,0,0,1,1,1},
        {0,1,1,1,1,1,1,0}
    };

    // ── Registros I/O ─────────────────────────────────────────────────────────
    private final int[] ch1r  = new int[5];
    private final int[] ch2r  = new int[5];
    private final int[] ch3r  = new int[5];
    private final int[] ch4r  = new int[5];
    private final int[] waveRam = new int[16];
    private int NR50=0x77, NR51=0xF3, NR52=0xF1;

    // ── CH1 estado ────────────────────────────────────────────────────────────
    private boolean ch1En=false;
    private int ch1Duty=0, ch1DutyPos=0, ch1FreqTimer=4;
    private int ch1Vol=0, ch1EnvVol=0, ch1EnvPer=0, ch1EnvTim=0;
    private boolean ch1EnvInc=false;

    // ── CH2 estado ────────────────────────────────────────────────────────────
    private boolean ch2En=false;
    private int ch2Duty=0, ch2DutyPos=0, ch2FreqTimer=4;
    private int ch2Vol=0, ch2EnvVol=0, ch2EnvPer=0, ch2EnvTim=0;
    private boolean ch2EnvInc=false;

    // ── CH3 estado ────────────────────────────────────────────────────────────
    private boolean ch3DACOn=false;
    private int ch3WavePos=0, ch3FreqTimer=4, ch3OutLevel=0;

    // ── CH4 estado ────────────────────────────────────────────────────────────
    private boolean ch4En=false;
    private int ch4Vol=0, ch4EnvVol=0, ch4EnvPer=0, ch4EnvTim=0;
    private boolean ch4EnvInc=false;
    private int ch4FreqTimer=8, ch4LFSR=0x7FFF;
    private boolean ch4WidthMode=false;

    // ── Frame sequencer ───────────────────────────────────────────────────────
    private int fsTimer=0, fsStep=0;

    // ── Downsampling ─────────────────────────────────────────────────────────
    private double cycleAccum=0.0;

    // ── Filtros ───────────────────────────────────────────────────────────────
    private float hpCapL=0f, hpCapR=0f;   // high-pass (elimina DC)
    private float lpL=0f,    lpR=0f;      // low-pass  (suaviza aliasing)
    private static final float HP_ALPHA = 0.999f;
    private static final float LP_ALPHA = 0.5f;

    // ── Cola de audio (hilo separado) ─────────────────────────────────────────
    private final ArrayBlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(32);
    private float[] chunkF = new float[CHUNK_FRAMES * 2]; // float stereo working buffer
    private int     chunkPos = 0;
    private SourceDataLine audioLine;
    private Thread audioThread;
    private volatile float   masterVolume = 1.0f;
    private volatile boolean muted        = false;
    // Channel enables (set from UI options)
    private volatile boolean ch1Mask = true, ch2Mask = true, ch3Mask = true, ch4Mask = true;
    // Stereo mode: 0=normal 1=mono 2=inverted
    private volatile int     stereoMode  = 0;
    // Bass boost: -5..+5 (applied as low-shelf gain)
    private volatile float   bassGain    = 1.0f;
    // Reverb (simple delay-line)
    private volatile boolean reverbOn    = false;
    private final float[]    rvBuf       = new float[8192];
    private int              rvIdx       = 0;
    private volatile boolean audioRunning = false;

    // ── Constructor ───────────────────────────────────────────────────────────
    public APU() { initAudio(); }

    private void initAudio() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            // Gran buffer SO → absorbe scheduling jitter
            audioLine.open(fmt, CHUNK_FRAMES * 2 * 2 * 8);
            audioLine.start();
            audioRunning = true;
            audioThread = new Thread(this::audioLoop, "GB-Audio");
            audioThread.setDaemon(true);
            audioThread.setPriority(Thread.MAX_PRIORITY - 1);
            audioThread.start();
        } catch (Exception e) {
            System.out.println("[APU] Sin audio: " + e.getMessage());
        }
    }

    private void audioLoop() {
        while (audioRunning) {
            try {
                byte[] buf = audioQueue.take();
                if (audioLine != null) audioLine.write(buf, 0, buf.length);
            } catch (InterruptedException ignored) {}
        }
    }

    // ── Paso principal ────────────────────────────────────────────────────────
    public void step(int cycles) {
        if ((NR52 & 0x80) == 0) return;

        // Frame sequencer @ 512 Hz = cada 8192 ciclos
        fsTimer += cycles;
        while (fsTimer >= 8192) { fsTimer -= 8192; tickFS(); }

        // Generar muestras
        cycleAccum += cycles;
        while (cycleAccum >= CPS) {
            cycleAccum -= CPS;
            int stepCyc = (int) CPS;
            tickCh1(stepCyc); tickCh2(stepCyc); tickCh3(stepCyc); tickCh4(stepCyc);
            emitSample();
        }
    }

    // ── Frame sequencer ───────────────────────────────────────────────────────
    private void tickFS() {
        if ((fsStep & 1) == 0) tickLength();
        if (fsStep == 2 || fsStep == 6) tickSweep();
        if (fsStep == 7) tickEnv();
        fsStep = (fsStep + 1) & 7;
    }
    private void tickLength() {} // simplificado
    private void tickSweep()  {} // simplificado

    private void tickEnv() {
        if (ch1En && ch1EnvPer > 0) {
            if (--ch1EnvTim <= 0) { ch1EnvTim=ch1EnvPer; ch1EnvVol=clamp(ch1EnvVol+(ch1EnvInc?1:-1)); ch1Vol=ch1EnvVol; }
        }
        if (ch2En && ch2EnvPer > 0) {
            if (--ch2EnvTim <= 0) { ch2EnvTim=ch2EnvPer; ch2EnvVol=clamp(ch2EnvVol+(ch2EnvInc?1:-1)); ch2Vol=ch2EnvVol; }
        }
        if (ch4En && ch4EnvPer > 0) {
            if (--ch4EnvTim <= 0) { ch4EnvTim=ch4EnvPer; ch4EnvVol=clamp(ch4EnvVol+(ch4EnvInc?1:-1)); ch4Vol=ch4EnvVol; }
        }
    }
    private int clamp(int v){ return Math.max(0,Math.min(15,v)); }

    // ── Tick canales ──────────────────────────────────────────────────────────
    private void tickCh1(int c){
        ch1FreqTimer-=c;
        if(ch1FreqTimer<=0){int f=((ch1r[4]&7)<<8)|ch1r[3];ch1FreqTimer+=(2048-f)*4;ch1DutyPos=(ch1DutyPos+1)&7;}
    }
    private void tickCh2(int c){
        ch2FreqTimer-=c;
        if(ch2FreqTimer<=0){int f=((ch2r[4]&7)<<8)|ch2r[3];ch2FreqTimer+=(2048-f)*4;ch2DutyPos=(ch2DutyPos+1)&7;}
    }
    private void tickCh3(int c){
        ch3FreqTimer-=c;
        if(ch3FreqTimer<=0){int f=((ch3r[4]&7)<<8)|ch3r[3];ch3FreqTimer+=(2048-f)*2;ch3WavePos=(ch3WavePos+1)&31;}
    }
    private void tickCh4(int c){
        ch4FreqTimer-=c;
        if(ch4FreqTimer<=0){
            int d=ch4r[3]&7,s=(ch4r[3]>>4)&0xF;ch4FreqTimer+=(d==0?8:d*16)<<s;
            int x=(ch4LFSR^(ch4LFSR>>1))&1;ch4LFSR=(ch4LFSR>>1)|(x<<14);
            if(ch4WidthMode)ch4LFSR=(ch4LFSR&~0x40)|(x<<6);
        }
    }

    // ── Emit sample ───────────────────────────────────────────────────────────
    private void emitSample() {
        float L=0f, R=0f;

        if (ch1En && ch1Mask) {
            float s = DUTY[ch1Duty][ch1DutyPos] * ch1Vol / 15f;
            if((NR51&0x10)!=0) R+=s;
            if((NR51&0x01)!=0) L+=s;
        }
        if (ch2En && ch2Mask) {
            float s = DUTY[ch2Duty][ch2DutyPos] * ch2Vol / 15f;
            if((NR51&0x20)!=0) R+=s;
            if((NR51&0x02)!=0) L+=s;
        }
        if (ch3DACOn && ch3Mask) {
            int b = waveRam[ch3WavePos>>1];
            int n = (ch3WavePos&1)==0 ? (b>>4) : (b&0xF);
            float s = 0f;
            if(ch3OutLevel>0) s = (n-8)/7.5f / (1<<(ch3OutLevel-1));
            if((NR51&0x40)!=0) R+=s;
            if((NR51&0x04)!=0) L+=s;
        }
        if (ch4En && ch4Mask) {
            float s = ((ch4LFSR&1)==0?1f:0f)*ch4Vol/15f;
            if((NR51&0x80)!=0) R+=s;
            if((NR51&0x08)!=0) L+=s;
        }

        // Master volume (4 canales → dividir entre 4 para no saturar)
        float vL = ((NR50>>4)&7)/7f, vR = (NR50&7)/7f;
        L = L * vL * 0.25f;
        R = R * vR * 0.25f;

        // High-pass filter (elimina componente DC que causa crackling entre silencio y sonido)
        float filtL = L - hpCapL;   hpCapL = L - filtL * HP_ALPHA;
        float filtR = R - hpCapR;   hpCapR = R - filtR * HP_ALPHA;

        // Low-pass filter (suaviza aliasing de las ondas cuadradas)
        lpL = LP_ALPHA * filtL + (1f - LP_ALPHA) * lpL;
        lpR = LP_ALPHA * filtR + (1f - LP_ALPHA) * lpR;

        // Bass boost (low-shelf: amplify the already-filtered low freq via gain on lpL/R)
        float bL = lpL * bassGain, bR = lpR * bassGain;

        // Stereo mode
        float outL, outR;
        switch (stereoMode) {
            case 1  -> { outL = (bL + bR) * 0.5f; outR = outL; }  // mono
            case 2  -> { outL = bR; outR = bL; }                   // inverted
            default -> { outL = bL; outR = bR; }                   // normal stereo
        }

        // Reverb (simple Schroeder-style single delay)
        if (reverbOn) {
            int rOff = (rvIdx + rvBuf.length - 3000) & (rvBuf.length - 1);
            float rSample = rvBuf[rOff];
            rvBuf[rvIdx] = (outL + outR) * 0.5f * 0.35f;
            rvIdx = (rvIdx + 1) & (rvBuf.length - 1);
            outL += rSample * 0.3f;
            outR += rSample * 0.3f;
        }

        float vol = muted ? 0f : masterVolume;
        chunkF[chunkPos++] = outL * vol;
        chunkF[chunkPos++] = outR * vol;

        if (chunkPos >= chunkF.length) {
            flushChunk();
        }
    }

    private void flushChunk() {
        byte[] bytes = new byte[CHUNK_FRAMES * 4]; // 2 ch * 2 bytes
        for (int i = 0; i < CHUNK_FRAMES * 2; i++) {
            short s = (short)(Math.max(-1f, Math.min(1f, chunkF[i])) * 32767f);
            bytes[i*2]   = (byte)(s & 0xFF);
            bytes[i*2+1] = (byte)(s >> 8);
        }
        // offer() – no bloquea nunca. Si la cola está llena descartamos el chunk
        // (preferable a bloquear el hilo de CPU y desincronizar el timing)
        audioQueue.offer(bytes);
        chunkF   = new float[CHUNK_FRAMES * 2];
        chunkPos = 0;
    }

    // ── Triggers ──────────────────────────────────────────────────────────────
    private void triggerCH1(){
        ch1En=true; NR52|=1;
        ch1Vol=ch1EnvVol=(ch1r[2]>>4)&0xF; ch1EnvInc=(ch1r[2]&8)!=0;
        ch1EnvPer=ch1r[2]&7; ch1EnvTim=ch1EnvPer;
        int f=((ch1r[4]&7)<<8)|ch1r[3]; ch1FreqTimer=(2048-f)*4;
    }
    private void triggerCH2(){
        ch2En=true; NR52|=2;
        ch2Vol=ch2EnvVol=(ch2r[2]>>4)&0xF; ch2EnvInc=(ch2r[2]&8)!=0;
        ch2EnvPer=ch2r[2]&7; ch2EnvTim=ch2EnvPer;
        int f=((ch2r[4]&7)<<8)|ch2r[3]; ch2FreqTimer=(2048-f)*4;
    }
    private void triggerCH3(){
        if(!ch3DACOn)return; NR52|=4;
        ch3WavePos=0; int f=((ch3r[4]&7)<<8)|ch3r[3]; ch3FreqTimer=(2048-f)*2;
    }
    private void triggerCH4(){
        ch4En=true; NR52|=8;
        ch4Vol=ch4EnvVol=(ch4r[2]>>4)&0xF; ch4EnvInc=(ch4r[2]&8)!=0;
        ch4EnvPer=ch4r[2]&7; ch4EnvTim=ch4EnvPer;
        ch4LFSR=0x7FFF;
        int d=ch4r[3]&7,s=(ch4r[3]>>4)&0xF; ch4FreqTimer=(d==0?8:d*16)<<s;
    }

    // ── I/O ───────────────────────────────────────────────────────────────────
    public int readIO(int a){
        return switch(a){
            case 0xFF10->ch1r[0]|0x80; case 0xFF11->ch1r[1]|0x3F; case 0xFF12->ch1r[2];
            case 0xFF13->0xFF;         case 0xFF14->ch1r[4]|0xBF;
            case 0xFF16->ch2r[1]|0x3F; case 0xFF17->ch2r[2]; case 0xFF18->0xFF; case 0xFF19->ch2r[4]|0xBF;
            case 0xFF1A->ch3r[0]|0x7F; case 0xFF1B->0xFF; case 0xFF1C->ch3r[2]|0x9F;
            case 0xFF1D->0xFF;         case 0xFF1E->ch3r[4]|0xBF;
            case 0xFF20->0xFF; case 0xFF21->ch4r[2]; case 0xFF22->ch4r[3]; case 0xFF23->ch4r[4]|0xBF;
            case 0xFF24->NR50; case 0xFF25->NR51; case 0xFF26->NR52|0x70;
            default->0xFF;
        };
    }

    public void writeIO(int a,int v){
        switch(a){
            case 0xFF10->ch1r[0]=v;
            case 0xFF11->{ ch1r[1]=v; ch1Duty=(v>>6)&3; }
            case 0xFF12->{ ch1r[2]=v; ch1EnvVol=(v>>4)&0xF; ch1EnvInc=(v&8)!=0; ch1EnvPer=v&7; if((v&0xF8)==0)ch1En=false; }
            case 0xFF13->ch1r[3]=v;
            case 0xFF14->{ ch1r[4]=v; if((v&0x80)!=0)triggerCH1(); }
            case 0xFF16->{ ch2r[1]=v; ch2Duty=(v>>6)&3; }
            case 0xFF17->{ ch2r[2]=v; ch2EnvVol=(v>>4)&0xF; ch2EnvInc=(v&8)!=0; ch2EnvPer=v&7; if((v&0xF8)==0)ch2En=false; }
            case 0xFF18->ch2r[3]=v;
            case 0xFF19->{ ch2r[4]=v; if((v&0x80)!=0)triggerCH2(); }
            case 0xFF1A->{ ch3r[0]=v; ch3DACOn=(v&0x80)!=0; if(!ch3DACOn)NR52&=~4; }
            case 0xFF1B->ch3r[1]=v;
            case 0xFF1C->{ ch3r[2]=v; ch3OutLevel=(v>>5)&3; }
            case 0xFF1D->ch3r[3]=v;
            case 0xFF1E->{ ch3r[4]=v; if((v&0x80)!=0)triggerCH3(); }
            case 0xFF20->ch4r[1]=v;
            case 0xFF21->{ ch4r[2]=v; ch4EnvVol=(v>>4)&0xF; ch4EnvInc=(v&8)!=0; ch4EnvPer=v&7; if((v&0xF8)==0)ch4En=false; }
            case 0xFF22->{ ch4r[3]=v; ch4WidthMode=(v&8)!=0; }
            case 0xFF23->{ ch4r[4]=v; if((v&0x80)!=0)triggerCH4(); }
            case 0xFF24->NR50=v;
            case 0xFF25->NR51=v;
            case 0xFF26->{ boolean was=(NR52&0x80)!=0; NR52=(NR52&0xF)|(v&0x80);
                           if(was&&(NR52&0x80)==0){ch1En=ch2En=ch4En=false;ch3DACOn=false;}}
        }
    }
    public int  readWave(int a)          { return waveRam[a-0xFF30]; }
    public void writeWave(int a,int v)   { waveRam[a-0xFF30]=v; }

    public void setMasterVolume(float v) { masterVolume = Math.max(0f, Math.min(1f, v)); }
    public float getMasterVolume()        { return masterVolume; }
    public void  setMuted(boolean m)      { muted = m; }
    public boolean isMuted()              { return muted; }

    // ── UI-accessible sound controls ─────────────────────────────────────────
    public void setChannels(boolean c1, boolean c2, boolean c3, boolean c4) {
        ch1Mask=c1; ch2Mask=c2; ch3Mask=c3; ch4Mask=c4;
    }
    public void setStereoMode(int mode)  { stereoMode = Math.max(0,Math.min(2,mode)); }
    public int  getStereoMode()          { return stereoMode; }
    public void setBassBoost(int db) {
        // db: -5..+5 → gain: 0.56..1.78 (approx 3dB per step)
        bassGain = (float)Math.pow(10.0, db / 20.0);
    }
    public int  getBassBoost() {
        return (int)Math.round(Math.log10(bassGain) * 20.0);
    }
    public void setReverb(boolean on)    { reverbOn=on; if(!on){java.util.Arrays.fill(rvBuf,0f);rvIdx=0;} }
    public boolean isReverb()            { return reverbOn; }
    public boolean[] getChannelMasks()   { return new boolean[]{ch1Mask,ch2Mask,ch3Mask,ch4Mask}; }

    public void stop(){
        audioRunning=false;
        if(audioThread!=null)audioThread.interrupt();
        if(audioLine!=null){audioLine.stop();audioLine.close();}
    }
}
