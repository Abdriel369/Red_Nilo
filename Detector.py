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

       # ... (el resto del código igual hasta la detección de landmarks)

        comando = "NONE"
        x_norm = None
        y_norm = None

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

                indice_arriba   = dedo_levantado(lm[8],  lm[5])
                medio_arriba    = dedo_levantado(lm[12], lm[9])
                anular_arriba   = dedo_levantado(lm[16], lm[13])
                meñique_arriba  = dedo_levantado(lm[20], lm[17])
                pulgar_arriba   = lm[4].x < lm[3].x   # aproximado

                # Modo DIBUJAR → calculamos coordenadas normalizadas
                if indice_arriba and medio_arriba and not anular_arriba:
                    comando = "DIBUJAR"

                    # Punta del índice (landmark 8)
                    idx = lm[8]

                    # Normalizamos a rango 0.0 → 1.0 (independiente del tamaño de la cámara)
                    # Puedes invertir Y si quieres que coincida con el canvas de Java
                    x_norm = idx.x          # 0 = izquierda, 1 = derecha
                    y_norm = 1.0 - idx.y    # invertimos Y (0=arriba, 1=abajo)

                elif not indice_arriba and medio_arriba and anular_arriba:
                    comando = "BORRAR_TODO"
                elif indice_arriba and not medio_arriba and anular_arriba:
                    comando = "BORRADOR"
                elif pulgar_arriba and indice_arriba  :
                    comando = "CAMBIAR_COLOR"

        # Preparamos el mensaje
        if comando == "DIBUJAR" and x_norm is not None and y_norm is not None:
            mensaje = f"DIBUJAR {x_norm:.4f} {y_norm:.4f}\n"
        else:
            mensaje = f"{comando}\n"

        try:
            client_socket.sendall(mensaje.encode('utf-8'))
        except (ConnectionResetError, BrokenPipeError, BrokenPipeError):
            print("Conexión perdida con Java :(")
            break

        cv2.imshow('Hand Gesture → Java', frame)
        if cv2.waitKey(1) & 0xFF == 27:
            break