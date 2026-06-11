package com.emulator;

import com.emulator.cpu.CPU;
import com.emulator.memory.MemoryBus;
import com.emulator.memory.cartridge.Cartridge;
import com.emulator.ppu.PPU;
import com.emulator.apu.APU;
import com.emulator.timer.Timer;
import com.emulator.input.Joypad;
import com.emulator.savestate.SaveData;
import com.emulator.savestate.SaveStateManager;
import com.emulator.ui.GameBoyWindow;

import java.io.IOException;

/**
 * Clase principal del emulador — DMG + GBC.
 */
public class GameBoy implements Runnable {

    public static final int CPU_HZ           = 4194304;
    public static final int TARGET_FPS       = 60;
    public static final int CYCLES_PER_FRAME = CPU_HZ / TARGET_FPS;  // 69905

    private final Cartridge        cart;
    private final String           romPath;
    private final MemoryBus        bus;
    private final CPU              cpu;
    private final PPU              ppu;
    private final APU              apu;
    private final Timer            timer;
    private final Joypad           joypad;
    private final GBCMode          gbcMode;
    private final GameBoyWindow    window;
    private final SaveStateManager saveManager;

    private volatile boolean running = false;
    private volatile boolean paused  = false;
    private Thread emulatorThread;

    public GameBoy(String romPath) throws IOException {
        this.romPath = romPath;
        cart        = Cartridge.load(romPath);
        // forceGBC: treat DMG ROMs as GBC (colour palettes, etc.)
        boolean fgbc = com.emulator.ui.EmulatorSettings.get().forceGBC;
        gbcMode     = (fgbc && cart.getGBCMode() == GBCMode.DMG) ? GBCMode.GBC : cart.getGBCMode();
        joypad      = new Joypad();
        ppu         = new PPU();
        ppu.setMode(gbcMode);
        apu         = new APU();
        timer       = new Timer();
        bus         = new MemoryBus(cart, ppu, apu, timer, joypad, gbcMode);
        cpu         = new CPU(bus, gbcMode);
        saveManager = new SaveStateManager(romPath);
        window      = new GameBoyWindow(this, joypad, saveManager, gbcMode);
        ppu.setFrameCallback(window::onFrame);
    }

    @Override
    public void run() {
        running = true;
        final long FRAME_NS = 1_000_000_000L / TARGET_FPS;
        long frameStart = System.nanoTime();

        while (running) {
            if (paused) { sleep(5); frameStart = System.nanoTime(); continue; }

            // GBC doble velocidad: 2× ciclos por frame
            int frameCycles = bus.doubleSpeed ? CYCLES_PER_FRAME * 2 : CYCLES_PER_FRAME;
            int budget = frameCycles;

            while (budget > 0 && running) {
                int c = cpu.step();
                // PPU y Timer usan ciclos normales (÷2 en doble velocidad)
                int ppuCycles = bus.doubleSpeed ? c / 2 : c;
                ppu.step(ppuCycles, bus);
                apu.step(ppuCycles);
                timer.step(c, bus);   // timer corre a velocidad real
                budget -= c;
            }

            long now = System.nanoTime(), sleepNs = FRAME_NS - (now - frameStart);
            if (sleepNs > 1_000_000L) sleep(sleepNs / 1_000_000L);
            frameStart = System.nanoTime();
        }
        cart.saveBattery();
        apu.stop();
    }

    public void start() {
        emulatorThread = new Thread(this, "GameBoy-Emulator");
        emulatorThread.setDaemon(false);
        emulatorThread.start();
    }

    public void stop()               { running = false; }
    public void setPaused(boolean p) { paused  = p; }
    public boolean isPaused()        { return paused; }
    public GBCMode getGBCMode()      { return gbcMode; }
    public APU     getAPU()           { return apu; }
    public String  getRomPath()       { return romPath; }
    public GameBoyWindow window()    { return window; }

    public void saveState(int slot) {
        boolean was = paused; paused = true; sleep(20);
        SaveData d = new SaveData();
        cpu.captureState(d); bus.captureState(d); ppu.captureState(d); timer.captureState(d);
        saveManager.save(slot, d);
        paused = was;
    }

    public boolean loadState(int slot) {
        SaveData d = saveManager.load(slot);
        if (d == null) return false;
        boolean was = paused; paused = true; sleep(20);
        cpu.applyState(d); bus.applyState(d); ppu.applyState(d); timer.applyState(d);
        paused = was;
        return true;
    }

    public SaveStateManager getSaveManager() { return saveManager; }

    private void sleep(long ms) {
        try { Thread.sleep(Math.max(1, ms)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws IOException {
        String rom = args.length > 0 ? args[0] : null;
        com.emulator.ui.Launcher.launch(rom);
    }
}
