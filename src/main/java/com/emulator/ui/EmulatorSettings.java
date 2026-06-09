package com.emulator.ui;

import java.util.prefs.Preferences;

/**
 * Configuración global persistente del emulador.
 * Usa java.util.prefs.Preferences para guardar/cargar automáticamente.
 * Acceso singleton: EmulatorSettings.get()
 */
public class EmulatorSettings {

    private static final Preferences PREFS =
        Preferences.userNodeForPackage(EmulatorSettings.class);

    private static final EmulatorSettings INSTANCE = new EmulatorSettings();
    public static EmulatorSettings get() { return INSTANCE; }

    // ── Vídeo ─────────────────────────────────────────────────────────────────
    public int     scale        = 3;
    public int     filterMode   = 0;    // 0=nearest 1=bilinear 2=bicubic 3=CRT 4=LCD 5=xBR
    public boolean keepAspect   = true;
    public boolean showFPS      = true;
    public int     dmgPalette   = 0;    // 0–5
    public int     brightness   = 100;  // 0–200 (100=normal)
    public int     contrast     = 100;  // 0–200
    public int     saturation   = 100;  // 0–200
    public boolean integerScale = true; // forzar escala entera
    public boolean showGrid     = false;// cuadrícula de píxeles

    // ── Sonido ────────────────────────────────────────────────────────────────
    public int     masterVolume = 100;  // 0–100
    public boolean muted        = false;
    public boolean ch1On        = true;
    public boolean ch2On        = true;
    public boolean ch3On        = true;
    public boolean ch4On        = true;
    public int     stereoMode   = 0;    // 0=stereo 1=mono 2=invertido
    public int     bassBoost    = 0;    // -5..+5 (en dB, aproximado)
    public boolean reverb       = false;

    // ── General ───────────────────────────────────────────────────────────────
    public boolean pauseOnFocusLoss = true;
    public boolean showStatusBar    = true;
    public String  lastRomPath      = "";

    // ── Key bindings (stored as KeyEvent.VK_xxx int codes) ───────────────────
    public int keyA      = java.awt.event.KeyEvent.VK_Z;
    public int keyB      = java.awt.event.KeyEvent.VK_X;
    public int keyStart  = java.awt.event.KeyEvent.VK_ENTER;
    public int keySelect = java.awt.event.KeyEvent.VK_BACK_SPACE;
    public int keyUp     = java.awt.event.KeyEvent.VK_UP;
    public int keyDown   = java.awt.event.KeyEvent.VK_DOWN;
    public int keyLeft   = java.awt.event.KeyEvent.VK_LEFT;
    public int keyRight  = java.awt.event.KeyEvent.VK_RIGHT;
    public int keyL      = java.awt.event.KeyEvent.VK_A;
    public int keyR      = java.awt.event.KeyEvent.VK_S;

    // ── GBC colour mode forced on DMG ROMs ────────────────────────────────────
    public boolean forceGBC = false;

    private EmulatorSettings() { load(); }

    // ── Persistencia ──────────────────────────────────────────────────────────
    public void save() {
        // Vídeo
        PREFS.putInt    ("scale",        scale);
        PREFS.putInt    ("filterMode",   filterMode);
        PREFS.putBoolean("keepAspect",   keepAspect);
        PREFS.putBoolean("showFPS",      showFPS);
        PREFS.putInt    ("dmgPalette",   dmgPalette);
        PREFS.putInt    ("brightness",   brightness);
        PREFS.putInt    ("contrast",     contrast);
        PREFS.putInt    ("saturation",   saturation);
        PREFS.putBoolean("integerScale", integerScale);
        PREFS.putBoolean("showGrid",     showGrid);
        // Sonido
        PREFS.putInt    ("masterVolume", masterVolume);
        PREFS.putBoolean("muted",        muted);
        PREFS.putBoolean("ch1On",        ch1On);
        PREFS.putBoolean("ch2On",        ch2On);
        PREFS.putBoolean("ch3On",        ch3On);
        PREFS.putBoolean("ch4On",        ch4On);
        PREFS.putInt    ("stereoMode",   stereoMode);
        PREFS.putInt    ("bassBoost",    bassBoost);
        PREFS.putBoolean("reverb",       reverb);
        // General
        PREFS.putBoolean("pauseOnFocusLoss", pauseOnFocusLoss);
        PREFS.putBoolean("showStatusBar",showStatusBar);
        PREFS.put       ("lastRomPath",  lastRomPath);
        // Keys
        PREFS.putInt("keyA",      keyA);      PREFS.putInt("keyB",      keyB);
        PREFS.putInt("keyStart",  keyStart);  PREFS.putInt("keySelect", keySelect);
        PREFS.putInt("keyUp",     keyUp);     PREFS.putInt("keyDown",   keyDown);
        PREFS.putInt("keyLeft",   keyLeft);   PREFS.putInt("keyRight",  keyRight);
        PREFS.putInt("keyL",      keyL);      PREFS.putInt("keyR",      keyR);
        PREFS.putBoolean("forceGBC", forceGBC);
        try { PREFS.flush(); } catch (Exception ignored) {}
    }

    public final void load() {
        scale        = PREFS.getInt    ("scale",        3);
        filterMode   = PREFS.getInt    ("filterMode",   0);
        keepAspect   = PREFS.getBoolean("keepAspect",   true);
        showFPS      = PREFS.getBoolean("showFPS",      true);
        dmgPalette   = PREFS.getInt    ("dmgPalette",   0);
        brightness   = PREFS.getInt    ("brightness",   100);
        contrast     = PREFS.getInt    ("contrast",     100);
        saturation   = PREFS.getInt    ("saturation",   100);
        integerScale = PREFS.getBoolean("integerScale", true);
        showGrid     = PREFS.getBoolean("showGrid",     false);
        masterVolume = PREFS.getInt    ("masterVolume", 100);
        muted        = PREFS.getBoolean("muted",        false);
        ch1On        = PREFS.getBoolean("ch1On",        true);
        ch2On        = PREFS.getBoolean("ch2On",        true);
        ch3On        = PREFS.getBoolean("ch3On",        true);
        ch4On        = PREFS.getBoolean("ch4On",        true);
        stereoMode   = PREFS.getInt    ("stereoMode",   0);
        bassBoost    = PREFS.getInt    ("bassBoost",    0);
        reverb       = PREFS.getBoolean("reverb",       false);
        pauseOnFocusLoss = PREFS.getBoolean("pauseOnFocusLoss", true);
        showStatusBar    = PREFS.getBoolean("showStatusBar", true);
        lastRomPath      = PREFS.get       ("lastRomPath",   "");
        keyA      = PREFS.getInt("keyA",      java.awt.event.KeyEvent.VK_Z);
        keyB      = PREFS.getInt("keyB",      java.awt.event.KeyEvent.VK_X);
        keyStart  = PREFS.getInt("keyStart",  java.awt.event.KeyEvent.VK_ENTER);
        keySelect = PREFS.getInt("keySelect", java.awt.event.KeyEvent.VK_BACK_SPACE);
        keyUp     = PREFS.getInt("keyUp",     java.awt.event.KeyEvent.VK_UP);
        keyDown   = PREFS.getInt("keyDown",   java.awt.event.KeyEvent.VK_DOWN);
        keyLeft   = PREFS.getInt("keyLeft",   java.awt.event.KeyEvent.VK_LEFT);
        keyRight  = PREFS.getInt("keyRight",  java.awt.event.KeyEvent.VK_RIGHT);
        keyL      = PREFS.getInt("keyL",      java.awt.event.KeyEvent.VK_A);
        keyR      = PREFS.getInt("keyR",      java.awt.event.KeyEvent.VK_S);
        forceGBC  = PREFS.getBoolean("forceGBC", false);
        // Clamp values
        scale        = Math.max(1, Math.min(4,   scale));
        filterMode   = Math.max(0, Math.min(6,   filterMode));
        dmgPalette   = Math.max(0, Math.min(5,   dmgPalette));
        brightness   = Math.max(0, Math.min(200, brightness));
        contrast     = Math.max(0, Math.min(200, contrast));
        saturation   = Math.max(0, Math.min(200, saturation));
        masterVolume = Math.max(0, Math.min(100, masterVolume));
        stereoMode   = Math.max(0, Math.min(2,   stereoMode));
        bassBoost    = Math.max(-5,Math.min(5,   bassBoost));
    }

    public void reset() {
        scale=3; filterMode=0; keepAspect=true; showFPS=true; dmgPalette=0;
        brightness=100; contrast=100; saturation=100; integerScale=true; showGrid=false;
        masterVolume=100; muted=false; ch1On=true; ch2On=true; ch3On=true; ch4On=true;
        stereoMode=0; bassBoost=0; reverb=false;
        pauseOnFocusLoss=true; showStatusBar=true; forceGBC=false;
        keyA=java.awt.event.KeyEvent.VK_Z; keyB=java.awt.event.KeyEvent.VK_X;
        keyStart=java.awt.event.KeyEvent.VK_ENTER; keySelect=java.awt.event.KeyEvent.VK_BACK_SPACE;
        keyUp=java.awt.event.KeyEvent.VK_UP; keyDown=java.awt.event.KeyEvent.VK_DOWN;
        keyLeft=java.awt.event.KeyEvent.VK_LEFT; keyRight=java.awt.event.KeyEvent.VK_RIGHT;
        keyL=java.awt.event.KeyEvent.VK_A; keyR=java.awt.event.KeyEvent.VK_S;
        save();
    }
}
