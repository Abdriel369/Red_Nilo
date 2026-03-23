import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * VisorFrame — Visor 3D de archivos OBJ
 *
 * Controles por gestos (desde Python):
 *   DIBUJAR x y   → Rota el modelo (x = yaw, y = pitch)
 *   ZOOM +        → Acerca la cámara
 *   ZOOM -        → Aleja la cámara
 *   CAMBIAR_COLOR → Ignorado
 *   BORRAR_TODO   → Ignorado
 *   NONE/BORRADOR → Resetea referencia de rotación
 *
 * Renderizado: proyección perspectiva + caras sólidas + wireframe encima
 * Sin librerías externas — puro Java2D
 */
public class VisorFrame extends JFrame {

    private final Visor3DPanel panel3D;

    public VisorFrame(List<List<Point>> trazosIgnorados) {
        setTitle("Visor 3D — OBJ con Gestos");
        setSize(950, 700);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setBackground(new Color(15, 15, 20));

        panel3D = new Visor3DPanel();
        add(panel3D, BorderLayout.CENTER);

        add(crearToolbar(),   BorderLayout.NORTH);
        add(crearStatusBar(), BorderLayout.SOUTH);

        setVisible(true);
    }

    // ── Barra de herramientas ─────────────────────────────────────────────
    private JPanel crearToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        bar.setBackground(new Color(28, 28, 36));

        JButton btnAbrir = estilizarBoton("📂  Abrir OBJ", new Color(0, 120, 215));
        btnAbrir.addActionListener(e -> abrirOBJ());

        JButton btnReset = estilizarBoton("↺  Resetear vista", new Color(60, 60, 80));
        btnReset.addActionListener(e -> { panel3D.resetearVista(); panel3D.repaint(); });

        JLabel lblInfo = new JLabel("   Gestos: rotar con DIBUJAR · zoom con ✌️+👍");
        lblInfo.setForeground(new Color(130, 130, 155));
        lblInfo.setFont(new Font("Arial", Font.PLAIN, 12));

        bar.add(btnAbrir);
        bar.add(btnReset);
        bar.add(lblInfo);
        return bar;
    }

    // ── Barra de estado ───────────────────────────────────────────────────
    private JPanel crearStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 4));
        bar.setBackground(new Color(18, 18, 26));
        JLabel hint = new JLabel(
            "  ✌️ DIBUJAR → Rotar  |  ✌️+👍 Juntos → Zoom+  |  ✌️+👍 Abiertos → Zoom-"
        );
        hint.setForeground(new Color(80, 160, 255));
        hint.setFont(new Font("Arial", Font.PLAIN, 12));
        bar.add(hint);
        return bar;
    }

    private JButton estilizarBoton(String texto, Color bg) {
        JButton btn = new JButton(texto);
        btn.setFont(new Font("Arial", Font.BOLD, 13));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Color hover = bg.brighter();
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setBackground(hover); }
            public void mouseExited (MouseEvent e) { btn.setBackground(bg);    }
        });
        return btn;
    }

    // ── Cargar OBJ con JFileChooser ───────────────────────────────────────
    private void abrirOBJ() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Selecciona un archivo OBJ");
        fc.setFileFilter(new FileNameExtensionFilter("Wavefront OBJ (*.obj)", "obj"));
        fc.setAcceptAllFileFilterUsed(false);

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File archivo = fc.getSelectedFile();
            setTitle("Visor 3D — " + archivo.getName());
            try {
                ModeloOBJ modelo = ModeloOBJ.cargar(archivo);
                panel3D.setModelo(modelo);
                panel3D.repaint();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                    "Error al cargar el archivo:\n" + ex.getMessage(),
                    "Error OBJ", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Llamado desde DibujoConGestos cuando llega un comando TCP */
    public void procesarComando(String comando) {
        if (panel3D != null) {
            panel3D.procesarComando(comando);
            panel3D.repaint();
        }
    }

    // =========================================================
    //  Modelo OBJ — carga vértices y caras
    // =========================================================
    static class ModeloOBJ {
        final double[][] vertices;  // [n][3]  x,y,z normalizados a [-1,1]
        final int[][]    caras;     // [m][k]  índices de vértices (0-based)

        ModeloOBJ(double[][] v, int[][] c) {
            this.vertices = v;
            this.caras    = c;
        }

        static ModeloOBJ cargar(File archivo) throws IOException {
            List<double[]> verts = new ArrayList<>();
            List<int[]>    faces = new ArrayList<>();

            try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    linea = linea.trim();

                    if (linea.startsWith("v ")) {
                        // Vértice: v x y z
                        String[] p = linea.split("\\s+");
                        verts.add(new double[]{
                            Double.parseDouble(p[1]),
                            Double.parseDouble(p[2]),
                            Double.parseDouble(p[3])
                        });

                    } else if (linea.startsWith("f ")) {
                        // Cara: f v1 v2 v3 ...  o  f v1/vt/vn v2/vt/vn ...
                        String[] p = linea.split("\\s+");
                        int[] idx = new int[p.length - 1];
                        for (int i = 1; i < p.length; i++) {
                            // Solo tomamos el índice de vértice (antes del primer '/')
                            idx[i - 1] = Integer.parseInt(p[i].split("/")[0]) - 1;
                        }
                        faces.add(idx);
                    }
                }
            }

            if (verts.isEmpty())
                throw new IOException("El archivo no contiene vértices (líneas 'v').");

            double[][] vs = verts.toArray(new double[0][]);
            int[][]    fs = faces.toArray(new int[0][]);

            centrarYNormalizar(vs);
            return new ModeloOBJ(vs, fs);
        }

        /** Centra el modelo en el origen y lo escala a [-1, 1] */
        private static void centrarYNormalizar(double[][] vs) {
            double minX=Double.MAX_VALUE, maxX=-Double.MAX_VALUE;
            double minY=Double.MAX_VALUE, maxY=-Double.MAX_VALUE;
            double minZ=Double.MAX_VALUE, maxZ=-Double.MAX_VALUE;
            for (double[] v : vs) {
                minX=Math.min(minX,v[0]); maxX=Math.max(maxX,v[0]);
                minY=Math.min(minY,v[1]); maxY=Math.max(maxY,v[1]);
                minZ=Math.min(minZ,v[2]); maxZ=Math.max(maxZ,v[2]);
            }
            double cx=(minX+maxX)/2, cy=(minY+maxY)/2, cz=(minZ+maxZ)/2;
            double escala=Math.max(maxX-minX, Math.max(maxY-minY, maxZ-minZ));
            if (escala < 1e-9) escala = 1;
            for (double[] v : vs) {
                v[0]=(v[0]-cx)/escala*2;
                v[1]=(v[1]-cy)/escala*2;
                v[2]=(v[2]-cz)/escala*2;
            }
        }
    }

    // =========================================================
    //  Panel de renderizado 3D  (painter's algorithm + diffuse)
    // =========================================================
    static class Visor3DPanel extends JPanel {

        private ModeloOBJ modelo = null;

        // Transformaciones de vista
        private double yaw   =  30.0;
        private double pitch = -20.0;
        private double zoom  =  1.0;

        // Última posición del gesto DIBUJAR
        private Float ultimoX = null;
        private Float ultimoY = null;

        // Sensibilidades
        private static final double ROT_H     = 220.0;
        private static final double ROT_V     = 160.0;
        private static final double ZOOM_PASO = 0.06;
        private static final double ZOOM_MAX  = 6.0;
        private static final double ZOOM_MIN  = 0.1;

        // Paleta
        private static final Color COLOR_CARA  = new Color(50, 110, 210);
        private static final Color COLOR_WIRE  = new Color(150, 210, 255);

        // Vector de luz (normalizado)
        private static final double[] LUZ = norm3(new double[]{0.5, 1.0, 0.8});

        public Visor3DPanel() {
            setBackground(new Color(15, 15, 20));
        }

        public void setModelo(ModeloOBJ m) {
            this.modelo = m;
            resetearVista();
        }

        public void resetearVista() {
            yaw = 30.0; pitch = -20.0; zoom = 1.0;
            ultimoX = null; ultimoY = null;
        }

        // ── Gestión de comandos de gestos ─────────────────────────────────
        public void procesarComando(String cmd) {
            String[] p = cmd.trim().split("\\s+");
            if (p.length == 0) return;
            switch (p[0]) {
                case "DIBUJAR":
                    if (p.length == 3) {
                        try {
                            float xN = Float.parseFloat(p[1]);
                            float yN = Float.parseFloat(p[2]);
                            if (ultimoX != null) {
                                yaw   += (xN - ultimoX) * ROT_H;
                                pitch -= (yN - ultimoY) * ROT_V;
                                pitch  = Math.max(-89, Math.min(89, pitch));
                            }
                            ultimoX = xN; ultimoY = yN;
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
                case "ZOOM":
                    if (p.length == 2) {
                        if ("+".equals(p[1]) || "IN".equals(p[1]))
                            zoom = Math.min(zoom + ZOOM_PASO, ZOOM_MAX);
                        else if ("-".equals(p[1]) || "OUT".equals(p[1]))
                            zoom = Math.max(zoom - ZOOM_PASO, ZOOM_MIN);
                    }
                    break;
                case "NONE":
                case "BORRADOR":
                    ultimoX = null; ultimoY = null;
                    break;
                // CAMBIAR_COLOR y BORRAR_TODO → ignorados
            }
        }

        // ── Renderizado ───────────────────────────────────────────────────
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,    RenderingHints.VALUE_RENDER_QUALITY);

            int W = getWidth(), H = getHeight();

            // Fondo degradado oscuro
            g2.setPaint(new GradientPaint(0,0, new Color(18,18,30), 0,H, new Color(8,8,15)));
            g2.fillRect(0, 0, W, H);

            if (modelo == null) {
                dibujarPantallaVacia(g2, W, H);
                return;
            }

            double yawR   = Math.toRadians(yaw);
            double pitchR = Math.toRadians(pitch);
            double cosY=Math.cos(yawR), sinY=Math.sin(yawR);
            double cosP=Math.cos(pitchR), sinP=Math.sin(pitchR);

            // Parámetros de proyección perspectiva
            double fov  = 580 * zoom;
            double camZ = 4.0;
            double cx   = W / 2.0;
            double cy   = H / 2.0;

            // ── Transformar vértices al espacio de pantalla ───────────────
            int nV = modelo.vertices.length;
            double[] sx = new double[nV];
            double[] sy = new double[nV];
            double[] sz = new double[nV];

            for (int i = 0; i < nV; i++) {
                double[] v = modelo.vertices[i];
                // Yaw (rotación en Y)
                double x1 =  v[0]*cosY - v[2]*sinY;
                double y1 =  v[1];
                double z1 =  v[0]*sinY + v[2]*cosY;
                // Pitch (rotación en X)
                double x2 =  x1;
                double y2 =  y1*cosP - z1*sinP;
                double z2 =  y1*sinP + z1*cosP;
                // Perspectiva
                double zCam = z2 + camZ;
                if (zCam < 0.01) zCam = 0.01;
                sx[i] = cx + x2 * fov / zCam;
                sy[i] = cy - y2 * fov / zCam;
                sz[i] = z2;
            }

            // ── Ordenar caras por profundidad (painter's algorithm) ───────
            int nF = modelo.caras.length;
            double[] profZ = new double[nF];
            Integer[] orden = new Integer[nF];
            for (int f = 0; f < nF; f++) {
                double z = 0;
                for (int idx : modelo.caras[f]) z += sz[idx];
                profZ[f] = z / modelo.caras[f].length;
                orden[f] = f;
            }
            Arrays.sort(orden, (a, b) -> Double.compare(profZ[b], profZ[a]));

            // ── Dibujar caras + wireframe ─────────────────────────────────
            for (int fi : orden) {
                int[] cara = modelo.caras[fi];
                if (cara.length < 3) continue;

                int[] px = new int[cara.length];
                int[] py = new int[cara.length];
                for (int k = 0; k < cara.length; k++) {
                    px[k] = (int) sx[cara[k]];
                    py[k] = (int) sy[cara[k]];
                }

                // Normal de la cara para iluminación
                double[] n = normalCara(cara, yawR, pitchR);
                double   dot = Math.max(0, dotProduct(n, LUZ));
                double   luz = 0.18 + 0.82 * dot;

                // Cara trasera → mucho más oscura
                boolean trasera = n[2] < 0;
                if (trasera) luz *= 0.25;

                int r  = clamp((int)(COLOR_CARA.getRed()   * luz));
                int gv = clamp((int)(COLOR_CARA.getGreen() * luz));
                int b  = clamp((int)(COLOR_CARA.getBlue()  * luz));

                // Relleno
                g2.setColor(new Color(r, gv, b));
                g2.fillPolygon(px, py, cara.length);

                // Wireframe
                int alpha = trasera ? 35 : 85;
                g2.setColor(new Color(COLOR_WIRE.getRed(), COLOR_WIRE.getGreen(),
                                      COLOR_WIRE.getBlue(), alpha));
                g2.setStroke(new BasicStroke(0.7f));
                g2.drawPolygon(px, py, cara.length);
            }

            dibujarHUD(g2, nV, nF);
        }

        // ── Pantalla cuando no hay modelo ─────────────────────────────────
        private void dibujarPantallaVacia(Graphics2D g2, int W, int H) {
            g2.setFont(new Font("Arial", Font.BOLD, 22));
            g2.setColor(new Color(70, 70, 95));
            String l1 = "No hay modelo cargado";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(l1, (W - fm.stringWidth(l1)) / 2, H / 2 - 10);

            g2.setFont(new Font("Arial", Font.PLAIN, 15));
            g2.setColor(new Color(55, 55, 78));
            String l2 = "Usa el botón  📂 Abrir OBJ  en la barra superior";
            fm = g2.getFontMetrics();
            g2.drawString(l2, (W - fm.stringWidth(l2)) / 2, H / 2 + 22);
        }

        // ── HUD con stats ─────────────────────────────────────────────────
        private void dibujarHUD(Graphics2D g2, int nV, int nF) {
            g2.setColor(new Color(0, 0, 0, 155));
            g2.fillRoundRect(10, 10, 215, 86, 12, 12);

            g2.setFont(new Font("Monospaced", Font.BOLD, 12));
            g2.setColor(new Color(100, 190, 255));
            g2.drawString(String.format("Yaw   : %7.1f°", yaw % 360),   20, 30);
            g2.drawString(String.format("Pitch : %7.1f°", pitch),        20, 48);
            g2.drawString(String.format("Zoom  :   %5.0f%%", zoom*100),  20, 66);
            g2.setColor(new Color(70, 70, 95));
            g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
            g2.drawString(nV + " vértices  |  " + nF + " caras",         20, 84);
        }

        // ── Matemáticas 3D ────────────────────────────────────────────────

        private double[] normalCara(int[] cara, double yawR, double pitchR) {
            double[] t0 = tv(modelo.vertices[cara[0]], yawR, pitchR);
            double[] t1 = tv(modelo.vertices[cara[1]], yawR, pitchR);
            double[] t2 = tv(modelo.vertices[cara[2]], yawR, pitchR);
            double[] ab = {t1[0]-t0[0], t1[1]-t0[1], t1[2]-t0[2]};
            double[] ac = {t2[0]-t0[0], t2[1]-t0[1], t2[2]-t0[2]};
            return norm3(new double[]{
                ab[1]*ac[2] - ab[2]*ac[1],
                ab[2]*ac[0] - ab[0]*ac[2],
                ab[0]*ac[1] - ab[1]*ac[0]
            });
        }

        /** Transforma un vértice con yaw + pitch */
        private double[] tv(double[] v, double yawR, double pitchR) {
            double cosY=Math.cos(yawR), sinY=Math.sin(yawR);
            double cosP=Math.cos(pitchR), sinP=Math.sin(pitchR);
            double x1= v[0]*cosY - v[2]*sinY, y1= v[1], z1= v[0]*sinY + v[2]*cosY;
            return new double[]{x1, y1*cosP - z1*sinP, y1*sinP + z1*cosP};
        }

        private static double[] norm3(double[] v) {
            double l = Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
            if (l < 1e-10) return new double[]{0,0,1};
            return new double[]{v[0]/l, v[1]/l, v[2]/l};
        }

        private static double dotProduct(double[] a, double[] b) {
            return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
        }

        private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    }
}