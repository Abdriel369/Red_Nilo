import cv2
import mediapipe as mp
import socket
import time
import math

mp_hands = mp.solutions.hands
mp_drawing = mp.solutions.drawing_utils
mp_drawing_styles = mp.solutions.drawing_styles

# Configuración del socket (se conecta a Java)
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


def dedo_levantado(landmark_tip, landmark_mcp, landmark_pip=None):
    """True si el dedo está levantado (arriba)"""
    if landmark_pip is None:
        return landmark_tip.y < landmark_mcp.y
    return landmark_tip.y < landmark_pip.y and landmark_pip.y < landmark_mcp.y


def distancia_3d(a, b):
    """Distancia euclidiana entre dos landmarks (coordenadas normalizadas 0-1)"""
    return math.sqrt((a.x - b.x)**2 + (a.y - b.y)**2 + (a.z - b.z)**2)


# ── Configuración del gesto ZOOM ────────────────────────────────────────────
#
#   Gesto: índice (8) + medio (12) + pulgar (4) levantados,
#          anular y meñique doblados.
#
#   La "distancia de pinza" se mide como el promedio de:
#     · pulgar ↔ índice
#     · pulgar ↔ medio
#
#   Umbral CERCA  → ZOOM +  (dedos juntos  = acercar)
#   Umbral LEJOS  → ZOOM -  (dedos abiertos = alejar)
#
#   Los umbrales están en coordenadas normalizadas (0-1).
#   Ajusta ZOOM_CERCA / ZOOM_LEJOS si tu cámara captura diferente.
#
ZOOM_CERCA  = 0.08   # distancia media < esto → acercar
ZOOM_LEJOS  = 0.18   # distancia media > esto → alejar
# Entre ZOOM_CERCA y ZOOM_LEJOS → zona neutra (no envía zoom)

# Para no saturar Java con miles de comandos ZOOM por segundo,
# limitamos la frecuencia mínima entre envíos de zoom.
ZOOM_COOLDOWN = 0.08   # segundos mínimos entre dos comandos ZOOM
ultimo_zoom   = 0.0    # timestamp del último ZOOM enviado


cap = cv2.VideoCapture(0)

with mp_hands.Hands(
    min_detection_confidence=0.7,
    min_tracking_confidence=0.7,
    max_num_hands=1
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
        zoom_dir = None   # "+" | "-" | None

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

                # ── Estado de cada dedo ──────────────────────────────────
                indice_arriba  = dedo_levantado(lm[8],  lm[5])
                medio_arriba   = dedo_levantado(lm[12], lm[9])
                anular_arriba  = dedo_levantado(lm[16], lm[13])
                menique_arriba = dedo_levantado(lm[20], lm[17])
                pulgar_arriba  = lm[4].x < lm[3].x   # aproximado para mano derecha

                # ── Gesto ZOOM: pulgar + índice + medio levantados ───────
                #    anular y meñique doblados
                es_gesto_zoom = (
                    indice_arriba and
                    medio_arriba  and
                    pulgar_arriba and
                    not anular_arriba and
                    not menique_arriba
                )

                if es_gesto_zoom:
                    # Distancia media de la "pinza"
                    d_pulgar_indice = distancia_3d(lm[4], lm[8])
                    d_pulgar_medio  = distancia_3d(lm[4], lm[12])
                    distancia_media = (d_pulgar_indice + d_pulgar_medio) / 2.0

                    ahora = time.time()

                    if distancia_media < ZOOM_CERCA:
                        zoom_dir = "+"
                    elif distancia_media > ZOOM_LEJOS:
                        zoom_dir = "-"
                    # zona neutra → zoom_dir queda None, no enviamos nada

                    # Dibujamos la distancia en pantalla para ayudar a calibrar
                    texto_zoom = f"ZOOM {'IN' if zoom_dir=='+' else 'OUT' if zoom_dir=='-' else 'NEUTRO'}  d={distancia_media:.3f}"
                    cv2.putText(frame, texto_zoom, (10, 80),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7,
                                (0, 255, 255) if zoom_dir else (180, 180, 180), 2)

                    if zoom_dir and (ahora - ultimo_zoom) >= ZOOM_COOLDOWN:
                        comando    = f"ZOOM {zoom_dir}"
                        ultimo_zoom = ahora
                        # salimos del loop de manos (solo necesitamos una)
                        break

                # ── Gesto DIBUJAR: índice + medio levantados, anular abajo ─
                elif indice_arriba and medio_arriba and not anular_arriba:
                    comando = "DIBUJAR"
                    idx    = lm[8]
                    x_norm = idx.x
                    y_norm = 1.0 - idx.y

                # ── Gesto BORRAR TODO: medio + anular levantados, índice abajo
                elif not indice_arriba and medio_arriba and anular_arriba:
                    comando = "BORRAR_TODO"

                # ── Gesto BORRADOR: índice + anular, medio abajo ─────────
                elif indice_arriba and not medio_arriba and anular_arriba:
                    comando = "BORRADOR"

                # ── Gesto CAMBIAR COLOR: pulgar + índice ─────────────────
                elif pulgar_arriba and indice_arriba:
                    comando = "CAMBIAR_COLOR"

        # ── Preparar y enviar mensaje ────────────────────────────────────
        if comando.startswith("ZOOM"):
            mensaje = f"{comando}\n"                               # "ZOOM +\n" o "ZOOM -\n"
        elif comando == "DIBUJAR" and x_norm is not None:
            mensaje = f"DIBUJAR {x_norm:.4f} {y_norm:.4f}\n"
        else:
            mensaje = f"{comando}\n"

        # Mostrar comando activo en pantalla
        color_texto = {
            "DIBUJAR":      (0, 255, 0),
            "BORRAR_TODO":  (0, 0, 255),
            "BORRADOR":     (255, 100, 0),
            "CAMBIAR_COLOR":(255, 0, 255),
            "NONE":         (150, 150, 150),
        }.get(comando.split()[0], (0, 220, 220))   # ZOOM → cyan

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