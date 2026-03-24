import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;

/**
 * DibujoConGestos
 * ─────────────────────────────────────────────────────────────
 * Escucha comandos de Python en el puerto 65432,
 * los imprime en consola, los reenvía al módulo Java (65433)
 * y también los envía al VisorFrame si está abierto.
 *
 * Flujo:
 *   Python ──► [65432] DibujoConGestos ──► VisorFrame (3D)
 *                              └──────────► [65433] MóduloReceptor
 * ─────────────────────────────────────────────────────────────
 */
public class DibujoConGestos extends JFrame {

    private static final int SERVER_PORT  = 65432;
    private static final int FORWARD_PORT = 65433;

    private JLabel    lblEstadoPython;
    private JLabel    lblEstadoModulo;
    private JTextArea logArea;
    private int       mensajesRecibidos = 0;

    // Referencia al visor 3D — null si no está abierto
    private VisorFrame visorFrame = null;

    // ── Constructor ──────────────────────────────────────────
    public DibujoConGestos() {
        construirUI();
        new Thread(this::iniciarPuente).start();
    }

    // ── UI ───────────────────────────────────────────────────
    private void construirUI() {
        setTitle("Puente Python ↔ Java");
        setSize(500, 360);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(new Color(30, 30, 30));

        // ── Encabezado ──
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 10));
        header.setBackground(new Color(45, 45, 45));
        JLabel titulo = new JLabel("🔗  Puente Python → Java");
        titulo.setFont(new Font("Monospaced", Font.BOLD, 15));
        titulo.setForeground(new Color(100, 210, 255));
        header.add(titulo);
        root.add(header, BorderLayout.NORTH);

        // ── Estado de conexiones ──
        JPanel estado = new JPanel(new GridLayout(2, 1, 0, 4));
        estado.setBackground(new Color(30, 30, 30));
        estado.setBorder(BorderFactory.createEmptyBorder(8, 14, 6, 14));

        lblEstadoPython = estadoLabel("🔴  Python  [65432] : esperando conexión…");
        lblEstadoModulo = estadoLabel("🔴  Módulo  [65433] : sin reenvíos aún");

        estado.add(lblEstadoPython);
        estado.add(lblEstadoModulo);
        root.add(estado, BorderLayout.CENTER);

        // ── Barra inferior: botón + log ──
        JPanel sur = new JPanel(new BorderLayout());
        sur.setBackground(new Color(20, 20, 20));

        // Botón "Abrir Visor 3D"
        JPanel baraBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        baraBotones.setBackground(new Color(25, 25, 25));

        JButton btnVisor = new JButton("📺  Abrir Visor 3D");
        btnVisor.setFont(new Font("Monospaced", Font.BOLD, 13));
        btnVisor.setBackground(new Color(0, 120, 215));
        btnVisor.setForeground(Color.WHITE);
        btnVisor.setFocusPainted(false);
        btnVisor.setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        btnVisor.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnVisor.addActionListener(e -> abrirOEnfocarVisor());

        btnVisor.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) {
                btnVisor.setBackground(new Color(0, 150, 255));
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                btnVisor.setBackground(new Color(0, 120, 215));
            }
        });

        baraBotones.add(btnVisor);
        sur.add(baraBotones, BorderLayout.NORTH);

        // Log de mensajes
        logArea = new JTextArea(6, 44);
        logArea.setEditable(false);
        logArea.setBackground(new Color(18, 18, 18));
        logArea.setForeground(new Color(180, 255, 180));
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        sur.add(scroll, BorderLayout.CENTER);

        root.add(sur, BorderLayout.SOUTH);

        add(root);
        setVisible(true);
    }

    private JLabel estadoLabel(String texto) {
        JLabel lbl = new JLabel(texto);
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 12));
        lbl.setForeground(Color.LIGHT_GRAY);
        return lbl;
    }

    // ── Abrir / enfocar el Visor 3D ──────────────────────────
    private void abrirOEnfocarVisor() {
        if (visorFrame != null && visorFrame.isDisplayable()) {
            // Ya está abierto: traerlo al frente
            visorFrame.toFront();
            visorFrame.requestFocus();
        } else {
            // Crear nueva instancia
            visorFrame = new VisorFrame(null);
            log("📺 Visor 3D abierto.");
        }
    }

    // ── Servidor ─────────────────────────────────────────────
    private void iniciarPuente() {
        log("Iniciando servidor en puerto " + SERVER_PORT + "…");

        try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
            log("✅ Listo. Esperando a Python…");

            while (true) {
                Socket pythonSocket = serverSocket.accept();
                actualizarEstado(lblEstadoPython,
                        "🟢  Python  [65432] : conectado (" +
                        pythonSocket.getInetAddress().getHostAddress() + ")");
                log("✅ Python conectado.");

                manejarConexion(pythonSocket);

                actualizarEstado(lblEstadoPython,
                        "🔴  Python  [65432] : desconectado. Esperando…");
                log("⚠️  Python desconectado. Esperando nueva conexión…");
            }

        } catch (IOException e) {
            log("❌ Error en servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void manejarConexion(Socket pythonSocket) {
        try (BufferedReader entrada =
                new BufferedReader(new InputStreamReader(pythonSocket.getInputStream()))) {

            String linea;
            while ((linea = entrada.readLine()) != null) {
                mensajesRecibidos++;
                final String msg = linea;
                final int    num = mensajesRecibidos;

                // 1. Consola
                System.out.println("[#" + num + "] " + msg);

                // 2. Log visual
                log("[#" + num + "] " + msg);

                // 3. Reenvío al módulo Java externo
                reenviarAlModulo(msg);

                // 4. Enviar directamente al VisorFrame si está abierto
                SwingUtilities.invokeLater(() -> {
                    if (visorFrame != null && visorFrame.isDisplayable()) {
                        visorFrame.procesarComando(msg);
                    }
                });
            }

        } catch (IOException e) {
            log("⚠️  Conexión con Python cerrada: " + e.getMessage());
        }
    }

    private void reenviarAlModulo(String mensaje) {
        try (Socket modulo = new Socket("localhost", FORWARD_PORT);
             PrintWriter out = new PrintWriter(modulo.getOutputStream(), true)) {

            out.println(mensaje);
            actualizarEstado(lblEstadoModulo,
                    "🟢  Módulo  [65433] : último envío → msg #" + mensajesRecibidos);

        } catch (IOException e) {
            actualizarEstado(lblEstadoModulo,
                    "🔴  Módulo  [65433] : no disponible");
        }
    }

    // ── Utilidades UI ────────────────────────────────────────
    private void actualizarEstado(JLabel label, String texto) {
        SwingUtilities.invokeLater(() -> label.setText(texto));
    }

    private void log(String texto) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(texto + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ── Main ─────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(DibujoConGestos::new);
    }
}