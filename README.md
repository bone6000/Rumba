# Roomba Local

App Android mínima para controlar un Roomba (series 600/800/900, probado en teoría con el 960) **directamente por la wifi local**, sin la nube de iRobot ni servidores intermedios. Usa el mismo protocolo local que dorita980 (MQTT sobre TLS, puerto 8883).

## Compilar el APK con GitHub (sin instalar nada en el PC)

1. Crea un repositorio nuevo en GitHub (privado está bien).
2. Sube todo el contenido de esta carpeta, **incluida la carpeta `.github`**. Si la subida web no admite carpetas ocultas, crea el archivo `.github/workflows/build.yml` desde la web (Add file → Create new file) y pega su contenido.
3. Ve a la pestaña **Actions**. El workflow "Build APK" se lanza solo con cada subida, o pulsa "Run workflow".
4. Cuando termine (unos 5 minutos), entra en la ejecución y descarga `roomba-local-apk` en la sección *Artifacts*. Es un zip con `app-debug.apk`.
5. Pasa el APK al móvil e instálalo (hay que permitir instalar apps de orígenes desconocidos).

El APK es de depuración: Gradle lo firma automáticamente con una clave de debug, así que se instala sin más.

## Uso

1. Abre la app. La primera vez te lleva a **Ajustes** (también desde el engranaje ⚙ de arriba a la derecha).
2. En Ajustes pulsa **Buscar robot en la red**: rellena IP y BLID (el móvil tiene que estar en la misma wifi).
3. Pulsa **Obtener contraseña del robot** y sigue las instrucciones (HOME pulsado hasta el tono). También puedes escribir los tres datos a mano.
4. Vuelve a la pantalla principal: ves el mapa del recorrido en vivo, batería y estado, con los botones de control debajo.

Conviene dar al robot una IP fija (reserva DHCP en el router). La app se desconecta del robot al salir de la pantalla, para dejar libre la única conexión MQTT que admite.

## Avisos

- No está probada contra un robot real. El punto más delicado es el TLS antiguo del Roomba (por eso se usa BouncyCastle). Si el estado muestra un error de TLS o de handshake, copia el mensaje exacto.
- El certificado del robot es autofirmado y la app no lo valida. Úsala solo en tu red local.
- La contraseña se guarda en el almacenamiento privado de la app, sin cifrar.
- Solo funciona si el móvil llega a la IP del robot (misma wifi o VPN).
