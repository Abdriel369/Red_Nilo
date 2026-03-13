import cv2
import mediapipe as mp
import socket
import time

mp_hands = mp.solutions.hands
mp_drawing = mp.solutions.drawing_utils
mp_drawing_styles = mp.solutions.drawing_styles

# Configuración del socket (se conecta a Java)
HOST = "127.0.0.1"          # localhost
PORT = 65432                # puerto que tú elijas (libre)
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
    # Más robusto: punta más arriba que PIP y PIP más arriba que MCP
    return landmark_tip.y < landmark_pip.y and landmark_pip.y < landmark_mcp.y

cap = cv2.VideoCapture(0)

with mp_hands.Hands(
    min_detection_confidence=0.7,
    min_tracking_confidence=0.7,
    max_num_hands=1) as hands:   # ← 1 mano suele ser suficiente para dibujo

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        frame = cv2.flip(frame, 1)  # espejo (más natural)
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        results = hands.process(rgb_frame)

        comando = "NONE"

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

                # Ejemplo de gestos (ajústalos a tu gusto)
                indice_arriba   = dedo_levantado(lm[8],  lm[5])     # índice
                medio_arriba    = dedo_levantado(lm[12], lm[9])     # medio
                anular_arriba   = dedo_levantado(lm[16], lm[13])    # anular
                meñique_arriba  = dedo_levantado(lm[20], lm[17])    # meñique
                pulgar_arriba   = lm[4].x < lm[3].x                 # ← aproximado (pulgar a la izquierda)

                # ───────────────────────────────────────────────
                # Define aquí tus gestos concretos
                if indice_arriba and medio_arriba and not anular_arriba:
                    comando = "DIBUJAR"
                elif not indice_arriba and medio_arriba and anular_arriba:
                    comando = "BORRAR_TODO"
                elif indice_arriba and not medio_arriba and anular_arriba:
                    comando = "BORRADOR"
                elif pulgar_arriba and medio_arriba:
                    comando = "CAMBIAR_COLOR"   # ejemplo extra
                # ───────────────────────────────────────────────

        # Enviamos solo cuando hay cambio (o cada cierto tiempo)
        try:
            client_socket.sendall((comando + "\n").encode('utf-8'))
        except (ConnectionResetError, BrokenPipeError):
            print("Conexión perdida con Java :(")
            break

        cv2.imshow('Hand Gesture → Java', frame)

        if cv2.waitKey(1) & 0xFF == 27:
            break

cap.release()
cv2.destroyAllWindows()
try:
    client_socket.close()
except:
    pass