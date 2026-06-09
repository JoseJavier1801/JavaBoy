package com.emulator.memory.cartridge;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * Cartridge base class + factory.
 *
 * Battery save (RAM persistente):
 *   Al cargar la ROM se busca un fichero <rom>.sav junto a ella.
 *   Si existe se carga en la RAM del cartucho.
 *   Cada vez que el juego escribe en la RAM del cartucho se programa
 *   un guardado diferido 2 s después (debounce), para no machacar el
 *   disco en cada ciclo de CPU. Al cerrar el emulador se fuerza un
 *   flush inmediato vía saveBattery().
 *
 * Esto permite que juegos como Pokémon guarden y carguen partidas
 * usando el menú interno del juego.
 */
public abstract class Cartridge {

    protected final int[] rom;
    protected int[]       ram;

    // Ruta del fichero .sav (null si la ROM no tiene RAM)
    private Path savePath;

    // Guardado diferido: esperamos 2 s desde el último write antes de escribir
    private static final ScheduledExecutorService SAVER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Cart-Saver");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> pendingSave;
    private volatile boolean ramDirty = false;

    private com.emulator.GBCMode gbcMode = com.emulator.GBCMode.DMG;

    protected Cartridge(int[] rom, int[] ram) {
        this.rom = rom;
        this.ram = ram;
    }

    public com.emulator.GBCMode getGBCMode() { return gbcMode; }

    // ── Interfaz abstracta ────────────────────────────────────────────────────

    public abstract int  read(int addr);
    public abstract void write(int addr, int val);

    // ── Acceso a RAM externa (subclases pueden sobreescribir) ─────────────────

    public int readRAM(int addr) {
        if (ram.length == 0) return 0xFF;
        return ram[(addr - 0xA000) % ram.length];
    }

    /** Escribe en la RAM y marca dirty para el guardado diferido */
    public void writeRAM(int addr, int val) {
        if (ram.length == 0) return;
        ram[(addr - 0xA000) % ram.length] = val;
        scheduleSave();
    }

    // ── Save state (para save states del emulador, no del juego) ─────────────

    public int[] getRam()             { return ram.clone(); }
    public void  setRam(int[] r)      {
        if (r != null && r.length == ram.length) System.arraycopy(r, 0, ram, 0, ram.length);
    }

    // ── Battery save / load ───────────────────────────────────────────────────

    /**
     * Llama a esto justo después de construir el cartucho.
     * Carga el .sav si existe y registra un shutdown hook para guardado final.
     */
    void initBattery(Path romFilePath) {
        if (ram.length == 0) return;   // sin RAM → nada que persistir

        savePath = romFilePath.resolveSibling(
            stripExtension(romFilePath.getFileName().toString()) + ".sav");

        // Cargar partida existente
        if (Files.exists(savePath)) {
            try {
                byte[] data = Files.readAllBytes(savePath);
                for (int i = 0; i < Math.min(data.length, ram.length); i++)
                    ram[i] = data[i] & 0xFF;
                System.out.println("[Cart] Partida cargada desde " + savePath.getFileName());
            } catch (IOException e) {
                System.err.println("[Cart] No se pudo leer el .sav: " + e.getMessage());
            }
        }

        // Guardar al cerrar la JVM (Escape, cierre de ventana, etc.)
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveBatteryNow, "Cart-ShutdownSave"));
    }

    /** Guarda inmediatamente la RAM en disco. Llámalo al cerrar el emulador. */
    public void saveBattery() {
        if (pendingSave != null) pendingSave.cancel(false);
        saveBatteryNow();
    }

    protected void scheduleSave() {
        if (savePath == null) return;
        ramDirty = true;
        if (pendingSave != null) pendingSave.cancel(false);
        pendingSave = SAVER.schedule(this::saveBatteryNow, 2, TimeUnit.SECONDS);
    }

    private synchronized void saveBatteryNow() {
        if (savePath == null || !ramDirty) return;
        try {
            byte[] data = new byte[ram.length];
            for (int i = 0; i < ram.length; i++) data[i] = (byte) ram[i];
            Files.write(savePath, data);
            ramDirty = false;
            System.out.println("[Cart] Partida guardada → " + savePath.getFileName());
        } catch (IOException e) {
            System.err.println("[Cart] Error al guardar .sav: " + e.getMessage());
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static Cartridge load(String romPath) throws IOException {
        Path path = Paths.get(romPath);
        byte[] raw = Files.readAllBytes(path);
        int[]  rom = new int[raw.length];
        for (int i = 0; i < raw.length; i++) rom[i] = raw[i] & 0xFF;

        int type  = rom[0x0147];
        int ramSz = ramSize(rom[0x0149]);
        int[] ram = new int[ramSz];

        StringBuilder title = new StringBuilder();
        for (int i = 0x134; i <= 0x143 && i < rom.length && rom[i] != 0; i++)
            title.append((char) rom[i]);
        System.out.printf("[Cart] %-16s | Tipo:0x%02X | ROM:%dKB | RAM:%dKB%n",
            title, type, (32 << Math.min(rom[0x0148], 8)), ramSz / 1024);

        Cartridge cart = switch (type) {
            case 0x00                            -> new NoMBC(rom, ram);
            case 0x01, 0x02, 0x03               -> new MBC1(rom, ram);
            case 0x05, 0x06                      -> new MBC2(rom);
            case 0x0F, 0x10, 0x11, 0x12, 0x13   -> new MBC3(rom, ram);
            case 0x19, 0x1A, 0x1B,
                 0x1C, 0x1D, 0x1E               -> new MBC5(rom, ram);
            default -> {
                System.out.println("[Cart] MBC desconocido 0x" +
                    Integer.toHexString(type) + " — usando NoMBC");
                yield new NoMBC(rom, ram);
            }
        };

        // Detectar modo GBC
        cart.gbcMode = com.emulator.GBCMode.fromHeader(rom[0x0143]);
        System.out.println("[Cart] Modo: " + cart.gbcMode);

        // Inicializar persistencia de batería
        cart.initBattery(path);
        return cart;
    }

    private static int ramSize(int code) {
        return switch (code) {
            case 0x01 -> 2  * 1024;
            case 0x02 -> 8  * 1024;
            case 0x03 -> 32 * 1024;
            case 0x04 -> 128 * 1024;
            case 0x05 -> 64 * 1024;
            default   -> 0;
        };
    }
}
