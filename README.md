# 🎮 JavaBoy — Emulador de Game Boy y GameBoy Color hecho en java

Emulador completo del Game Boy  y Game Boy Color programado en java

## Compilar y ejecutar 

### Con Maven
```bash
mvn package -q
java -jar target/GameBoyEmulator.jar
```

## Al abrirlo

Al ejecutar sin argumentos, aparece el **Launcher**:
- Arrastra un fichero `.gb` o `.gbc` a la ventana, o
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

Las ROMs no están incluidas. Usa tus propias copias legales.
