import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;

public class DibujoConGestos extends JFrame {

    private static final int PORT = 65432;
    private DrawingPanel canvas;
    private String ultimoComando = "NONE";

    public DibujoConGestos() {
        setTitle("Dibujo con Gestos desde Python");
        setSize(1000, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        canvas = new DrawingPanel();
        add(canvas, BorderLayout.CENTER);

        setVisible(true);

        // Iniciar servidor en hilo separado
        new Thread(this::iniciarServidor).start();
    }

    private void iniciarServidor() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Java esperando conexión de Python en puerto " + PORT);
            Socket client = serverSocket.accept();
            System.out.println("¡Python conectado!");

            BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream())
            );

            String comando;
            while ((comando = in.readLine()) != null) {
                System.out.println("Recibido: " + comando);
                ultimoComando = comando;
                canvas.repaint(); // o manejar según el comando
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Panel donde dibujas
    class DrawingPanel extends JPanel {
        private int prevX = -1, prevY = -1;

        public DrawingPanel() {
            setBackground(Color.WHITE);
            // Aquí puedes agregar MouseMotionListener si quieres dibujar también con ratón para pruebas
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Ejemplo: pintar texto con el comando recibido
            g2.setFont(new Font("Arial", Font.BOLD, 40));
            g2.setColor(Color.BLUE);
            g2.drawString("Comando: " + ultimoComando, 50, 100);

            // Aquí implementa el dibujo real según el comando
            // Ejemplo muy básico:
            if ("DIBUJAR".equals(ultimoComando)) {
                // Deberías recibir también coordenadas del índice (lm[8])
                // Por ahora solo pintamos algo
                g2.setColor(Color.RED);
                g2.fillOval(400, 300, 80, 80);
            } else if ("BORRAR_TODO".equals(ultimoComando)) {
                // Podrías limpiar el canvas o dibujar fondo blanco
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(DibujoConGestos::new);
    }
}