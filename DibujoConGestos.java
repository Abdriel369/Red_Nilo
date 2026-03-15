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

    public DibujoConGestos() {
        setTitle("Dibujo con Gestos desde Python");
        setSize(1000, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        canvas = new DrawingPanel();
        add(canvas, BorderLayout.CENTER);
        setVisible(true);

        new Thread(this::iniciarServidor).start();
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
                canvas.procesarComando(linea);
                canvas.repaint();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    class DrawingPanel extends JPanel {
        private List<Point> puntos = new ArrayList<>();           // trazo actual
        private List<List<Point>> trazosCompletos = new ArrayList<>(); // trazos anteriores
        private boolean dibujando = false;
        private Color colorActual = Color.RED;
        private int grosor = 8;

        public DrawingPanel() {
            setBackground(Color.WHITE);
        }

        public void procesarComando(String comando) {
            String[] partes = comando.trim().split("\\s+");

            if (partes.length == 0) return;

            String tipo = partes[0];

            if ("DIBUJAR".equals(tipo) && partes.length == 3) {
                try {
                    float xNorm = Float.parseFloat(partes[1]);
                    float yNorm = Float.parseFloat(partes[2]);

                    // Convertimos coordenadas normalizadas (0-1) → píxeles del panel
                    int x = (int) (xNorm * getWidth());
                    int y = (int) (yNorm * getHeight());

                    // Evitamos puntos muy cercanos (reduce ruido)
                    if (!puntos.isEmpty()) {
                        Point ultimo = puntos.get(puntos.size() - 1);
                        double dist = Math.hypot(x - ultimo.x, y - ultimo.y);
                        if (dist < 5) return; // muy cerca → ignoramos
                    }

                    puntos.add(new Point(x, y));
                    dibujando = true;
                } catch (NumberFormatException e) {
                    System.out.println("Error parseando coordenadas: " + comando);
                }
            }
            else if ("NONE".equals(tipo) || "BORRADOR".equals(tipo)) {
                // Terminamos trazo actual (si había uno)
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
                // Ejemplo: ciclo de colores
                Color[] colores = {Color.RED, Color.BLUE, Color.GREEN, Color.ORANGE, Color.MAGENTA};
                colorActual = colores[(colores.length + 1) % colores.length];
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(grosor, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            // Dibujar trazos anteriores
            g2.setColor(colorActual);
            for (List<Point> trazo : trazosCompletos) {
                dibujarTrazo(g2, trazo);
            }

            // Dibujar trazo actual (en progreso)
            if (!puntos.isEmpty()) {
                dibujarTrazo(g2, puntos);
            }

            // Opcional: mostrar estado
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