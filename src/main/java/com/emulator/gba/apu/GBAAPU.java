package com.emulator.gba.apu;

import javax.sound.sampled.*;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * GBA APU — 6 channels: 4 GB-compatible + 2 Direct Sound (DMA PCM).
 * Simplified implementation that produces correct-sounding output.
 */
public class GBAAPU {

    private static final int SAMPLE_RATE  = 44100;
    private static final int CPU_HZ       = 16777216;
    private static final int CHUNK        = 512;
    private static final double CPS       = (double) CPU_HZ / SAMPLE_RATE;

    // ── I/O shadow registers ──────────────────────────────────────────────────
    private final int[] ioReg = new int[0x50];

    // ── Channel state (GB-compatible CH1-CH4) ─────────────────────────────────
    private int  ch1Timer=1, ch1Pos=0, ch1Vol=0, ch1Duty=0;
    private int  ch2Timer=1, ch2Pos=0, ch2Vol=0, ch2Duty=0;
    private int  ch3Timer=1, ch3Pos=0, ch3OutLevel=0;
    private final int[] waveRam = new int[16];
    private int  ch4Timer=1, ch4Vol=0, ch4LFSR=0x7FFF;

    // ── Direct Sound (FIFO) — stub ─────────────────────────────────────────────
    // DS channels play 8-bit PCM fed via DMA; we stub them with silence here
    // A full implementation would buffer the FIFO and output at 32768 Hz

    // ── Volume / mute ─────────────────────────────────────────────────────────
    private volatile float   masterVolume = 1.0f;
    private volatile boolean muted        = false;

    // ── Downsampling ──────────────────────────────────────────────────────────
    private double cycleAccum = 0;
    private float lpL = 0, lpR = 0;
    private static final float LP = 0.5f;

    // ── Output ────────────────────────────────────────────────────────────────
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
    private float[] chunk  = new float[CHUNK * 2];
    private int     chunkPos = 0;
    private SourceDataLine audioLine;
    private Thread audioThread;
    private volatile boolean running = false;

    private static final int[][] DUTY = {
        {0,0,0,0,0,0,0,1},{1,0,0,0,0,0,0,1},{1,0,0,0,0,1,1,1},{0,1,1,1,1,1,1,0}
    };

    public GBAAPU() { initAudio(); }

    private void initAudio() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(fmt, CHUNK * 4 * 8);
            audioLine.start();
            running = true;
            audioThread = new Thread(() -> {
                while (running) {
                    try {
                        byte[] buf = queue.take();
                        if (audioLine != null) audioLine.write(buf, 0, buf.length);
                    } catch (InterruptedException ignored) {}
                }
            }, "GBA-Audio");
            audioThread.setDaemon(true);
            audioThread.setPriority(Thread.MAX_PRIORITY - 1);
            audioThread.start();
        } catch (Exception e) {
            System.out.println("[GBA APU] Audio no disponible: " + e.getMessage());
        }
    }

    public void step(int cycles) {
        cycleAccum += cycles;
        while (cycleAccum >= CPS) {
            cycleAccum -= CPS;
            int sc = (int) CPS;
            // Advance channel timers
            ch1Timer -= sc; if (ch1Timer<=0){int f=((ioReg[0x14]&7)<<8)|ioReg[0x13];ch1Timer+=(2048-f)*4;ch1Pos=(ch1Pos+1)&7;}
            ch2Timer -= sc; if (ch2Timer<=0){int f=((ioReg[0x19]&7)<<8)|ioReg[0x18];ch2Timer+=(2048-f)*4;ch2Pos=(ch2Pos+1)&7;}
            ch3Timer -= sc; if (ch3Timer<=0){int f=((ioReg[0x1E]&7)<<8)|ioReg[0x1D];ch3Timer+=(2048-f)*2;ch3Pos=(ch3Pos+1)&31;}
            ch4Timer -= sc; if (ch4Timer<=0){int d=ioReg[0x22]&7,s=(ioReg[0x22]>>4)&0xF;ch4Timer+=(d==0?8:d*16)<<s;int x=(ch4LFSR^(ch4LFSR>>1))&1;ch4LFSR=(ch4LFSR>>1)|(x<<14);}
            emitSample();
        }
    }

    private void emitSample() {
        float L = 0, R = 0;
        // CH1
        float s1 = DUTY[ch1Duty][ch1Pos] * ch1Vol / 15f;
        L += (ioReg[0x25] & 0x01) != 0 ? s1 : 0;
        R += (ioReg[0x25] & 0x10) != 0 ? s1 : 0;
        // CH2
        float s2 = DUTY[ch2Duty][ch2Pos] * ch2Vol / 15f;
        L += (ioReg[0x25] & 0x02) != 0 ? s2 : 0;
        R += (ioReg[0x25] & 0x20) != 0 ? s2 : 0;
        // CH3
        if ((ioReg[0x1A] & 0x80) != 0 && ch3OutLevel > 0) {
            int b = waveRam[ch3Pos>>1]; int n = (ch3Pos&1)==0?(b>>4):(b&0xF);
            float s3 = (n-8)/7.5f / (1 << (ch3OutLevel-1));
            L += (ioReg[0x25] & 0x04) != 0 ? s3 : 0;
            R += (ioReg[0x25] & 0x40) != 0 ? s3 : 0;
        }
        // CH4
        float s4 = ((ch4LFSR&1)==0?1f:0f)*ch4Vol/15f;
        L += (ioReg[0x25] & 0x08) != 0 ? s4 : 0;
        R += (ioReg[0x25] & 0x80) != 0 ? s4 : 0;

        float vL = ((ioReg[0x24]>>4)&7)/7f, vR = (ioReg[0x24]&7)/7f;
        L = L * vL * 0.20f; R = R * vR * 0.20f;
        float vol = muted ? 0f : masterVolume;
        lpL = LP*L + (1-LP)*lpL; lpR = LP*R + (1-LP)*lpR;

        chunk[chunkPos++] = lpL * vol;
        chunk[chunkPos++] = lpR * vol;
        if (chunkPos >= chunk.length) {
            byte[] bytes = new byte[CHUNK*4];
            for (int i = 0; i < CHUNK*2; i++) {
                short s = (short)(Math.max(-1f,Math.min(1f,chunk[i]))*32767f);
                bytes[i*2] = (byte)(s&0xFF); bytes[i*2+1] = (byte)(s>>8);
            }
            queue.offer(bytes);
            chunk = new float[CHUNK*2]; chunkPos = 0;
        }
    }

    public void writeIO8(int off, int v) {
        int idx = off - 0x060;
        if (idx >= 0 && idx < ioReg.length) ioReg[idx] = v & 0xFF;
        switch (off) {
            case 0x072 -> { ch1Duty=(v>>6)&3; }
            case 0x073 -> { ch1Vol=(v>>4)&0xF; }
            case 0x077 -> { ch2Duty=(v>>6)&3; }
            case 0x078 -> { ch2Vol=(v>>4)&0xF; }
            case 0x07A -> { ch3OutLevel=(v>>5)&3; }
            case 0x07D -> { ch4Vol=(v>>4)&0xF; ch4LFSR=0x7FFF; }
        }
    }

    public int readIO8(int off) {
        int idx = off - 0x060;
        return (idx >= 0 && idx < ioReg.length) ? ioReg[idx] : 0xFF;
    }

    public void setMasterVolume(float v) { masterVolume = Math.max(0f, Math.min(1f, v)); }
    public float getMasterVolume()       { return masterVolume; }
    public void  setMuted(boolean m)     { muted = m; }
    public boolean isMuted()             { return muted; }

    public void stop() {
        running = false;
        if (audioThread != null) audioThread.interrupt();
        if (audioLine  != null) { audioLine.stop(); audioLine.close(); }
    }
}
