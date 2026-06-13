package com.emulator.ui;

import com.emulator.GameBoy;
import com.emulator.GBCMode;
import com.emulator.input.Joypad;
import com.emulator.input.Joypad.Button;
import com.emulator.savestate.SaveStateManager;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.AlphaComposite;
import java.awt.RadialGradientPaint;

/**
 * Ventana principal estilo JavaBoy.
 * Todas las opciones son funcionales y se persisten automáticamente
 * via EmulatorSettings (java.util.prefs).
 */
public class GameBoyWindow extends JFrame {

    private static final int BASE_W = 160, BASE_H = 144;

    // ── VBA colours ───────────────────────────────────────────────────────────
    private static final Color C_DARK  = new Color(128,128,128);
    private static final Color C_LIGHT = new Color(255,255,255);
    private static final Color C_STBG  = new Color(236,233,216);

    // ── DMG palettes (6 options) ──────────────────────────────────────────────
    public static final int[][] DMG_PALETTES = {
        {0xFF9BBC0F,0xFF8BAC0F,0xFF306230,0xFF0F380F}, // 0: Verde GB clásico
        {0xFFE8E8D0,0xFFA8A888,0xFF505030,0xFF101008}, // 1: Gris cálido
        {0xFFFFF6D3,0xFFF9A875,0xFFEB6B6F,0xFF7C3F58}, // 2: Pokémon GBC (rojo/coral)
        {0xFF8BE5FF,0xFF608FCF,0xFF20408F,0xFF040C2C}, // 3: Azul Pokémon (azul)
        {0xFFE0F8D0,0xFF88C070,0xFF346856,0xFF081820}, // 4: Verde agua GBC
        {0xFFF5E8B0,0xFFD4B060,0xFF906820,0xFF402800}, // 5: Sepia / Dorado
        {0xFF00FF88,0xFF00CC66,0xFF009944,0xFF003318}, // 6: Verde neón
        {0xFFFFD0D0,0xFFFF6060,0xFF880000,0xFF200000}, // 7: Rojo retro
        {0xFFD0D8FF,0xFF7090E0,0xFF204090,0xFF080820}, // 8: Azul fría
    };
    static final String[] PALETTE_NAMES = {
        "Verde clásico GB","Gris cálido","Pokémon Rojo/Fuego",
        "Pokémon Azul/Agua","Verde agua GBC","Sepia / Dorado",
        "Verde neón","Rojo retro","Azul fría"
    };
    static final String[] FILTER_NAMES = {
        "Nearest Neighbor","Sharp Bilinear","Scale2x (EPX)",
        "HQ2x","CRT Phosphor","LCD Dot-Matrix","Pixel Perfect"
    };
    static final String[] STEREO_NAMES = {"Estéreo","Mono","Canales invertidos"};

    // ── Components ────────────────────────────────────────────────────────────
    private final GameBoy          gb;
    private final Joypad           joypad;
    private final SaveStateManager saves;
    private final GBCMode          mode;
    private final String           romName;
    private final EmulatorSettings cfg = EmulatorSettings.get();

    private final GamePanel gamePanel = new GamePanel();
    private final JLabel    sRom   = seg("", 200);
    private final JLabel    sMode  = seg("", 50);
    private final JLabel    sFps   = seg("0 FPS", 62);
    private final JLabel    sState = seg("Running", 90);
    private final JPanel    statusPanel;

    private int   frameCount = 0;
    private long  fpsMark    = System.currentTimeMillis();
    private javax.swing.Timer fpsTimer;
    private int   currentSlot = 1;

    // ── Constructor ───────────────────────────────────────────────────────────
    public GameBoyWindow(GameBoy gb, Joypad joypad,
                         SaveStateManager saves, GBCMode mode) {
        super("JavaBoy");
        this.gb      = gb; this.joypad = joypad;
        this.saves   = saves; this.mode = mode;
        this.romName = saves.getRomName();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { doExit(); }
            @Override public void windowDeactivated(WindowEvent e) {
                if (cfg.pauseOnFocusLoss && !gb.isPaused()) {
                    gb.setPaused(true); sState.setText(" Paused"); }
            }
            @Override public void windowActivated(WindowEvent e) {
                if (cfg.pauseOnFocusLoss && gb.isPaused()) {
                    gb.setPaused(false); sState.setText(" Running"); }
            }
        });

        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        statusPanel = buildStatusBar();
        buildMenuBar();
        buildLayout();
        setupKeys();
        startFPS();
        applyAllSettings();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APPLY / SAVE settings
    // ─────────────────────────────────────────────────────────────────────────
    private void applyAllSettings() {
        // Video
        gamePanel.setFilter(cfg.filterMode);
        gamePanel.setKeepAspect(cfg.keepAspect);
        gamePanel.setShowGrid(cfg.showGrid);
        gamePanel.setBCS(cfg.brightness, cfg.contrast, cfg.saturation);
        // Apply palette to GamePanel (visual post-processing filter)
        // For forceGBC: also push palette to PPU so it renders in colour
        gamePanel.setPalette(null);  // reset first
        if (cfg.forceGBC && mode == GBCMode.DMG) {
            // Update PPU to use chosen palette colours
            gb.getPPU().setDMGColorized(true, DMG_PALETTES[
                Math.max(0, Math.min(cfg.dmgPalette, DMG_PALETTES.length-1))]);
        } else if (mode == GBCMode.DMG) {
            gamePanel.setPalette(DMG_PALETTES[cfg.dmgPalette]);
        }
        applyScale(cfg.scale);
        sFps.setVisible(cfg.showFPS);
        statusPanel.setVisible(cfg.showStatusBar);
        // Sound
        gb.getAPU().setMasterVolume(cfg.masterVolume / 100f);
        gb.getAPU().setMuted(cfg.muted);
        gb.getAPU().setChannels(cfg.ch1On, cfg.ch2On, cfg.ch3On, cfg.ch4On);
        gb.getAPU().setStereoMode(cfg.stereoMode);
        gb.getAPU().setBassBoost(cfg.bassBoost);
        gb.getAPU().setReverb(cfg.reverb);

        sMode.setText(" " + (mode == GBCMode.GBC ? "GBC" : "DMG"));
        sRom.setText(" " + romName);
        updateTitle();
    }

    private void saveSettings() { cfg.save(); }

    // ─────────────────────────────────────────────────────────────────────────
    // MENU BAR
    // ─────────────────────────────────────────────────────────────────────────
    private void buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.add(menuFile());
        bar.add(menuEmulation());
        bar.add(menuVideo());
        bar.add(menuSound());
        bar.add(menuCheats());
        bar.add(menuHelp());
        setJMenuBar(bar);
    }

    // ── File ─────────────────────────────────────────────────────────────────
    private JMenu menuFile() {
        JMenu m = menu("File");
        m.add(it("Open...", "ctrl O", this::doExit));
        m.add(it("Reiniciar ROM", "ctrl R", this::restartROM));
        m.addSeparator();
        JMenu ld = menu("Load State"), sv = menu("Save State");
        for (int i=1;i<=8;i++){final int s=i;
            ld.add(it("Slot "+i+"  F"+i,"",()->loadSlot(s)));
            sv.add(it("Slot "+i+"  Shift+F"+i,"",()->saveSlot(s)));}
        m.add(ld); m.add(sv);
        m.addSeparator();
        m.add(it("Close","",this::doExit));
        m.add(it("Exit","",()->{gb.stop();System.exit(0);}));
        return m;
    }

    // ── Emulation ────────────────────────────────────────────────────────────
    private JMenu menuEmulation() {
        JMenu m = menu("Emulation");
        JCheckBoxMenuItem p = new JCheckBoxMenuItem("Pause");
        p.setAccelerator(KeyStroke.getKeyStroke("P"));
        p.addActionListener(e->{ gb.setPaused(p.isSelected()); sState.setText(gb.isPaused()?" Paused":" Running"); });
        m.add(p);
        m.add(it("Reset","ctrl R",this::restartROM));
        m.addSeparator();
        JCheckBoxMenuItem fl = new JCheckBoxMenuItem("Pausa al perder foco", cfg.pauseOnFocusLoss);
        fl.addActionListener(e->{ cfg.pauseOnFocusLoss=fl.isSelected(); saveSettings(); });
        m.add(fl);
        return m;
    }

    // ── Video ─────────────────────────────────────────────────────────────────
    private JMenu menuVideo() {
        JMenu m = menu("Options");

        // Tamaño
        JMenu sz = menu("Video Size");
        ButtonGroup bsz = new ButtonGroup();
        for (int s : new int[]{1,2,3,4}) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(
                s+"x  ("+BASE_W*s+"×"+BASE_H*s+")", s==cfg.scale);
            r.setAccelerator(KeyStroke.getKeyStroke("ctrl "+s));
            final int fs=s; r.addActionListener(e->{ cfg.scale=fs; applyScale(fs); saveSettings(); });
            bsz.add(r); sz.add(r);
        }
        m.add(sz);

        // Filtro
        JMenu fl = menu("Video Filter");
        ButtonGroup bfl = new ButtonGroup();
        for (int i=0;i<FILTER_NAMES.length;i++) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(FILTER_NAMES[i], i==cfg.filterMode);
            final int fi=i; r.addActionListener(e->{ cfg.filterMode=fi; gamePanel.setFilter(fi); saveSettings(); });
            bfl.add(r); fl.add(r);
        }
        m.add(fl);

        m.addSeparator();

        // Proporción
        JCheckBoxMenuItem asp = new JCheckBoxMenuItem("Mantener proporción (10:9)", cfg.keepAspect);
        asp.addActionListener(e->{ cfg.keepAspect=asp.isSelected(); gamePanel.setKeepAspect(cfg.keepAspect); saveSettings(); });
        m.add(asp);

        // Cuadrícula
        JCheckBoxMenuItem grid = new JCheckBoxMenuItem("Cuadrícula de píxeles", cfg.showGrid);
        grid.addActionListener(e->{ cfg.showGrid=grid.isSelected(); gamePanel.setShowGrid(cfg.showGrid); saveSettings(); });
        m.add(grid);

        m.addSeparator();

        // Brillo/Contraste/Saturación
        m.add(it("Brillo, Contraste, Saturación...", "", this::showBCSDialog));

        // Paleta DMG
        if (mode == GBCMode.DMG) {
            m.addSeparator();
            JMenu pal = menu("Paleta DMG");
            ButtonGroup bp = new ButtonGroup();
            for (int i=0;i<PALETTE_NAMES.length;i++) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(PALETTE_NAMES[i], i==cfg.dmgPalette);
                final int pi=i; r.addActionListener(e->{ cfg.dmgPalette=pi;
                    gamePanel.setPalette(DMG_PALETTES[pi]); saveSettings(); });
                bp.add(r); pal.add(r);
            }
            m.add(pal);
        }

        m.addSeparator();

        // FPS / Status bar
        JCheckBoxMenuItem fps = new JCheckBoxMenuItem("Mostrar FPS", cfg.showFPS);
        fps.addActionListener(e->{ cfg.showFPS=fps.isSelected(); sFps.setVisible(cfg.showFPS); saveSettings(); });
        m.add(fps);

        JCheckBoxMenuItem sb = new JCheckBoxMenuItem("Mostrar barra de estado", cfg.showStatusBar);
        sb.addActionListener(e->{ cfg.showStatusBar=sb.isSelected(); statusPanel.setVisible(cfg.showStatusBar); pack(); saveSettings(); });
        m.add(sb);

        m.addSeparator();
        m.add(it("Configurar controles...","ctrl K",this::showKeyBindingsDialog));
        // GBC colour mode for DMG ROMs
        m.addSeparator();
        JCheckBoxMenuItem forceGBC = new JCheckBoxMenuItem(
            "Modo GBC en juegos GB (forzar colores)", cfg.forceGBC);
        forceGBC.setToolTipText(
            "Aplica la paleta DMG seleccionada sobre juegos de Game Boy clásico.\n"
            + "Reinicia la ROM para que el cambio tenga efecto.");
        forceGBC.addActionListener(e -> {
            cfg.forceGBC = forceGBC.isSelected();
            // Si paleta 0 (verde clásico = igual a DMG), cambiar a paleta visible
            if (cfg.forceGBC && cfg.dmgPalette == 0) cfg.dmgPalette = 2;
            saveSettings();
            String palName = PALETTE_NAMES[Math.min(cfg.dmgPalette, PALETTE_NAMES.length-1)];
            int r = JOptionPane.showConfirmDialog(this,
                "Modo color " + (cfg.forceGBC ? "activado" : "desactivado")
                + (cfg.forceGBC ? " con paleta: " + palName : "") + "."
                + "\nReinicia la ROM para aplicar el cambio."
                + "\n\n¿Reiniciar ahora?",
                "Modo color para juegos GB", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) restartROM();
        });
        m.add(forceGBC);
        m.add(it("Restablecer ajustes","",this::resetSettings));
        return m;
    }

    // ── Sound ─────────────────────────────────────────────────────────────────
    private JMenu menuSound() {
        JMenu m = menu("Sound");

        // Mute
        JCheckBoxMenuItem mute = new JCheckBoxMenuItem("Silenciar (Mute)", cfg.muted);
        mute.setAccelerator(KeyStroke.getKeyStroke("ctrl M"));
        mute.addActionListener(e->{ cfg.muted=mute.isSelected(); gb.getAPU().setMuted(cfg.muted);
            sState.setText(cfg.muted?" Muted":" Running"); saveSettings(); });
        m.add(mute);

        m.addSeparator();
        m.add(it("Ajustar sonido...","ctrl shift S",()->showSoundDialog(mute)));
        m.addSeparator();

        // Presets rápidos
        JMenu vp = menu("Volumen rápido");
        for (int p : new int[]{25,50,75,100}) { final int pv=p;
            vp.add(it(p+"%","",()->{ cfg.masterVolume=pv; cfg.muted=false; mute.setSelected(false);
                gb.getAPU().setMasterVolume(pv/100f); gb.getAPU().setMuted(false); saveSettings(); }));
        }
        m.add(vp);

        // Estéreo
        JMenu st = menu("Modo estéreo");
        ButtonGroup bst = new ButtonGroup();
        for (int i=0;i<STEREO_NAMES.length;i++) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(STEREO_NAMES[i], i==cfg.stereoMode);
            final int si=i; r.addActionListener(e->{ cfg.stereoMode=si;
                gb.getAPU().setStereoMode(si); saveSettings(); });
            bst.add(r); st.add(r);
        }
        m.add(st);

        return m;
    }

    // ── Cheats ────────────────────────────────────────────────────────────────
    private JMenu menuCheats() {
        JMenu m = menu("Cheats");
        m.add(it("Lista de cheats...","",()->JOptionPane.showMessageDialog(this,
            "Cheat codes no implementados.","Cheats",JOptionPane.INFORMATION_MESSAGE)));
        return m;
    }

    // ── Help ─────────────────────────────────────────────────────────────────
    private JMenu menuHelp() {
        JMenu m = menu("Help");
        m.add(it("About JavaBoy...","F1",this::showAbout));
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DIALOGS
    // ─────────────────────────────────────────────────────────────────────────

    /** Diálogo Brillo / Contraste / Saturación */
    private void showBCSDialog() {
        JDialog d = new JDialog(this, "Ajustes de imagen", false);
        d.setLayout(new BorderLayout());
        d.setResizable(false);

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(12,16,8,16));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5,4,5,4);
        gc.anchor = GridBagConstraints.WEST;

        // Helper que crea una fila label + slider + label valor
        Object[] rows = {
            "Brillo",     0, 200, cfg.brightness,
            "Contraste",  0, 200, cfg.contrast,
            "Saturación", 0, 200, cfg.saturation,
        };
        JSlider[] sliders = new JSlider[3];
        JLabel[]  valLbls = new JLabel[3];
        String[]  names   = {"Brillo","Contraste","Saturación"};

        for (int i=0;i<3;i++) {
            int min=(int)rows[i*4+1], max=(int)rows[i*4+2], val=(int)rows[i*4+3];
            gc.gridx=0; gc.gridy=i; gc.gridwidth=1;
            p.add(new JLabel(names[i]+":"), gc);

            JSlider sl = new JSlider(min, max, val);
            sl.setPreferredSize(new Dimension(240,40));
            sl.setMajorTickSpacing(50); sl.setMinorTickSpacing(10);
            sl.setPaintTicks(true); sl.setPaintLabels(true);
            sliders[i] = sl;
            gc.gridx=1; p.add(sl, gc);

            JLabel vl = new JLabel(val+"%");
            vl.setPreferredSize(new Dimension(38,20));
            vl.setFont(new Font("Tahoma",Font.BOLD,11));
            valLbls[i] = vl;
            gc.gridx=2; p.add(vl, gc);
        }

        // Live update
        sliders[0].addChangeListener(e->{ cfg.brightness=sliders[0].getValue();
            valLbls[0].setText(cfg.brightness+"%"); gamePanel.setBCS(cfg.brightness,cfg.contrast,cfg.saturation); });
        sliders[1].addChangeListener(e->{ cfg.contrast=sliders[1].getValue();
            valLbls[1].setText(cfg.contrast+"%");   gamePanel.setBCS(cfg.brightness,cfg.contrast,cfg.saturation); });
        sliders[2].addChangeListener(e->{ cfg.saturation=sliders[2].getValue();
            valLbls[2].setText(cfg.saturation+"%"); gamePanel.setBCS(cfg.brightness,cfg.contrast,cfg.saturation); });

        // Botón reset imagen
        gc.gridx=0; gc.gridy=3; gc.gridwidth=3;
        JButton reset = new JButton("Restablecer (100/100/100)");
        reset.addActionListener(e->{ cfg.brightness=100; cfg.contrast=100; cfg.saturation=100;
            sliders[0].setValue(100); sliders[1].setValue(100); sliders[2].setValue(100);
            gamePanel.setBCS(100,100,100); });
        p.add(reset, gc);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,6));
        JButton ok=new JButton("OK"); ok.setPreferredSize(new Dimension(75,23));
        JButton cancel=new JButton("Cancel"); cancel.setPreferredSize(new Dimension(75,23));
        // Remember original for cancel
        int ob=cfg.brightness, oc=cfg.contrast, os=cfg.saturation;
        ok.addActionListener(e->{ saveSettings(); d.dispose(); });
        cancel.addActionListener(e->{ cfg.brightness=ob; cfg.contrast=oc; cfg.saturation=os;
            gamePanel.setBCS(ob,oc,os); d.dispose(); });
        btns.add(ok); btns.add(cancel);

        d.add(p, BorderLayout.CENTER);
        d.add(btns, BorderLayout.SOUTH);
        d.pack(); d.setLocationRelativeTo(this); d.setVisible(true);
    }

    /** Diálogo de sonido completo */
    private void showSoundDialog(JCheckBoxMenuItem muteMenu) {
        JDialog d = new JDialog(this, "Sound Options", true);
        d.setLayout(new BorderLayout());
        d.setResizable(false);

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(12,16,6,16));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4,4,4,4); gc.anchor = GridBagConstraints.WEST;

        // Mute
        JCheckBox muteChk = new JCheckBox("Silenciar (Mute)", cfg.muted);
        gc.gridx=0; gc.gridy=0; gc.gridwidth=3; p.add(muteChk, gc);

        // Volumen
        gc.gridy=1; gc.gridwidth=1; gc.gridx=0; p.add(new JLabel("Volumen:"), gc);
        JSlider volSl = new JSlider(0, 100, cfg.masterVolume);
        volSl.setPreferredSize(new Dimension(220,45));
        volSl.setMajorTickSpacing(25); volSl.setMinorTickSpacing(5);
        volSl.setPaintTicks(true); volSl.setPaintLabels(true);
        gc.gridx=1; p.add(volSl, gc);
        JLabel volLbl = new JLabel(cfg.masterVolume+"%");
        volLbl.setFont(new Font("Tahoma",Font.BOLD,12)); volLbl.setPreferredSize(new Dimension(42,20));
        gc.gridx=2; p.add(volLbl, gc);

        // Bass boost
        gc.gridy=2; gc.gridx=0; p.add(new JLabel("Graves:"), gc);
        JSlider bassSl = new JSlider(-5, 5, cfg.bassBoost);
        bassSl.setPreferredSize(new Dimension(220,45));
        bassSl.setMajorTickSpacing(5); bassSl.setPaintTicks(true); bassSl.setPaintLabels(true);
        gc.gridx=1; p.add(bassSl, gc);
        JLabel bassLbl = new JLabel((cfg.bassBoost>=0?"+":"")+cfg.bassBoost+" dB");
        bassLbl.setFont(new Font("Tahoma",Font.BOLD,11)); bassLbl.setPreferredSize(new Dimension(52,20));
        gc.gridx=2; p.add(bassLbl, gc);

        // Separador
        gc.gridy=3; gc.gridx=0; gc.gridwidth=3; p.add(new JSeparator(), gc);

        // Canales
        gc.gridy=4; gc.gridwidth=1;
        gc.gridx=0; p.add(new JLabel("Canales activos:"), gc);
        JCheckBox c1=new JCheckBox("CH1 Pulse A",cfg.ch1On);
        JCheckBox c2=new JCheckBox("CH2 Pulse B",cfg.ch2On);
        JCheckBox c3=new JCheckBox("CH3 Wave",   cfg.ch3On);
        JCheckBox c4=new JCheckBox("CH4 Noise",  cfg.ch4On);
        gc.gridy=5; gc.gridx=0; p.add(c1,gc); gc.gridx=1; p.add(c2,gc);
        gc.gridy=6; gc.gridx=0; p.add(c3,gc); gc.gridx=1; p.add(c4,gc);

        // Reverb
        JCheckBox reverb = new JCheckBox("Reverb", cfg.reverb);
        gc.gridy=7; gc.gridx=0; gc.gridwidth=2; p.add(reverb, gc);

        // Estéreo
        gc.gridy=8; gc.gridwidth=1; gc.gridx=0; p.add(new JLabel("Estéreo:"), gc);
        JComboBox<String> stereo = new JComboBox<>(STEREO_NAMES);
        stereo.setSelectedIndex(cfg.stereoMode);
        gc.gridx=1; gc.gridwidth=2; p.add(stereo, gc);

        // Live listeners
        volSl.addChangeListener(e->{volLbl.setText(volSl.getValue()+"%");
            if(!muteChk.isSelected()) gb.getAPU().setMasterVolume(volSl.getValue()/100f);});
        muteChk.addActionListener(e->{gb.getAPU().setMuted(muteChk.isSelected()); volSl.setEnabled(!muteChk.isSelected());});
        bassSl.addChangeListener(e->{
            int v=bassSl.getValue(); bassLbl.setText((v>=0?"+":"")+v+" dB");
            gb.getAPU().setBassBoost(v); });
        c1.addActionListener(e->gb.getAPU().setChannels(c1.isSelected(),c2.isSelected(),c3.isSelected(),c4.isSelected()));
        c2.addActionListener(e->gb.getAPU().setChannels(c1.isSelected(),c2.isSelected(),c3.isSelected(),c4.isSelected()));
        c3.addActionListener(e->gb.getAPU().setChannels(c1.isSelected(),c2.isSelected(),c3.isSelected(),c4.isSelected()));
        c4.addActionListener(e->gb.getAPU().setChannels(c1.isSelected(),c2.isSelected(),c3.isSelected(),c4.isSelected()));
        reverb.addActionListener(e->gb.getAPU().setReverb(reverb.isSelected()));
        stereo.addActionListener(e->gb.getAPU().setStereoMode(stereo.getSelectedIndex()));

        // Buttons
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,6));
        JButton ok=new JButton("OK"); ok.setPreferredSize(new Dimension(75,23));
        JButton cancel=new JButton("Cancel"); cancel.setPreferredSize(new Dimension(75,23));
        int ov=cfg.masterVolume; boolean om=cfg.muted; int ob=cfg.bassBoost;
        ok.addActionListener(e->{
            cfg.masterVolume=volSl.getValue(); cfg.muted=muteChk.isSelected();
            cfg.ch1On=c1.isSelected(); cfg.ch2On=c2.isSelected();
            cfg.ch3On=c3.isSelected(); cfg.ch4On=c4.isSelected();
            cfg.bassBoost=bassSl.getValue(); cfg.reverb=reverb.isSelected();
            cfg.stereoMode=stereo.getSelectedIndex();
            muteMenu.setSelected(cfg.muted);
            saveSettings(); d.dispose();
        });
        cancel.addActionListener(e->{
            gb.getAPU().setMasterVolume(ov/100f); gb.getAPU().setMuted(om);
            gb.getAPU().setBassBoost(ob);
            d.dispose();
        });
        btns.add(ok); btns.add(cancel);
        d.add(p,BorderLayout.CENTER); d.add(btns,BorderLayout.SOUTH);
        d.pack(); d.setLocationRelativeTo(this); d.setVisible(true);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "JavaBoy (Java Clone)\nGame Boy / Color Emulator\n\n" +
            "ROM: "+romName+"\nMode: "+(mode==GBCMode.GBC?"Game Boy Color":"Game Boy")+
            "\nScale: "+cfg.scale+"x  |  Filter: "+FILTER_NAMES[cfg.filterMode],
            "About", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showKeyBindingsDialog() {
        JDialog dlg = new JDialog(this, "Configurar controles", true);
        dlg.setLayout(new BorderLayout(0,0));
        dlg.setResizable(false);

        String[] names  = {"A","B","Start","Select","Arriba","Abajo","Izquierda","Derecha"};
        int[] defaults  = {
            java.awt.event.KeyEvent.VK_Z,
            java.awt.event.KeyEvent.VK_X,
            java.awt.event.KeyEvent.VK_ENTER,
            java.awt.event.KeyEvent.VK_BACK_SPACE,
            java.awt.event.KeyEvent.VK_UP,
            java.awt.event.KeyEvent.VK_DOWN,
            java.awt.event.KeyEvent.VK_LEFT,
            java.awt.event.KeyEvent.VK_RIGHT
        };
        int[] current = {cfg.keyA,cfg.keyB,cfg.keyStart,cfg.keySelect,
                          cfg.keyUp,cfg.keyDown,cfg.keyLeft,cfg.keyRight};
        int[] edited  = current.clone();

        JButton[] btns = new JButton[8];
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(new EmptyBorder(12,16,8,16));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4,6,4,6); gc.anchor = GridBagConstraints.WEST;

        for (int i=0;i<8;i++) {
            final int idx=i;
            gc.gridx=0; gc.gridy=i; gc.weightx=0;
            JLabel lbl = new JLabel(names[i]+":");
            lbl.setPreferredSize(new Dimension(80,20));
            grid.add(lbl, gc);

            JButton b = new JButton(java.awt.event.KeyEvent.getKeyText(current[i]));
            b.setPreferredSize(new Dimension(130,26));
            b.setFont(new Font("Tahoma",Font.BOLD,12));
            b.setBackground(new Color(240,240,255));
            btns[i] = b;

            b.addActionListener(e -> {
                b.setText("Pulsa una tecla...");
                b.setBackground(new Color(255,255,180));
                b.requestFocusInWindow();
                b.addKeyListener(new KeyAdapter(){
                    @Override public void keyPressed(KeyEvent ke){
                        int code=ke.getKeyCode();
                        if(code==KeyEvent.VK_ESCAPE){b.setText(java.awt.event.KeyEvent.getKeyText(edited[idx]));b.setBackground(new Color(240,240,255));b.removeKeyListener(this);return;}
                        edited[idx]=code;
                        b.setText(java.awt.event.KeyEvent.getKeyText(code));
                        b.setBackground(new Color(200,255,200));
                        b.removeKeyListener(this);
                    }
                });
            });

            gc.gridx=1; gc.weightx=1;
            grid.add(b, gc);

            // Default button
            JButton def = new JButton("↺");
            def.setToolTipText("Restaurar por defecto");
            def.setPreferredSize(new Dimension(30,26));
            def.setFont(new Font("SansSerif",Font.PLAIN,13));
            final int di=i;
            def.addActionListener(e2->{
                edited[di]=defaults[di];
                btns[di].setText(java.awt.event.KeyEvent.getKeyText(defaults[di]));
                btns[di].setBackground(new Color(240,240,255));
            });
            gc.gridx=2; gc.weightx=0;
            grid.add(def, gc);
        }

        // Info panel
        JLabel info = new JLabel(
            "<html><small style='color:gray'>Haz clic en un botón y pulsa la tecla deseada.<br>"
            + "Pulsa Escape para cancelar la captura.</small></html>");
        gc.gridx=0; gc.gridy=8; gc.gridwidth=3; gc.insets=new Insets(8,6,0,6);
        grid.add(info, gc);

        JPanel btnsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,6));
        JButton ok=new JButton("OK"); ok.setPreferredSize(new Dimension(75,23));
        JButton cancel=new JButton("Cancel"); cancel.setPreferredSize(new Dimension(75,23));
        JButton resetAll=new JButton("Restablecer todos");

        ok.addActionListener(e->{
            cfg.keyA=edited[0]; cfg.keyB=edited[1];
            cfg.keyStart=edited[2]; cfg.keySelect=edited[3];
            cfg.keyUp=edited[4]; cfg.keyDown=edited[5];
            cfg.keyLeft=edited[6]; cfg.keyRight=edited[7];
            saveSettings(); dlg.dispose();
        });
        cancel.addActionListener(e->dlg.dispose());
        resetAll.addActionListener(e->{
            for(int i=0;i<8;i++){edited[i]=defaults[i];btns[i].setText(java.awt.event.KeyEvent.getKeyText(defaults[i]));btns[i].setBackground(new Color(240,240,255));}
        });

        btnsPanel.add(resetAll); btnsPanel.add(ok); btnsPanel.add(cancel);
        dlg.add(grid,BorderLayout.CENTER);
        dlg.add(btnsPanel,BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void resetSettings() {
        int r = JOptionPane.showConfirmDialog(this,
            "¿Restablecer todos los ajustes a los valores por defecto?",
            "Restablecer", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) { cfg.reset(); applyAllSettings(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LAYOUT
    // ─────────────────────────────────────────────────────────────────────────
    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.BLACK);
        root.add(gamePanel, BorderLayout.CENTER);
        root.add(statusPanel, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        bar.setBackground(C_STBG);
        bar.setBorder(BorderFactory.createMatteBorder(1,0,0,0,C_DARK));
        bar.setPreferredSize(new Dimension(0,22));
        bar.add(sRom); bar.add(vsep()); bar.add(sMode);
        bar.add(vsep()); bar.add(sFps);
        bar.add(vsep()); bar.add(sState);
        return bar;
    }

    private JPanel vsep() {
        return new JPanel(){
            {setPreferredSize(new Dimension(5,22));setBackground(C_STBG);setOpaque(true);}
            @Override protected void paintComponent(Graphics g){
                super.paintComponent(g);
                g.setColor(C_DARK); g.drawLine(1,2,1,getHeight()-3);
                g.setColor(C_LIGHT);g.drawLine(2,2,2,getHeight()-3);}
        };
    }

    private JLabel seg(String t, int w) {
        JLabel l = new JLabel(t){
            @Override protected void paintComponent(Graphics g){
                g.setColor(C_DARK); g.drawLine(0,0,getWidth()-1,0);g.drawLine(0,0,0,getHeight()-1);
                g.setColor(C_LIGHT);g.drawLine(getWidth()-1,0,getWidth()-1,getHeight()-1);g.drawLine(0,getHeight()-1,getWidth()-1,getHeight()-1);
                g.setColor(C_STBG); g.fillRect(1,1,getWidth()-2,getHeight()-2);
                super.paintComponent(g);}
        };
        l.setFont(new Font("Tahoma",Font.PLAIN,11));
        l.setForeground(Color.BLACK);
        l.setBorder(new EmptyBorder(2,5,2,5));
        l.setPreferredSize(new Dimension(w,22));
        l.setOpaque(false);
        return l;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCALE / FPS / FRAME
    // ─────────────────────────────────────────────────────────────────────────
    private void applyScale(int s) {
        gamePanel.setPreferredSize(new Dimension(BASE_W*s, BASE_H*s));
        pack(); updateTitle();
    }

    private void updateTitle() {
        setTitle("JavaBoy - "+romName+" ["+(mode==GBCMode.GBC?"GBC":"GB")+"] "+cfg.scale+"x");
    }

    private void startFPS() {
        fpsTimer = new javax.swing.Timer(1000, e->{
            if (cfg.showFPS) {
                long now=System.currentTimeMillis(), diff=now-fpsMark;
                if(diff>0) sFps.setText(" "+(frameCount*1000L/diff)+" FPS");
            }
            frameCount=0; fpsMark=System.currentTimeMillis();
        });
        fpsTimer.start();
    }

    public void onFrame(int[] pixels) { gamePanel.updatePixels(pixels); frameCount++; }

    // ─────────────────────────────────────────────────────────────────────────
    // SAVE / LOAD SLOT
    // ─────────────────────────────────────────────────────────────────────────
    private void saveSlot(int s){ currentSlot=s; gb.saveState(s); sState.setText(" Saved S"+s); }
    private void loadSlot(int s){ currentSlot=s; sState.setText(gb.loadState(s)?" Loaded S"+s:" S"+s+" empty"); }

    // ─────────────────────────────────────────────────────────────────────────
    // KEYS
    // ─────────────────────────────────────────────────────────────────────────
    private void setupKeys() {
        addKeyListener(new KeyAdapter(){
            @Override public void keyPressed(KeyEvent e)  { handleKey(e,true);  }
            @Override public void keyReleased(KeyEvent e) { handleKey(e,false); }
        });
        setFocusable(true);
    }

    private void handleKey(KeyEvent e, boolean pressed) {
        int code=e.getKeyCode();
        // Configurable bindings
        Button btn = null;
        if (code==cfg.keyRight)  btn=Button.RIGHT;
        else if (code==cfg.keyLeft)   btn=Button.LEFT;
        else if (code==cfg.keyUp)     btn=Button.UP;
        else if (code==cfg.keyDown)   btn=Button.DOWN;
        else if (code==cfg.keyA)      btn=Button.A;
        else if (code==cfg.keyB)      btn=Button.B;
        else if (code==cfg.keyStart)  btn=Button.START;
        else if (code==cfg.keySelect) btn=Button.SELECT;
        if(btn!=null){if(pressed)joypad.press(btn);else joypad.release(btn);return;}
        if(!pressed) return;
        if(code==KeyEvent.VK_P){gb.setPaused(!gb.isPaused());sState.setText(gb.isPaused()?" Paused":" Running");}
        if(code==KeyEvent.VK_R && e.isControlDown()) restartROM();
        if(code==KeyEvent.VK_ESCAPE) doExit();
        if(code>=KeyEvent.VK_F1&&code<=KeyEvent.VK_F8){
            int s=code-KeyEvent.VK_F1+1;
            if(e.isShiftDown())loadSlot(s);else saveSlot(s);}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────
    public void showAndStart() {
        setVisible(true); toFront(); requestFocusInWindow(); gb.start();
    }

    private void restartROM() {
        String path = gb.getRomPath();
        fpsTimer.stop();
        gb.stop();
        dispose();
        SwingUtilities.invokeLater(() -> {
            try {
                GameBoy newGb = new GameBoy(path);
                newGb.window().showAndStart();
            } catch (java.io.IOException e) {
                JOptionPane.showMessageDialog(null,
                    "Error al reiniciar:\n" + e.getMessage(),
                    "JavaBoy", JOptionPane.ERROR_MESSAGE);
                new Launcher().setVisible(true);
            }
        });
    }

    private void doExit() {
        cfg.lastRomPath = ""; saveSettings();
        fpsTimer.stop(); gb.stop(); dispose();
        SwingUtilities.invokeLater(()->new Launcher().setVisible(true));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MENU HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private JMenu menu(String n){ return new JMenu(n); }
    private JMenuItem it(String n, String acc, Runnable a){
        JMenuItem mi=new JMenuItem(n);
        if(!acc.isEmpty()){try{mi.setAccelerator(KeyStroke.getKeyStroke(acc));}catch(Exception ignored){}}
        if(a!=null) mi.addActionListener(e->a.run());
        return mi;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GAME PANEL — High-quality filters
    // ─────────────────────────────────────────────────────────────────────────
    static class GamePanel extends JPanel {

        // Source: 160×144 raw pixels from the PPU
        private final BufferedImage src = new BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB);

        // Intermediate buffers (allocated lazily to match scale)
        private BufferedImage scaled2x = null;   // for Scale2x / HQ2x
        private int[]         raw      = new int[160 * 144]; // last raw frame

        // Settings
        private int     filter   = 0;
        private boolean keepAsp  = true;
        private boolean showGrid = false;
        private int[]   palette  = null;
        private float   bright   = 1f, contrast = 1f, sat = 1f;

        GamePanel() { setBackground(Color.BLACK); }

        void setFilter(int f)            { filter = f; scaled2x = null; repaint(); }
        void setKeepAspect(boolean k)    { keepAsp = k; repaint(); }
        void setShowGrid(boolean g)      { showGrid = g; repaint(); }
        void setPalette(int[] p)         { palette = p; }
        void setBCS(int b, int c, int s) { bright = b/100f; contrast = c/100f; sat = s/100f; repaint(); }

        void updatePixels(int[] pixels) {
            int[] data = palette != null ? applyPalette(pixels) : pixels.clone();
            data = applyBCS(data);
            raw = data;
            synchronized (src) { src.setRGB(0, 0, 160, 144, data, 0, 160); }
            repaint();
        }

        // ── Palette remap ─────────────────────────────────────────────────────
        private static final int[] ORIG_DMG = {0xFF9BBC0F,0xFF8BAC0F,0xFF306230,0xFF0F380F};
        private int[] applyPalette(int[] in) {
            int[] out = new int[in.length];
            for (int i = 0; i < in.length; i++) {
                int c = in[i]|0xFF000000, best = Integer.MAX_VALUE, idx = 0;
                for (int j = 0; j < 4; j++) {
                    int d = colorDist(c, ORIG_DMG[j]);
                    if (d < best) { best = d; idx = j; }
                }
                out[i] = palette[idx];
            }
            return out;
        }
        private static int colorDist(int a, int b) {
            int r=((a>>16)&0xFF)-((b>>16)&0xFF), g=((a>>8)&0xFF)-((b>>8)&0xFF), bl=(a&0xFF)-(b&0xFF);
            return r*r + g*g + bl*bl;
        }

        // ── Brightness / Contrast / Saturation ────────────────────────────────
        private int[] applyBCS(int[] in) {
            if (bright==1f && contrast==1f && sat==1f) return in;
            int[] out = new int[in.length];
            for (int i = 0; i < in.length; i++) {
                int px = in[i];
                float r=((px>>16)&0xFF)/255f, g=((px>>8)&0xFF)/255f, b=(px&0xFF)/255f;
                r*=bright; g*=bright; b*=bright;
                r=(r-.5f)*contrast+.5f; g=(g-.5f)*contrast+.5f; b=(b-.5f)*contrast+.5f;
                float lum=.299f*r+.587f*g+.114f*b;
                r=lum+(r-lum)*sat; g=lum+(g-lum)*sat; b=lum+(b-lum)*sat;
                out[i]=0xFF000000|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);
            }
            return out;
        }
        private static int clamp(float v) { return Math.max(0, Math.min(255, (int)(v*255))); }

        // ─────────────────────────────────────────────────────────────────────
        // SCALE2X — pixel-art upscaler (EPX algorithm)
        // Each pixel becomes 2×2. Eliminates staircase artifacts on diagonals.
        // ─────────────────────────────────────────────────────────────────────
        private int[] scale2x(int[] p, int w, int h) {
            int[] out = new int[w*2 * h*2];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int E  = p[y*w+x];
                    int A  = y>0   ? p[(y-1)*w+x]   : E;
                    int C  = x>0   ? p[y*w+(x-1)]   : E;
                    int B  = x<w-1 ? p[y*w+(x+1)]   : E;
                    int D  = y<h-1 ? p[(y+1)*w+x]   : E;
                    int E0, E1, E2, E3;
                    if (A!=D && C!=B) {
                        E0 = C==A ? C : E;
                        E1 = A==B ? B : E;
                        E2 = C==D ? C : E;
                        E3 = D==B ? B : E;
                    } else {
                        E0=E1=E2=E3=E;
                    }
                    int ox=x*2, oy=y*2, ow=w*2;
                    out[oy*ow+ox]     = E0;
                    out[oy*ow+ox+1]   = E1;
                    out[(oy+1)*ow+ox] = E2;
                    out[(oy+1)*ow+ox+1] = E3;
                }
            }
            return out;
        }

        // ─────────────────────────────────────────────────────────────────────
        // HQ2x (simplified) — colour-aware smoothing
        // Uses luminance difference to decide blending direction.
        // ─────────────────────────────────────────────────────────────────────
        private static float luma(int c) {
            return 0.299f*((c>>16)&0xFF) + 0.587f*((c>>8)&0xFF) + 0.114f*(c&0xFF);
        }
        private static int blend(int a, int b, float t) {
            int r=(int)(((a>>16)&0xFF)*(1-t)+((b>>16)&0xFF)*t);
            int g=(int)(((a>>8)&0xFF)*(1-t)+((b>>8)&0xFF)*t);
            int bl=(int)((a&0xFF)*(1-t)+(b&0xFF)*t);
            return 0xFF000000|(r<<16)|(g<<8)|bl;
        }
        private int[] hq2x(int[] p, int w, int h) {
            int[] out = new int[w*2 * h*2];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int E = p[y*w+x];
                    int A = y>0&&x>0   ? p[(y-1)*w+(x-1)] : E;
                    int B = y>0        ? p[(y-1)*w+x]      : E;
                    int C = y>0&&x<w-1 ? p[(y-1)*w+(x+1)] : E;
                    int D = x>0        ? p[y*w+(x-1)]      : E;
                    int F = x<w-1      ? p[y*w+(x+1)]      : E;
                    int G = y<h-1&&x>0 ? p[(y+1)*w+(x-1)] : E;
                    int H = y<h-1      ? p[(y+1)*w+x]      : E;
                    int I = y<h-1&&x<w-1?p[(y+1)*w+(x+1)]:E;

                    float lE=luma(E),lB=luma(B),lD=luma(D),lF=luma(F),lH=luma(H);
                    float thresh=30f;
                    boolean eqB=Math.abs(lE-lB)<thresh, eqD=Math.abs(lE-lD)<thresh;
                    boolean eqF=Math.abs(lE-lF)<thresh, eqH=Math.abs(lE-lH)<thresh;

                    int E0=E,E1=E,E2=E,E3=E;
                    // Top-left
                    if (!eqD && !eqB) E0=blend(E,blend(D,B,0.5f),0.25f);
                    // Top-right
                    if (!eqB && !eqF) E1=blend(E,blend(B,F,0.5f),0.25f);
                    // Bottom-left
                    if (!eqD && !eqH) E2=blend(E,blend(D,H,0.5f),0.25f);
                    // Bottom-right
                    if (!eqH && !eqF) E3=blend(E,blend(H,F,0.5f),0.25f);

                    int ox=x*2, oy=y*2, ow=w*2;
                    out[oy*ow+ox]       = E0;
                    out[oy*ow+ox+1]     = E1;
                    out[(oy+1)*ow+ox]   = E2;
                    out[(oy+1)*ow+ox+1] = E3;
                }
            }
            return out;
        }

        // ─────────────────────────────────────────────────────────────────────
        // SHARP BILINEAR — scale with sharpening kernel (unsharp mask)
        // ─────────────────────────────────────────────────────────────────────
        private static BufferedImage sharpBilinear(BufferedImage in, int dw, int dh) {
            // First scale bilinear
            BufferedImage tmp = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = tmp.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(in, 0, 0, dw, dh, null);
            g.dispose();
            // Then apply unsharp-mask (3×3 Laplacian sharpening)
            float[] kernel = {
                -0.1f,-0.15f,-0.1f,
                -0.15f, 2.0f,-0.15f,
                -0.1f,-0.15f,-0.1f
            };
            BufferedImageOp op = new ConvolveOp(new Kernel(3,3,kernel), ConvolveOp.EDGE_NO_OP, null);
            return op.filter(tmp, null);
        }

        // ─────────────────────────────────────────────────────────────────────
        // CRT — curved screen + RGB phosphor mask + bloom + scanlines
        // ─────────────────────────────────────────────────────────────────────
        private void drawCRT(Graphics2D g2, int ox, int oy, int dw, int dh) {
            // 1. Scale with bilinear
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, ox, oy, dw, dh, null);

            // 2. Scanlines: alternating dark/bright lines (every 2 output pixels)
            float lineH = (float)dh / 144f;
            for (float y2 = oy; y2 < oy+dh; y2 += lineH) {
                int ly = (int)y2;
                int lh = Math.max(1, (int)(lineH * 0.38f));
                g2.setColor(new Color(0,0,0,72));
                g2.fillRect(ox, ly, dw, lh);
            }

            // 3. RGB phosphor mask: vertical R/G/B stripes per pixel column
            float colW = (float)dw / 160f;
            for (int xi = 0; xi < 160; xi++) {
                float cx = ox + xi * colW;
                int pw = Math.max(1, (int)(colW / 3));
                // R stripe
                g2.setColor(new Color(255,0,0,18));
                g2.fillRect((int)cx, oy, pw, dh);
                // B stripe
                g2.setColor(new Color(0,0,255,18));
                g2.fillRect((int)(cx + colW*0.66f), oy, pw, dh);
            }

            // 4. Bloom glow (soft additive overlay of a blurred+brightened copy)
            // We approximate bloom with a semi-transparent enlarged version
            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.07f));
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, ox-4, oy-4, dw+8, dh+8, null);
            g2.setComposite(old);

            // 5. Vignette — dark oval border
            RadialGradientPaint vg = new RadialGradientPaint(
                new java.awt.geom.Point2D.Float(ox+dw/2f, oy+dh/2f),
                Math.max(dw, dh) * 0.65f,
                new float[]{0.5f, 1.0f},
                new Color[]{new Color(0,0,0,0), new Color(0,0,0,130)}
            );
            g2.setPaint(vg);
            g2.fillRect(ox, oy, dw, dh);

            // 6. Subtle green tint (phosphor warmth)
            g2.setColor(new Color(0,20,0,12));
            g2.fillRect(ox, oy, dw, dh);
        }

        // ─────────────────────────────────────────────────────────────────────
        // LCD — Game Boy LCD dot-matrix with subpixel RGB and dark frame
        // ─────────────────────────────────────────────────────────────────────
        private void drawLCD(Graphics2D g2, int ox, int oy, int dw, int dh) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(src, ox, oy, dw, dh, null);

            float pw = (float)dw/160f, ph = (float)dh/144f;
            int gap = Math.max(1, (int)(Math.min(pw,ph)*0.15f));

            // Horizontal lines (row gaps)
            g2.setColor(new Color(10,15,10,110));
            for (float y2 = oy; y2 < oy+dh; y2 += ph) {
                g2.fillRect(ox, (int)y2, dw, gap);
            }
            // Vertical lines (column gaps)
            for (float x2 = ox; x2 < ox+dw; x2 += pw) {
                g2.fillRect((int)x2, oy, gap, dh);
            }
            // Overall dark tint (LCD backlight reduction)
            g2.setColor(new Color(5,20,5,30));
            g2.fillRect(ox, oy, dw, dh);
        }

        // ─────────────────────────────────────────────────────────────────────
        // PAINT
        // ─────────────────────────────────────────────────────────────────────
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            synchronized (src) {
                int pw = getWidth(), ph2 = getHeight(), dw, dh, ox, oy;
                if (keepAsp) {
                    dw=pw; dh=pw*144/160;
                    if (dh>ph2) { dh=ph2; dw=ph2*160/144; }
                    ox=(pw-dw)/2; oy=(ph2-dh)/2;
                } else { dw=pw; dh=ph2; ox=0; oy=0; }

                Graphics2D g2 = (Graphics2D) g;

                switch (filter) {
                    case 0 -> { // ── Nearest Neighbor (Pixel art) ────────────────
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_SPEED);
                        g2.drawImage(src, ox, oy, dw, dh, null);
                    }
                    case 1 -> { // ── Sharp Bilinear (bilinear + unsharp mask) ─────
                        BufferedImage sharp = sharpBilinear(src, dw, dh);
                        g2.drawImage(sharp, ox, oy, null);
                    }
                    case 2 -> { // ── Scale2x (EPX pixel-art upscaler) ────────────
                        if (raw.length == 160*144) {
                            int[] s2 = scale2x(raw, 160, 144);
                            if (scaled2x == null || scaled2x.getWidth()!=320)
                                scaled2x = new BufferedImage(320, 288, BufferedImage.TYPE_INT_RGB);
                            scaled2x.setRGB(0,0,320,288,s2,0,320);
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                            g2.drawImage(scaled2x, ox, oy, dw, dh, null);
                        }
                    }
                    case 3 -> { // ── HQ2x (colour-aware smooth upscale) ──────────
                        if (raw.length == 160*144) {
                            int[] hq = hq2x(raw, 160, 144);
                            if (scaled2x == null || scaled2x.getWidth()!=320)
                                scaled2x = new BufferedImage(320, 288, BufferedImage.TYPE_INT_RGB);
                            scaled2x.setRGB(0,0,320,288,hq,0,320);
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                            g2.drawImage(scaled2x, ox, oy, dw, dh, null);
                        }
                    }
                    case 4 -> { // ── CRT (curvature + phosphors + bloom + vignette)
                        drawCRT(g2, ox, oy, dw, dh);
                    }
                    case 5 -> { // ── LCD dot-matrix (Game Boy screen simulation) ──
                        drawLCD(g2, ox, oy, dw, dh);
                    }
                    case 6 -> { // ── Pixel Perfect (integer scale, centered) ──────
                        int mul = Math.max(1, Math.min(dw/160, dh/144));
                        int pdw=160*mul, pdh=144*mul;
                        int pox=ox+(dw-pdw)/2, poy=oy+(dh-pdh)/2;
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        g2.drawImage(src, pox, poy, pdw, pdh, null);
                    }
                }

                // Optional pixel grid overlay
                if (showGrid && filter!=5) {
                    g2.setColor(new Color(255,255,255,22));
                    float spx=(float)dw/160f, spy=(float)dh/144f;
                    for (float x2=ox; x2<ox+dw; x2+=spx)
                        g2.fillRect((int)x2, oy, 1, dh);
                    for (float y2=oy; y2<oy+dh; y2+=spy)
                        g2.fillRect(ox, (int)y2, dw, 1);
                }
            }
        }
    }
}
