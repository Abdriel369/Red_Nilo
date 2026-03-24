import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;

public class VisorFrame extends JFrame {

    private final Visor3DPanel panel3D;

    public VisorFrame(List<List<Point>> trazosIgnorados) {
        setTitle("Visor 3D — PLY con Gestos");
        setSize(950, 700);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setBackground(new Color(15, 15, 20));

        panel3D = new Visor3DPanel();
        add(panel3D, BorderLayout.CENTER);

        add(crearToolbar(), BorderLayout.NORTH);
        add(crearStatusBar(), BorderLayout.SOUTH);

        setVisible(true);
    }

    private JPanel crearToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        bar.setBackground(new Color(28, 28, 36));

        JButton btnAbrir = new JButton("📂 Abrir PLY");
        btnAbrir.setFont(new Font("Arial", Font.BOLD, 13));
        btnAbrir.setBackground(new Color(0, 120, 215));
        btnAbrir.setForeground(Color.WHITE);
        btnAbrir.setFocusPainted(false);
        btnAbrir.addActionListener(e -> abrirPLY());

        JButton btnReset = new JButton("↺ Resetear vista");
        btnReset.setFont(new Font("Arial", Font.BOLD, 13));
        btnReset.setBackground(new Color(60, 60, 80));
        btnReset.setForeground(Color.WHITE);
        btnReset.setFocusPainted(false);
        btnReset.addActionListener(e -> { panel3D.resetearVista(); panel3D.repaint(); });

        bar.add(btnAbrir);
        bar.add(btnReset);
        return bar;
    }

    private JPanel crearStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 4));
        bar.setBackground(new Color(18, 18, 26));
        JLabel hint = new JLabel("  Gestos: DIBUJAR → Rotar | ZOOM +/- → Zoom");
        hint.setForeground(new Color(80, 160, 255));
        hint.setFont(new Font("Arial", Font.PLAIN, 12));
        bar.add(hint);
        return bar;
    }

    private void abrirPLY() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Selecciona un archivo PLY");
        fc.setFileFilter(new FileNameExtensionFilter("PLY files (*.ply)", "ply"));

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File archivo = fc.getSelectedFile();
            setTitle("Visor 3D — " + archivo.getName());
            try {
                ModeloPLY modelo = ModeloPLY.cargar(archivo);
                panel3D.setModelo(modelo);
                panel3D.repaint();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                    "Error: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void procesarComando(String comando) {
        if (panel3D != null) {
            panel3D.procesarComando(comando);
            // repaint() ya no es necesario aquí — el timer lo hace a 60 fps
        }
    }

    // ── Modelo PLY (sin cambios) ─────────────────────────────
    static class ModeloPLY {
        final double[][] vertices;
        final int[][] caras;

        ModeloPLY(double[][] v, int[][] c) {
            this.vertices = v;
            this.caras = c;
        }

        static ModeloPLY cargar(File archivo) throws IOException {
            List<double[]> verts = new ArrayList<>();
            List<int[]> faces = new ArrayList<>();
            boolean enCabecera = true;
            int numVertices = 0;
            int numFaces = 0;
            int verticesLeidos = 0;
            int carasLeidas = 0;

            try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    linea = linea.trim();

                    if (enCabecera) {
                        if (linea.equals("end_header")) {
                            enCabecera = false;
                            continue;
                        }
                        if (linea.startsWith("element vertex")) {
                            numVertices = Integer.parseInt(linea.split("\\s+")[2]);
                        } else if (linea.startsWith("element face")) {
                            numFaces = Integer.parseInt(linea.split("\\s+")[2]);
                        }
                        continue;
                    }

                    if (verticesLeidos < numVertices) {
                        String[] parts = linea.split("\\s+");
                        if (parts.length >= 3) {
                            verts.add(new double[]{
                                Double.parseDouble(parts[0]),
                                Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2])
                            });
                            verticesLeidos++;
                        }
                    } else if (carasLeidas < numFaces) {
                        String[] parts = linea.split("\\s+");
                        if (parts.length >= 2) {
                            int numVerts = Integer.parseInt(parts[0]);
                            int[] cara = new int[numVerts];
                            for (int i = 0; i < numVerts; i++) {
                                cara[i] = Integer.parseInt(parts[i + 1]);
                            }
                            faces.add(cara);
                            carasLeidas++;
                        }
                    }
                }
            }

            if (verts.isEmpty()) throw new IOException("No hay vértices");

            double[][] vs = verts.toArray(new double[0][]);
            int[][] fs = faces.toArray(new int[0][]);

            centrarYNormalizar(vs);
            return new ModeloPLY(vs, fs);
        }

        private static void centrarYNormalizar(double[][] vs) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

            for (double[] v : vs) {
                minX = Math.min(minX, v[0]); maxX = Math.max(maxX, v[0]);
                minY = Math.min(minY, v[1]); maxY = Math.max(maxY, v[1]);
                minZ = Math.min(minZ, v[2]); maxZ = Math.max(maxZ, v[2]);
            }

            double cx = (minX + maxX) / 2;
            double cy = (minY + maxY) / 2;
            double cz = (minZ + maxZ) / 2;
            double escala = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));

            if (escala < 1e-9) escala = 1;

            for (double[] v : vs) {
                v[0] = (v[0] - cx) / escala * 1.5;
                v[1] = (v[1] - cy) / escala * 1.5;
                v[2] = (v[2] - cz) / escala * 1.5;
            }
        }
    }

    // ── Panel 3D ─────────────────────────────────────────────
    static class Visor3DPanel extends JPanel {

        private ModeloPLY modelo = null;

        // ── Valores TARGET: reciben los comandos de Python ───
        private double yawTarget   = 30.0;
        private double pitchTarget = -20.0;
        private double zoomTarget  = 1.0;

        // ── Valores ACTUALES: se interpolan hacia el target ──
        private double yaw   = 30.0;
        private double pitch = -20.0;
        private double zoom  = 1.0;

        // Qué tan rápido alcanza el target (0.0–1.0)
        // 0.08 = mucha inercia/suavidad   0.25 = rápido y preciso
        private static final double LERP = 0.15;

        private Float ultimoX = null;
        private Float ultimoY = null;

        private static final double ROT_H     = 180.0;
        private static final double ROT_V     = 130.0;
        private static final double ZOOM_PASO = 0.05;

        private static final Color COLOR_CARA = new Color(50, 110, 210);

        // Buffer manual para eliminar parpadeo
        private Image offscreen = null;

        public Visor3DPanel() {
            setBackground(new Color(15, 15, 20));

            // Timer a 60 fps: interpola yaw/pitch/zoom y repinta solo si hay movimiento
            new javax.swing.Timer(16, e -> {
                double dy = yawTarget   - yaw;
                double dp = pitchTarget - pitch;
                double dz = zoomTarget  - zoom;

                yaw   += dy * LERP;
                pitch += dp * LERP;
                zoom  += dz * LERP;

                boolean moviendose = Math.abs(dy) > 0.005
                                  || Math.abs(dp) > 0.005
                                  || Math.abs(dz) > 0.0005;
                if (moviendose) repaint();
            }).start();
        }

        public void setModelo(ModeloPLY m) {
            this.modelo = m;
            resetearVista();
        }

        public void resetearVista() {
            yawTarget = yaw = 30.0;
            pitchTarget = pitch = -20.0;
            zoomTarget = zoom = 1.0;
            ultimoX = null;
            ultimoY = null;
        }

        public void procesarComando(String comando) {
            String[] p = comando.trim().split("\\s+");
            if (p.length == 0) return;

            switch (p[0]) {
                case "DIBUJAR":
                    if (p.length == 3) {
                        try {
                            float xN = Float.parseFloat(p[1]);
                            float yN = Float.parseFloat(p[2]);
                            if (ultimoX != null) {
                                // Modificamos TARGET, no el valor actual
                                yawTarget   += (xN - ultimoX) * ROT_H;
                                pitchTarget -= (yN - ultimoY) * ROT_V;
                                pitchTarget  = Math.max(-89, Math.min(89, pitchTarget));
                            }
                            ultimoX = xN;
                            ultimoY = yN;
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
                case "ZOOM":
                    if (p.length == 2) {
                        if ("+".equals(p[1])) zoomTarget = Math.min(zoomTarget + ZOOM_PASO, 3.0);
                        else if ("-".equals(p[1])) zoomTarget = Math.max(zoomTarget - ZOOM_PASO, 0.3);
                    }
                    break;
                case "NONE":
                case "BORRADOR":
                    ultimoX = null;
                    ultimoY = null;
                    break;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            int W = getWidth(), H = getHeight();

            // Recrear buffer si cambió el tamaño de la ventana
            if (offscreen == null
                    || offscreen.getWidth(null)  != W
                    || offscreen.getHeight(null) != H) {
                offscreen = createImage(W, H);
            }

            Graphics2D g2 = (Graphics2D) offscreen.getGraphics();
            dibujar(g2, W, H);
            g2.dispose();

            g.drawImage(offscreen, 0, 0, null);
        }

        private void dibujar(Graphics2D g2, int W, int H) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Fondo (igual que antes)
            g2.setColor(new Color(15, 15, 20));
            g2.fillRect(0, 0, W, H);

            if (modelo == null) {
                g2.setColor(Color.GRAY);
                g2.setFont(new Font("Arial", Font.BOLD, 20));
                String msg = "No hay modelo cargado";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, (W - fm.stringWidth(msg)) / 2, H / 2);
                return;
            }

            // Usar yaw/pitch/zoom interpolados (no los target)
            double yawR   = Math.toRadians(yaw);
            double pitchR = Math.toRadians(pitch);
            double cosY = Math.cos(yawR),   sinY = Math.sin(yawR);
            double cosP = Math.cos(pitchR), sinP = Math.sin(pitchR);

            double fov  = 500 * zoom;
            double camZ = 4.0;
            double cx   = W / 2.0;
            double cy   = H / 2.0;

            int nV = modelo.vertices.length;
            double[] sx = new double[nV];
            double[] sy = new double[nV];
            double[] sz = new double[nV];

            // Transformar vértices (sin cambios)
            for (int i = 0; i < nV; i++) {
                double[] v = modelo.vertices[i];
                double x1 = v[0]*cosY - v[2]*sinY;
                double y1 = v[1];
                double z1 = v[0]*sinY + v[2]*cosY;

                double x2 = x1;
                double y2 = y1*cosP - z1*sinP;
                double z2 = y1*sinP + z1*cosP;

                double zCam = z2 + camZ;
                if (zCam < 0.01) zCam = 0.01;
                sx[i] = cx + x2 * fov / zCam;
                sy[i] = cy - y2 * fov / zCam;
                sz[i] = z2;
            }

            // Dibujar caras (sin cambios)
            for (int[] cara : modelo.caras) {
                if (cara.length < 3) continue;

                int[] px = new int[cara.length];
                int[] py = new int[cara.length];
                boolean valido = true;
                for (int k = 0; k < cara.length; k++) {
                    if (cara[k] >= 0 && cara[k] < sx.length) {
                        px[k] = (int) sx[cara[k]];
                        py[k] = (int) sy[cara[k]];
                    } else {
                        valido = false;
                        break;
                    }
                }
                if (!valido) continue;

                g2.setColor(COLOR_CARA);
                g2.fillPolygon(px, py, cara.length);
                g2.setColor(new Color(200, 200, 255, 80));
                g2.drawPolygon(px, py, cara.length);
            }

            // HUD (sin cambios, pero muestra valores interpolados)
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRoundRect(10, 10, 160, 60, 8, 8);
            g2.setColor(new Color(100, 190, 255));
            g2.setFont(new Font("Monospaced", Font.BOLD, 11));
            g2.drawString(String.format("Yaw: %.0f°",   yaw),        18, 28);
            g2.drawString(String.format("Pitch: %.0f°", pitch),      18, 44);
            g2.drawString(String.format("Zoom: %.0f%%", zoom * 100), 18, 60);
        }
    }
}