import cv2
import mediapipe as mp
import socket
import time
import math

mp_hands = mp.solutions.hands
mp_drawing = mp.solutions.drawing_utils
mp_drawing_styles = mp.solutions.drawing_styles

HOST = "127.0.0.1"
PORT = 65432

client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
connected = False
while not connected:
    try:
        client_socket.connect((HOST, PORT))
        connected = True
        print("¡Conectado al programa Java!")
    except ConnectionRefusedError:
        print("Esperando que Java inicie el servidor...")
        time.sleep(1)


def dedo_levantado(landmark_tip, landmark_mcp):
    return landmark_tip.y < landmark_mcp.y


def distancia_3d(a, b):
    return math.sqrt((a.x - b.x)**2 + (a.y - b.y)**2 + (a.z - b.z)**2)


def centro_mano(lm):
    puntos = [lm[0], lm[5], lm[9], lm[13], lm[17]]
    cx = sum(p.x for p in puntos) / len(puntos)
    cy = sum(p.y for p in puntos) / len(puntos)
    cz = sum(p.z for p in puntos) / len(puntos)
    return cx, cy, cz


class Punto:
    def __init__(self, x, y, z):
        self.x = x
        self.y = y
        self.z = z


# ── Constantes ZOOM ──────────────────────────────────────────
ZOOM_JUNTO    = 0.28
ZOOM_LEJOS    = 0.30
ZOOM_COOLDOWN = 0.05
ultimo_zoom   = 0.0

cap = cv2.VideoCapture(0)

with mp_hands.Hands(
    min_detection_confidence=0.5,
    min_tracking_confidence=0.5,
    max_num_hands=2
) as hands:

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        frame     = cv2.flip(frame, 1)
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        results   = hands.process(rgb_frame)

        comando = "NONE"
        x_norm  = None
        y_norm  = None
        manos   = []

        if results.multi_hand_landmarks:
            for hand_landmarks in results.multi_hand_landmarks:
                mp_drawing.draw_landmarks(
                    frame,
                    hand_landmarks,
                    mp_hands.HAND_CONNECTIONS,
                    mp_drawing_styles.get_default_hand_landmarks_style(),
                    mp_drawing_styles.get_default_hand_connections_style()
                )

                lm = hand_landmarks.landmark

                indice_arriba  = dedo_levantado(lm[8],  lm[5])
                medio_arriba   = dedo_levantado(lm[12], lm[9])
                anular_arriba  = dedo_levantado(lm[16], lm[13])
                menique_arriba = dedo_levantado(lm[20], lm[17])
                pulgar_arriba  = lm[4].x < lm[3].x - 0.02

                cinco_dedos = (
                    indice_arriba and medio_arriba and
                    anular_arriba and menique_arriba and pulgar_arriba
                )

                cx, cy, cz = centro_mano(lm)

                manos.append({
                    "lm":             lm,
                    "indice_arriba":  indice_arriba,
                    "medio_arriba":   medio_arriba,
                    "anular_arriba":  anular_arriba,
                    "menique_arriba": menique_arriba,
                    "pulgar_arriba":  pulgar_arriba,
                    "cinco_dedos":    cinco_dedos,
                    "cx": cx, "cy": cy, "cz": cz,
                })

        # ── Lógica principal ─────────────────────────────────
        if len(manos) == 2:
            # ── ZOOM: distancia entre las 2 manos ────────────
            m0, m1 = manos[0], manos[1]
            p0 = Punto(m0["cx"], m0["cy"], m0["cz"])
            p1 = Punto(m1["cx"], m1["cy"], m1["cz"])
            dist_actual = distancia_3d(p0, p1)

            ahora    = time.time()
            zoom_dir = None

            if dist_actual < ZOOM_JUNTO:
                zoom_dir = "+++"   # manos juntas  → acercar
            elif dist_actual > ZOOM_LEJOS:
                zoom_dir = "---"   # manos alejadas → alejar

            # Línea visual entre centros de manos
            h, w = frame.shape[:2]
            px0 = (int(m0["cx"] * w), int(m0["cy"] * h))
            px1 = (int(m1["cx"] * w), int(m1["cy"] * h))
            color_linea = (0, 255, 0) if zoom_dir == "+++" else (0, 0, 255) if zoom_dir == "---" else (180, 180, 180)
            cv2.line(frame, px0, px1, color_linea, 2)
            cv2.putText(frame,
                        f"dist: {dist_actual:.3f}  {'JUNTO' if zoom_dir=='+++' else 'LEJOS' if zoom_dir=='---' else 'NEUTRO'}",
                        (10, 110), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color_linea, 2)

            if zoom_dir and (ahora - ultimo_zoom) >= ZOOM_COOLDOWN:
                comando     = f"ZOOM {zoom_dir}"
                ultimo_zoom = ahora

        elif len(manos) == 1:
            # ── Gestos de 1 mano ─────────────────────────────
            m  = manos[0]

            if m["cinco_dedos"]:
                comando = "MOVER"
                x_norm  = m["cx"]
                y_norm  = 1.0 - m["cy"]

            elif not m["indice_arriba"] and m["medio_arriba"] and m["anular_arriba"]:
                comando = "BORRAR_TODO"

            elif m["indice_arriba"] and not m["medio_arriba"] and m["anular_arriba"]:
                comando = "BORRADOR"

            elif m["pulgar_arriba"] and m["indice_arriba"]:
                comando = "CAMBIAR_COLOR"

        # ── Preparar y enviar mensaje ─────────────────────────
        if comando.startswith("ZOOM"):
            mensaje = f"{comando}\n"
        elif comando == "MOVER" and x_norm is not None:
            mensaje = f"MOVER {x_norm:.4f} {y_norm:.4f}\n"
        else:
            mensaje = f"{comando}\n"

        # Mostrar comando activo en pantalla
        color_texto = {
            "MOVER":        (0, 255, 0),
            "BORRAR_TODO":  (0, 0, 255),
            "BORRADOR":     (255, 100, 0),
            "CAMBIAR_COLOR":(255, 0, 255),
            "NONE":         (150, 150, 150),
        }.get(comando.split()[0], (0, 220, 220))

        cv2.putText(frame, f"CMD: {comando.strip()}", (10, 40),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, color_texto, 2)

        try:
            client_socket.sendall(mensaje.encode('utf-8'))
        except (ConnectionResetError, BrokenPipeError):
            print("Conexión perdida con Java :(")
            break

        cv2.imshow('Hand Gesture → Java', frame)
        if cv2.waitKey(1) & 0xFF == 27:
            break

cap.release()
cv2.destroyAllWindows()
client_socket.close()