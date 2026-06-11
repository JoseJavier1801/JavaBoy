package com.emulator.ui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Launcher estilo VBA: diálogo de fichero nativo del sistema operativo.
 * Al arrancar sin ROM muestra el explorador directamente; también mantiene
 * la lista de ROMs recientes en un pequeño diálogo previo.
 */
public class Launcher extends JFrame {

    private static final int MAX_RECENT = 10;
    private static final Preferences PREFS =
        Preferences.userNodeForPackage(Launcher.class);

    // VBA usa colores del sistema; nosotros imitamos el look Windows clásico
    private static final Color C_WIN_BG    = new Color(212, 208, 200);
    private static final Color C_WIN_PANEL = new Color(236, 233, 216);
    private static final Color C_WIN_DARK  = new Color(128, 128, 128);
    private static final Color C_WIN_LIGHT = new Color(255, 255, 255);
    private static final Color C_BLUE_SEL  = new Color(49,  106, 197);
    private static final Color C_STATUS_BG = new Color(236, 233, 216);

    private final DefaultListModel<String> recentModel = new DefaultListModel<>();
    private final JList<String>            recentList  = new JList<>(recentModel);
    private final JLabel                   statusBar   = new JLabel(" ");

    public Launcher() {
        super("JavaBoy");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        buildMenuBar();
        buildUI();
        loadRecentPrefs();
        pack();
        setLocationRelativeTo(null);
    }

    // ── Menú idéntico al VBA sin ROM cargada ─────────────────────────────────
    private void buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("Open...",          "ctrl O", this::browseROM));
        file.add(menuItem("Open GameBoy...",  "",       this::browseROM));
        file.addSeparator();
        JMenu recent = new JMenu("Recent");
        recent.add(menuItem("(none)", "", null));
        file.add(recent);
        file.addSeparator();
        file.add(menuItem("Exit", "", () -> System.exit(0)));
        bar.add(file);

        JMenu help = new JMenu("Help");
        help.add(menuItem("About JavaBoy...", "F1", this::showAbout));
        bar.add(help);

        setJMenuBar(bar);
    }

    private JMenuItem menuItem(String name, String accel, Runnable action) {
        JMenuItem mi = new JMenuItem(name);
        if (!accel.isEmpty()) {
            try { mi.setAccelerator(KeyStroke.getKeyStroke(accel)); }
            catch (Exception ignored) {}
        }
        if (action != null) mi.addActionListener(e -> action.run());
        return mi;
    }

    // ── UI principal ─────────────────────────────────────────────────────────
    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 4));
        root.setBackground(C_WIN_BG);
        root.setBorder(new EmptyBorder(4, 4, 0, 4));
        root.setPreferredSize(new Dimension(440, 320));

        // ── Zona drag & drop ─────────────────────────────────────────────────
        JPanel drop = buildDropZone();
        root.add(drop, BorderLayout.NORTH);

        // ── Lista de recientes ────────────────────────────────────────────────
        JPanel center = new JPanel(new BorderLayout(0, 2));
        center.setOpaque(false);

        JLabel lbl = new JLabel("Recent ROMs:");
        lbl.setFont(new Font("Tahoma", Font.PLAIN, 11));
        center.add(lbl, BorderLayout.NORTH);

        recentList.setFont(new Font("Tahoma", Font.PLAIN, 11));
        recentList.setSelectionBackground(C_BLUE_SEL);
        recentList.setSelectionForeground(Color.WHITE);
        recentList.setBackground(Color.WHITE);
        recentList.setCellRenderer(new RecentRenderer());
        recentList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String p = recentList.getSelectedValue();
                    if (p != null) launchROM(p);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(recentList);
        scroll.setBorder(BorderFactory.createLoweredBevelBorder());
        scroll.setBackground(Color.WHITE);
        center.add(scroll, BorderLayout.CENTER);

        root.add(center, BorderLayout.CENTER);

        // ── Botones ───────────────────────────────────────────────────────────
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        btnPanel.setOpaque(false);

        JButton openBtn  = vbaButton("&Open ROM...");
        JButton loadBtn  = vbaButton("&Load");
        JButton clearBtn = vbaButton("Clear List");
        JButton cancelBtn= vbaButton("Cancel");

        openBtn .addActionListener(e -> browseROM());
        loadBtn .addActionListener(e -> {
            String p = recentList.getSelectedValue();
            if (p != null) launchROM(p);
            else browseROM();
        });
        clearBtn.addActionListener(e -> clearRecent());
        cancelBtn.addActionListener(e -> System.exit(0));

        btnPanel.add(openBtn);
        btnPanel.add(loadBtn);
        btnPanel.add(clearBtn);
        btnPanel.add(cancelBtn);
        root.add(btnPanel, BorderLayout.SOUTH);

        // ── Status bar ────────────────────────────────────────────────────────
        statusBar.setFont(new Font("Tahoma", Font.PLAIN, 11));
        statusBar.setBackground(C_STATUS_BG);
        statusBar.setOpaque(true);
        statusBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, C_WIN_DARK),
            new EmptyBorder(2, 4, 2, 4)));

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(btnPanel,  BorderLayout.CENTER);
        south.add(statusBar, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        add(root);
    }

    private JPanel buildDropZone() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // Borde punteado VBA-style
                g.setColor(C_WIN_DARK);
                float[] dash = {4f, 4f};
                Graphics2D g2 = (Graphics2D) g;
                g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 1, dash, 0));
                g2.drawRect(1, 1, getWidth()-3, getHeight()-3);
            }
        };
        p.setPreferredSize(new Dimension(432, 70));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createLoweredBevelBorder());

        JLabel lbl = new JLabel(
            "<html><center><b>JavaBoy</b><br>" +
            "<small>Drag a ROM here or use File → Open...</small></center></html>",
            SwingConstants.CENTER);
        lbl.setFont(new Font("Tahoma", Font.PLAIN, 11));
        p.add(lbl, BorderLayout.CENTER);

        new DropTarget(p, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override public void dragEnter(DropTargetDragEvent e) { p.setBackground(new Color(220,230,255)); p.repaint(); }
            @Override public void dragExit(DropTargetEvent e)      { p.setBackground(Color.WHITE); p.repaint(); }
            @Override public void drop(DropTargetDropEvent ev) {
                p.setBackground(Color.WHITE);
                try {
                    ev.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>)
                        ev.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) launchROM(files.get(0).getAbsolutePath());
                } catch (Exception ex) { statusBar.setText("Error: " + ex.getMessage()); }
            }
        });
        return p;
    }

    /** Botón estilo VBA (relieve 3D Windows clásico) */
    private JButton vbaButton(String text) {
        // Quitar & del mnemónico
        String display = text.replace("&", "");
        JButton b = new JButton(display);
        b.setFont(new Font("Tahoma", Font.PLAIN, 11));
        b.setPreferredSize(new Dimension(100, 23));
        b.setFocusPainted(false);
        return b;
    }

    // ── Cell renderer ─────────────────────────────────────────────────────────
    private static class RecentRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int idx, boolean sel, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(
                    list, value, idx, sel, focus);
            String path = (String) value;
            int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            l.setText(sep >= 0 ? path.substring(sep + 1) : path);
            l.setToolTipText(path);
            l.setFont(new Font("Tahoma", Font.PLAIN, 11));
            l.setBorder(new EmptyBorder(1, 3, 1, 3));
            return l;
        }
    }

    // ── ROM launch ────────────────────────────────────────────────────────────
    private void browseROM() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open ROM");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Game Boy / Color ROMs (*.gb;*.gbc)", "gb", "gbc"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            launchROM(fc.getSelectedFile().getAbsolutePath());
    }

    private void launchROM(String path) {
        File f = new File(path);
        if (!f.exists()) {
            statusBar.setText("File not found: " + path);
            removeFromRecent(path);
            return;
        }
        addToRecent(path);
        setVisible(false);
        dispose();

        SwingUtilities.invokeLater(() -> {
            try {
                com.emulator.GameBoy gb = new com.emulator.GameBoy(path);
                gb.window().showAndStart();
            } catch (IOException ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null,
                    "Error loading ROM:\n" + ex.getMessage(),
                    "JavaBoy", JOptionPane.ERROR_MESSAGE);
                SwingUtilities.invokeLater(() -> new Launcher().setVisible(true));
            }
        });
    }

    // ── Recientes ─────────────────────────────────────────────────────────────
    private void loadRecentPrefs() {
        recentModel.clear();
        for (int i = 0; i < MAX_RECENT; i++) {
            String p = PREFS.get("recent_" + i, null);
            if (p != null) recentModel.addElement(p);
        }
        if (recentModel.isEmpty()) recentModel.addElement("(no recent files)");
    }

    private void addToRecent(String path) {
        if (recentModel.size() == 1 && recentModel.get(0).equals("(no recent files)"))
            recentModel.clear();
        for (int i = 0; i < recentModel.size(); i++)
            if (recentModel.get(i).equals(path)) { recentModel.remove(i); break; }
        recentModel.add(0, path);
        while (recentModel.size() > MAX_RECENT) recentModel.remove(recentModel.size() - 1);
        saveRecentPrefs();
    }

    private void removeFromRecent(String path) {
        for (int i = 0; i < recentModel.size(); i++)
            if (recentModel.get(i).equals(path)) { recentModel.remove(i); break; }
        saveRecentPrefs();
    }

    private void clearRecent() {
        recentModel.clear();
        recentModel.addElement("(no recent files)");
        for (int i = 0; i < MAX_RECENT; i++) PREFS.remove("recent_" + i);
    }

    private void saveRecentPrefs() {
        for (int i = 0; i < MAX_RECENT; i++) {
            String v = i < recentModel.size() ? recentModel.get(i) : null;
            if (v != null && !v.startsWith("(")) PREFS.put("recent_" + i, v);
            else PREFS.remove("recent_" + i);
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "JavaBoy (Java Clone)\n" +
            "Game Boy / Game Boy Color Emulator\n\n" +
            "Open a ROM to start playing.",
            "About JavaBoy", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void launch(String romPath) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            if (romPath != null) {
                try {
                    com.emulator.GameBoy gb = new com.emulator.GameBoy(romPath);
                    gb.window().showAndStart();
                } catch (IOException e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(null,
                        "Error loading ROM:\n" + e.getMessage(),
                        "JavaBoy", JOptionPane.ERROR_MESSAGE);
                    new Launcher().setVisible(true);
                }
            } else {
                new Launcher().setVisible(true);
            }
        });
    }
}
