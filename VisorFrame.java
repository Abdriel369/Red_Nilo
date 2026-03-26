import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * VisorFrame — Visor 3D optimizado para hardware de bajos recursos
 *
 * Mejoras:
 *  • Carga OBJ (geométrico, ~150 verts) Y PLY clásico
 *  • SIN aristas — solo relleno con shading suave
 *  • Backface culling  → ~50% menos trabajo por frame
 *  • Z-sorting         → orden de pintura correcto
 *  • FPS adaptativo    → 20/30/60 fps según carga del CPU
 *  • Iluminación Lambertiana con normal precalculada por cara
 *  • Arrays reutilizados → menos GC, más fluido
 */
public class VisorFrame extends JFrame {

    private final Visor3DPanel panel3D;

    public VisorFrame(List<List<Point>> trazosIgnorados) {
        setTitle("Visor 3D — Aether X1");
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
        JButton btnAbrir = boton("📂  Abrir modelo (OBJ / PLY)", new Color(0, 120, 215));
        btnAbrir.addActionListener(e -> abrirModelo());
        JButton btnReset = boton("↺  Resetear vista", new Color(60, 60, 80));
        btnReset.addActionListener(e -> { panel3D.resetearVista(); panel3D.repaint(); });
        bar.add(btnAbrir); bar.add(btnReset);
        return bar;
    }

    private JButton boton(String txt, Color bg) {
        JButton b = new JButton(txt);
        b.setFont(new Font("Arial", Font.BOLD, 13));
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        return b;
    }

    private JPanel crearStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 4));
        bar.setBackground(new Color(18, 18, 26));
        JLabel hint = new JLabel("  Gestos: DIBUJAR → Rotar  |  ZOOM +/- → Zoom");
        hint.setForeground(new Color(80, 160, 255));
        hint.setFont(new Font("Arial", Font.PLAIN, 12));
        bar.add(hint);
        return bar;
    }

    private void abrirModelo() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Selecciona modelo OBJ o PLY");
        fc.setFileFilter(new FileNameExtensionFilter("Modelos 3D (*.obj, *.ply)", "obj", "ply"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            setTitle("Visor 3D — " + f.getName());
            try {
                Modelo3D m = f.getName().toLowerCase().endsWith(".obj")
                        ? Modelo3D.cargarOBJ(f) : Modelo3D.cargarPLY(f);
                panel3D.setModelo(m);
                panel3D.repaint();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void procesarComando(String comando) {
        if (panel3D != null) panel3D.procesarComando(comando);
    }

    // ════════════════════════════════════════════════════════════
    // Modelo 3D — soporta OBJ y PLY, normales precalculadas
    // ════════════════════════════════════════════════════════════
    static class Modelo3D {
        final double[][] vertices;
        final int[][]    caras;
        final double[][] normales;   // normal 3D por cara (world-space)

        Modelo3D(double[][] v, int[][] c) {
            this.vertices = v;
            this.caras    = c;
            this.normales = calcNormales(v, c);
        }

        static Modelo3D cargarOBJ(File f) throws IOException {
            List<double[]> V = new ArrayList<>();
            List<int[]>    F = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("v ")) {
                        String[] p = line.split("\\s+");
                        V.add(new double[]{Double.parseDouble(p[1]),
                                           Double.parseDouble(p[2]),
                                           Double.parseDouble(p[3])});
                    } else if (line.startsWith("f ")) {
                        String[] p = line.split("\\s+");
                        int[] face = new int[p.length - 1];
                        for (int i = 0; i < face.length; i++)
                            face[i] = Integer.parseInt(p[i+1].split("/")[0]) - 1;
                        F.add(face);
                    }
                }
            }
            if (V.isEmpty()) throw new IOException("OBJ sin vértices");
            double[][] vs = V.toArray(new double[0][]);
            normalizar(vs);
            return new Modelo3D(vs, F.toArray(new int[0][]));
        }

        static Modelo3D cargarPLY(File f) throws IOException {
            List<double[]> V = new ArrayList<>();
            List<int[]>    F = new ArrayList<>();
            boolean hdr = true; int numV=0, numF=0, rv=0, rf=0;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (hdr) {
                        if (line.equals("end_header")) { hdr=false; continue; }
                        if (line.startsWith("element vertex"))
                            numV = Integer.parseInt(line.split("\\s+")[2]);
                        else if (line.startsWith("element face"))
                            numF = Integer.parseInt(line.split("\\s+")[2]);
                        continue;
                    }
                    if (rv < numV) {
                        String[] p = line.split("\\s+");
                        if (p.length>=3) V.add(new double[]{Double.parseDouble(p[0]),
                                                             Double.parseDouble(p[1]),
                                                             Double.parseDouble(p[2])});
                        rv++;
                    } else if (rf < numF) {
                        String[] p = line.split("\\s+");
                        if (p.length>=2) {
                            int n = Integer.parseInt(p[0]);
                            int[] face = new int[n];
                            for (int i=0;i<n;i++) face[i]=Integer.parseInt(p[i+1]);
                            F.add(face);
                        }
                        rf++;
                    }
                }
            }
            if (V.isEmpty()) throw new IOException("PLY sin vértices");
            double[][] vs = V.toArray(new double[0][]);
            normalizar(vs);
            return new Modelo3D(vs, F.toArray(new int[0][]));
        }

        private static double[][] calcNormales(double[][] vs, int[][] cs) {
            double[][] ns = new double[cs.length][3];
            for (int i = 0; i < cs.length; i++) {
                int[] c = cs[i];
                if (c.length < 3) continue;
                double[] a=vs[c[0]], b=vs[c[1]], e=vs[c[2]];
                double ax=b[0]-a[0], ay=b[1]-a[1], az=b[2]-a[2];
                double bx=e[0]-a[0], by=e[1]-a[1], bz=e[2]-a[2];
                double nx=ay*bz-az*by, ny=az*bx-ax*bz, nz=ax*by-ay*bx;
                double len=Math.sqrt(nx*nx+ny*ny+nz*nz);
                if (len>1e-9) { ns[i][0]=nx/len; ns[i][1]=ny/len; ns[i][2]=nz/len; }
            }
            return ns;
        }

        private static void normalizar(double[][] vs) {
            double minX=Double.MAX_VALUE,maxX=-Double.MAX_VALUE;
            double minY=Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
            double minZ=Double.MAX_VALUE,maxZ=-Double.MAX_VALUE;
            for (double[] v:vs) {
                minX=Math.min(minX,v[0]); maxX=Math.max(maxX,v[0]);
                minY=Math.min(minY,v[1]); maxY=Math.max(maxY,v[1]);
                minZ=Math.min(minZ,v[2]); maxZ=Math.max(maxZ,v[2]);
            }
            double cx=(minX+maxX)/2,cy=(minY+maxY)/2,cz=(minZ+maxZ)/2;
            double sc=Math.max(maxX-minX,Math.max(maxY-minY,maxZ-minZ));
            if(sc<1e-9) sc=1;
            for (double[] v:vs) { v[0]=(v[0]-cx)/sc*1.5; v[1]=(v[1]-cy)/sc*1.5; v[2]=(v[2]-cz)/sc*1.5; }
        }
    }

    // ════════════════════════════════════════════════════════════
    // Panel 3D
    // ════════════════════════════════════════════════════════════
    static class Visor3DPanel extends JPanel {

        private Modelo3D modelo = null;

        private double yawTarget=30,pitchTarget=-20,zoomTarget=1;
        private double yaw=30,pitch=-20,zoom=1;
        private static final double LERP=0.15,ROT_H=180,ROT_V=130,ZOOM_PASO=0.05;
        private Float ultimoX=null,ultimoY=null;

        // Colores
        private static final Color BASE  = new Color(35, 90, 185);
        private static final Color TOP   = new Color(155, 205, 255);

        // Dirección de luz (normalizada)
        private static final double LX=0.408, LY=0.816, LZ=0.408; // norm(0.5,1,0.5)

        // FPS adaptativo
        private int timerDelay=33;
        private long tUltimo=0,acum=0; int nFrames=0;
        private static final int N=20,MS_FAST=20,MS_SLOW=42;

        private Image offscreen=null;

        // Arrays reutilizables (sin new[] en cada frame)
        private Integer[] idx = new Integer[0];
        private double[]  zC  = new double[0];
        private int[]     px  = new int[8];
        private int[]     py  = new int[8];

        private final javax.swing.Timer timer;

        public Visor3DPanel() {
            setBackground(new Color(15,15,20));
            timer = new javax.swing.Timer(timerDelay, e -> tick());
            timer.start();
        }

        private void tick() {
            double dy=yawTarget-yaw,dp=pitchTarget-pitch,dz=zoomTarget-zoom;
            yaw+=dy*LERP; pitch+=dp*LERP; zoom+=dz*LERP;
            if (Math.abs(dy)>0.005||Math.abs(dp)>0.005||Math.abs(dz)>0.0005) {
                long now=System.currentTimeMillis(); repaint();
                if(tUltimo>0){ acum+=now-tUltimo; if(++nFrames>=N){ajustarFPS(acum/nFrames);acum=0;nFrames=0;} }
                tUltimo=now;
            }
        }

        private void ajustarFPS(long avg) {
            int nd=timerDelay;
            if(avg<MS_FAST&&timerDelay>16) nd=16;
            else if(avg>MS_SLOW&&timerDelay<50) nd=50;
            else if(avg>=MS_FAST&&avg<=MS_SLOW&&timerDelay!=33) nd=33;
            if(nd!=timerDelay){timerDelay=nd;timer.setDelay(nd);}
        }

        public void setModelo(Modelo3D m) {
            this.modelo=m;
            int n=m.caras.length;
            idx=new Integer[n]; zC=new double[n];
            for(int i=0;i<n;i++) idx[i]=i;
            resetearVista();
        }

        public void resetearVista() {
            yawTarget=yaw=30; pitchTarget=pitch=-20; zoomTarget=zoom=1;
            ultimoX=null; ultimoY=null;
        }

        public void procesarComando(String cmd) {
            String[] p=cmd.trim().split("\\s+");
            if(p.length==0) return;
            switch(p[0]) {
                case "DIBUJAR":
                    if(p.length==3) { try {
                        float xN=Float.parseFloat(p[1]),yN=Float.parseFloat(p[2]);
                        if(ultimoX!=null){ yawTarget+=(xN-ultimoX)*ROT_H; pitchTarget-=(yN-ultimoY)*ROT_V; pitchTarget=Math.max(-89,Math.min(89,pitchTarget)); }
                        ultimoX=xN; ultimoY=yN;
                    } catch(NumberFormatException ignored){} } break;
                case "ZOOM":
                    if(p.length==2){ if("+".equals(p[1])) zoomTarget=Math.min(zoomTarget+ZOOM_PASO,3.0); else if("-".equals(p[1])) zoomTarget=Math.max(zoomTarget-ZOOM_PASO,0.3); } break;
                case "NONE": case "BORRADOR": ultimoX=null; ultimoY=null; break;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            int W=getWidth(),H=getHeight();
            if(offscreen==null||offscreen.getWidth(null)!=W||offscreen.getHeight(null)!=H)
                offscreen=createImage(W,H);
            Graphics2D g2=(Graphics2D)offscreen.getGraphics();
            render(g2,W,H);
            g2.dispose();
            g.drawImage(offscreen,0,0,null);
        }

        private void render(Graphics2D g2, int W, int H) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Fondo degradado oscuro
            g2.setPaint(new GradientPaint(0,0,new Color(10,10,18),0,H,new Color(18,20,35)));
            g2.fillRect(0,0,W,H);

            if(modelo==null) {
                g2.setColor(new Color(80,80,100));
                g2.setFont(new Font("Arial",Font.BOLD,18));
                String msg="Abre un modelo OBJ o PLY";
                FontMetrics fm=g2.getFontMetrics();
                g2.drawString(msg,(W-fm.stringWidth(msg))/2,H/2);
                return;
            }

            // ── Matrices de rotación ─────────────────────────
            double yR=Math.toRadians(yaw),pR=Math.toRadians(pitch);
            double cosY=Math.cos(yR),sinY=Math.sin(yR);
            double cosP=Math.cos(pR),sinP=Math.sin(pR);
            double fov=500*zoom,camZ=4.0,cxW=W/2.0,cyH=H/2.0;

            int nV=modelo.vertices.length;
            double[] sx=new double[nV],sy=new double[nV],sz=new double[nV];

            // ── Proyectar vértices ───────────────────────────
            for(int i=0;i<nV;i++) {
                double[] v=modelo.vertices[i];
                double x1=v[0]*cosY-v[2]*sinY, z1=v[0]*sinY+v[2]*cosY;
                double y2=v[1]*cosP-z1*sinP,   z2=v[1]*sinP+z1*cosP;
                double zc=z2+camZ; if(zc<0.01)zc=0.01;
                sx[i]=cxW+x1*fov/zc; sy[i]=cyH-y2*fov/zc; sz[i]=z2;
            }

            // ── Z promedio por cara ──────────────────────────
            int nC=modelo.caras.length;
            for(int i=0;i<nC;i++) {
                int[] c=modelo.caras[i]; double s=0;
                for(int vi:c) s+=sz[vi]; zC[i]=s/c.length;
            }

            // ── Z-sort: de atrás hacia adelante ─────────────
            Arrays.sort(idx,(a,b)->Double.compare(zC[b],zC[a]));

            // ── Dibujar caras ────────────────────────────────
            for(int ci:idx) {
                int[] cara=modelo.caras[ci];
                int n=cara.length; if(n<3) continue;

                // ── Backface culling con normal rotada ────────
                double[] nm=modelo.normales[ci];
                double nnx=nm[0]*cosY-nm[2]*sinY;
                double nnz=nm[0]*sinY+nm[2]*cosY;
                double nny=nm[1]*cosP-nnz*sinP;
                double nnz2=nm[1]*sinP+nnz*cosP;
                if(nnz2>0) continue;  // cara trasera → omitir

                // ── Proyectar polígono ────────────────────────
                boolean ok=true;
                for(int k=0;k<n;k++){
                    int vi=cara[k]; if(vi<0||vi>=nV){ok=false;break;}
                    px[k]=(int)sx[vi]; py[k]=(int)sy[vi];
                }
                if(!ok) continue;

                // ── Iluminación Lambertiana ───────────────────
                double dot=nnx*LX+nny*LY+nnz2*LZ;
                double intensity=Math.min(1.0, 0.25 + Math.max(0,dot)*0.75);

                int r=(int)(BASE.getRed()  +(TOP.getRed()  -BASE.getRed())  *intensity);
                int gv=(int)(BASE.getGreen()+(TOP.getGreen()-BASE.getGreen())*intensity);
                int b=(int)(BASE.getBlue() +(TOP.getBlue() -BASE.getBlue()) *intensity);

                g2.setColor(new Color(
                    Math.max(0,Math.min(255,r)),
                    Math.max(0,Math.min(255,gv)),
                    Math.max(0,Math.min(255,b))
                ));
                g2.fillPolygon(px, py, n);
                // ← Sin drawPolygon: CERO aristas
            }

            // ── HUD ──────────────────────────────────────────
            g2.setColor(new Color(0,0,0,160));
            g2.fillRoundRect(10,10,175,78,10,10);
            g2.setFont(new Font("Monospaced",Font.BOLD,11));
            g2.setColor(new Color(100,190,255));
            g2.drawString(String.format("Yaw:   %5.0f°",  yaw),     18,27);
            g2.drawString(String.format("Pitch: %5.0f°",  pitch),   18,43);
            g2.drawString(String.format("Zoom:  %5.0f%%", zoom*100),18,59);
            g2.drawString(String.format("FPS:   ~%d",1000/timerDelay),18,75);
        }
    }
}