import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class DibujoConGestos extends JFrame {
    private static final int PORT = 65432;
    private DrawingPanel canvas;

    // Referencia al visor (null si no está abierto)
    private VisorFrame visorFrame = null;

    public DibujoConGestos() {
        setTitle("Dibujo con Gestos desde Python");
        setSize(1000, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        canvas = new DrawingPanel();
        add(canvas, BorderLayout.CENTER);

        // ── Barra inferior con botón "Mostrar" ───────────────────────────
        JPanel barraInferior = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        barraInferior.setBackground(new Color(40, 40, 40));

        JButton btnMostrar = new JButton("📺  Mostrar Visor");
        btnMostrar.setFont(new Font("Arial", Font.BOLD, 14));
        btnMostrar.setBackground(new Color(0, 120, 215));
        btnMostrar.setForeground(Color.WHITE);
        btnMostrar.setFocusPainted(false);
        btnMostrar.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        btnMostrar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Hover effect
        btnMostrar.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                btnMostrar.setBackground(new Color(0, 150, 255));
            }
            @Override public void mouseExited(MouseEvent e) {
                btnMostrar.setBackground(new Color(0, 120, 215));
            }
        });

        // Acción: abre el VisorFrame con una copia de los trazos actuales
        btnMostrar.addActionListener(e -> abrirVisor());

        barraInferior.add(btnMostrar);
        add(barraInferior, BorderLayout.SOUTH);

        setVisible(true);
        new Thread(this::iniciarServidor).start();
    }

    /**
     * Abre el VisorFrame con los trazos actuales del canvas.
     * Si ya hay uno abierto, lo cierra y abre uno nuevo con los trazos frescos.
     */
    private void abrirVisor() {
        if (visorFrame != null && visorFrame.isDisplayable()) {
            visorFrame.dispose();
        }
        // Pasamos una copia profunda de los trazos completos al visor
        List<List<Point>> copia = canvas.copiarTrazos();
        visorFrame = new VisorFrame(copia);
    }

    private void iniciarServidor() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Java esperando conexión de Python en puerto " + PORT);
            Socket client = serverSocket.accept();
            System.out.println("¡Python conectado!");

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

            String linea;
            while ((linea = in.readLine()) != null) {
                System.out.println("Recibido: " + linea);

                // Enviamos el comando al canvas (dibujo normal)
                canvas.procesarComando(linea);
                canvas.repaint();

                // Si el visor está abierto, le reenviamos el mismo comando
                // pero el VisorFrame lo interpretará distinto (rotación/zoom)
                if (visorFrame != null && visorFrame.isDisplayable()) {
                    final String cmd = linea;
                    SwingUtilities.invokeLater(() -> visorFrame.procesarComando(cmd));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =========================================================
    //  Panel de dibujo (igual que antes + método copiarTrazos)
    // =========================================================
    class DrawingPanel extends JPanel {
        private List<Point> puntos = new ArrayList<>();
        private List<List<Point>> trazosCompletos = new ArrayList<>();
        private boolean dibujando = false;
        private Color colorActual = Color.RED;
        private int grosor = 8;
        private int num = 0;

        public DrawingPanel() {
            setBackground(Color.WHITE);
        }

        /**
         * Devuelve una copia profunda de todos los trazos finalizados.
         * El VisorFrame recibe esta copia para no compartir estado.
         */
        public List<List<Point>> copiarTrazos() {
            List<List<Point>> copia = new ArrayList<>();
            // Incluimos trazos completos
            for (List<Point> trazo : trazosCompletos) {
                copia.add(new ArrayList<>(trazo));
            }
            // Si hay un trazo en curso, también lo incluimos
            if (!puntos.isEmpty()) {
                copia.add(new ArrayList<>(puntos));
            }
            return copia;
        }

        public void procesarComando(String comando) {
            String[] partes = comando.trim().split("\\s+");
            if (partes.length == 0) return;

            String tipo = partes[0];

            if ("DIBUJAR".equals(tipo) && partes.length == 3) {
                try {
                    float xNorm = Float.parseFloat(partes[1]);
                    float yNorm = Float.parseFloat(partes[2]);

                    int x = (int) (xNorm * getWidth());
                    int y = (int) (yNorm * getHeight());

                    if (!puntos.isEmpty()) {
                        Point ultimo = puntos.get(puntos.size() - 1);
                        double dist = Math.hypot(x - ultimo.x, y - ultimo.y);
                        if (dist < 5) return;
                    }

                    puntos.add(new Point(x, y));
                    dibujando = true;
                } catch (NumberFormatException e) {
                    System.out.println("Error parseando coordenadas: " + comando);
                }
            }
            else if ("NONE".equals(tipo) || "BORRADOR".equals(tipo)) {
                if (!puntos.isEmpty()) {
                    trazosCompletos.add(new ArrayList<>(puntos));
                    puntos.clear();
                }
                dibujando = false;
            }
            else if ("BORRAR_TODO".equals(tipo)) {
                puntos.clear();
                trazosCompletos.clear();
                dibujando = false;
            }
            else if ("CAMBIAR_COLOR".equals(tipo)) {
                Timer timer = new Timer(3000, e -> {
                    System.out.println("Acción repetida cada 3 segundos");
                });
                timer.start();
                Color[] colores = {Color.RED, Color.BLUE, Color.GREEN, Color.ORANGE, Color.MAGENTA};
                colorActual = colores[num % colores.length];
                num += 1;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(grosor, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            g2.setColor(colorActual);
            for (List<Point> trazo : trazosCompletos) {
                dibujarTrazo(g2, trazo);
            }

            if (!puntos.isEmpty()) {
                dibujarTrazo(g2, puntos);
            }

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Arial", Font.PLAIN, 16));
            g2.drawString("Modo: " + (dibujando ? "DIBUJANDO" : "Espera gesto"), 20, 30);
        }

        private void dibujarTrazo(Graphics2D g2, List<Point> pts) {
            if (pts.size() < 2) return;
            for (int i = 1; i < pts.size(); i++) {
                Point p1 = pts.get(i - 1);
                Point p2 = pts.get(i);
                g2.drawLine(p1.x, p1.y, p2.x, p2.y);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(DibujoConGestos::new);
    }
}
