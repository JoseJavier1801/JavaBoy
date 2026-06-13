# 🎮 Game Boy Emulator — Java

Emulador completo del Game Boy DMG con audio arreglado, launcher gráfico y sistema de guardado.

## Compilar y ejecutar (3 pasos)

### Linux / macOS
```bash
# 1. Instalar JDK si no lo tienes
sudo apt install default-jdk        # Ubuntu/Debian
brew install openjdk@17             # macOS

# 2. Compilar
chmod +x build.sh && ./build.sh

# 3. Ejecutar
java -jar GameBoyEmulator.jar
# o doble-clic en GameBoyEmulator.jar
```

### Windows
```bat
REM 1. Instalar JDK
winget install Microsoft.OpenJDK.17

REM 2. Compilar (doble-clic en build.bat o desde cmd)
build.bat

REM 3. Ejecutar
javaw -jar GameBoyEmulator.jar
```

### Con Maven (alternativo)
```bash
mvn package -q
java -jar target/GameBoyEmulator.jar
```

## Al abrirlo

Al ejecutar sin argumentos, aparece el **Launcher**:
- Arrastra un fichero `.gb` a la ventana, o
- Haz clic en **Abrir ROM…** para elegir con el explorador
- Las ROMs recientes se guardan automáticamente

## Controles

| Teclado        | Game Boy    |
|----------------|-------------|
| ← ↑ → ↓        | D-Pad       |
| Z              | Botón A     |
| X              | Botón B     |
| Enter          | Start       |
| Backspace      | Select      |
| **P**          | Pausa       |
| **F1–F8**      | Guardar partida slot 1–8 |
| **Shift+F1–F8**| Cargar partida slot 1–8  |
| Clic izq sidebar | Guardar slot |
| Clic der sidebar | Cargar slot  |
| Escape         | Volver al launcher |

## Mejoras de audio (v2)

- **Hilo de audio dedicado** — la generación de PCM no bloquea nunca el hilo de CPU
- **High-pass filter** — elimina la componente DC que causaba el crackling al silenciar canales
- **Low-pass filter** — suaviza el aliasing de las ondas cuadradas
- **Cola no-bloqueante** — si el SO tarda en consumir audio, se descarta (mejor que congelar)
- **Volúmenes normalizados** — cada canal contribuye máx. 25% → sin overflow/saturación

## Estructura

```
src/main/java/com/emulator/
├── GameBoy.java          ← Bucle principal + main()
├── cpu/                  ← CPU LR35902 completa (~500 opcodes)
├── memory/               ← MemoryBus + NoMBC/MBC1/2/3/5
├── ppu/                  ← PPU 4 modos, sprites, scroll
├── apu/                  ← APU 4 canales (audio arreglado)
├── timer/                ← Timer con overflow delay correcto
├── input/                ← Joypad (registro 0xFF00)
├── savestate/            ← Guardado/carga de partida (.sav)
└── ui/                   ← Launcher + ventana de juego (Swing)
```

## Compatibilidad MBC

| Tipo | Juegos ejemplo |
|------|---------------|
| NoMBC | Tetris, Dr. Mario |
| MBC1  | Super Mario Land, Kirby |
| MBC2  | Pokémon Red/Blue (pequeños) |
| MBC3  | Pokémon Gold/Silver, Zelda Link's Awakening |
| MBC5  | Pokémon Crystal, Mario Bros Deluxe |

Las ROMs no están incluidas. Usa tus propias copias legales (`.gb`).
