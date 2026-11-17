# Roomba Local

App Android para controlar un **Roomba 960** (y, en teoría, otros de las series 600/800/900) **directamente por tu wifi**, sin la nube de iRobot, sin servidores intermedios y sin Home Assistant. Habla con el robot por el mismo protocolo local que usan dorita980 y roombapy: MQTT sobre TLS, puerto 8883.

Pensada para robots cuya nube de iRobot ya no funciona.

## Qué hace

- Iniciar, pausar, reanudar y parar la limpieza, mandar el robot a la base y hacerlo sonar (Localizar).
- Muestra batería, estado y aviso de depósito lleno.
- Dibuja en directo el recorrido del robot.
- Busca el robot en la red y le pide la contraseña, sin tener que sacarla con otras herramientas. La contraseña se puede ver, copiar y guardar como copia de seguridad.
- Estadísticas: batería, limpieza en curso, historial, errores y cuánto le queda al filtro y a los cepillos.
- Programa limpiezas por días y horas: se guardan en el propio robot y el móvil hace de respaldo.
- Guarda el mapa tras la primera limpieza completa, permite nombrar zonas (Salón, Cocina…) y muestra el porcentaje que cubrió la última limpieza en cada una.

Todo se guarda **solo en el móvil**. La app no envía nada a internet.

## 1. Obtener el APK (sin instalar nada en el PC)

**Opción recomendada: Releases.**

1. Entra en la sección **Releases** de este repositorio (columna derecha de la página principal).
2. Abre la release **Roomba Local (última versión)** (etiqueta `latest`, se actualiza sola con cada cambio en `main`) y descarga `roomba-local.apk` desde **Assets**.
3. Pásalo al móvil y ábrelo. Android pedirá permiso para **instalar apps de orígenes desconocidos**.

Las versiones nuevas se instalan encima de las anteriores sin perder los ajustes ni el mapa.

**Versiones con número (opcional):** al crear una etiqueta que empiece por `v`, el APK también se adjunta a su release:

```
git tag v1.1.0
git push origin v1.1.0
```

GitHub Actions compila el APK y lo publica como release en unos minutos. También se puede crear la etiqueta desde la web, en Releases → Draft a new release.

**Alternativa:** cada subida a `main` genera un APK de prueba en **Actions** → última ejecución → Artifacts (`roomba-local-apk`).

## 2. Primera configuración

Necesitas que el móvil esté en **la misma wifi que el robot**.

1. Abre la app. Si falta algún dato, te lleva sola a **Ajustes** (también está en el engranaje ⚙ de arriba a la derecha).
2. Pulsa **Buscar robot en la red**. Rellena la IP y el BLID (el usuario del robot).
3. Pulsa **Obtener contraseña del robot** y sigue las instrucciones:
   - Cierra la app oficial de iRobot en todos los dispositivos (el robot solo admite una conexión a la vez).
   - Con el robot en la base y encendido, mantén pulsado **HOME** hasta que suene un tono y parpadee el símbolo wifi. Suéltalo.
   - Pulsa **Continuar** enseguida, antes de que pase un minuto.
4. Vuelve atrás: la app se conecta sola. El punto de color junto al título indica el estado: verde conectado, ámbar conectando, rojo error.

También puedes escribir IP, BLID y contraseña a mano en Ajustes.

**Copia de seguridad de la contraseña.** Debajo del campo de contraseña:

- **Mostrar / Ocultar** enseña la contraseña.
- **Copiar** la copia al portapapeles.
- **Compartir** envía IP, BLID y contraseña a donde elijas (gestor de contraseñas, notas, correo…).
- **Pegar copia de seguridad** restaura esos datos: copia el texto que guardaste y pulsa el botón.

**Recomendado:** dale al robot una IP fija con una reserva DHCP en el router, para que no cambie.

## 3. Uso diario

La pantalla principal tiene el mapa arriba, batería y estado en medio, y los controles debajo.

| Botón | Qué hace |
|---|---|
| Limpiar | Inicia una limpieza |
| Pausa / Reanudar | Detiene y continúa la misión |
| Parar | Cancela la misión |
| A la base | Manda el robot a cargar |
| Localizar | Hace sonar el robot |

La app se desconecta del robot al salir de la pantalla, para dejar libre su única conexión. Si usas la app oficial de iRobot o Home Assistant a la vez, ciérralos mientras uses esta.

## 4. Mapa y zonas

1. **Primera limpieza completa.** Haz una limpieza con la app abierta y deja que termine sola en la base. Al acabar se guarda el recorrido como mapa y aparece un aviso.
2. **Dibujar zonas.** Pulsa ▦ en la pantalla principal para abrir **Mapa y zonas**. Arrastra el dedo sobre el mapa para dibujar un rectángulo y ponle nombre.
3. **Ver la cobertura.** Tras cada limpieza completa, cada zona muestra el porcentaje que cubrió el robot (verde ≥ 80 %, ámbar ≥ 40 %, rojo menos). En el mapa, lo cubierto se pinta en turquesa.
4. **Corregir el mapa.** Si el primero no salió bien, pulsa **Usar la última limpieza como mapa**. **Borrar mapa** lo vacía y conserva las zonas.

### Limitaciones del mapa

- Es la **trayectoria** del robot, no un plano con paredes.
- El 960 no guarda mapas propios y mide su posición desde el punto de partida de cada misión. Por eso el mapa solo encaja entre limpiezas si el robot **sale siempre de la base**.
- El 960 no admite órdenes de limpieza por zonas. Las zonas sirven para **ver la cobertura**, no para mandar al robot a una estancia.
- El fin de limpieza se detecta cuando el robot informa de que carga sin ciclo activo. Si el mapa no se guarda solo, usa el botón manual.

## 5. Estadísticas

Pulsa ⓘ en la pantalla principal (o toca la tarjeta de batería) para abrir **Estadísticas**:

- **Batería:** porcentaje, estado, capacidad estimada, ciclos de carga, horas en la base y fabricante.
- **Limpieza actual:** fase, tiempo limpiando, superficie, quién la inició, errores y estado del depósito.
- **Mantenimiento:** horas que le quedan al filtro, cepillo lateral, rodillos y rueda delantera. El 960 no informa del desgaste de las piezas, así que la app lo calcula con sus horas de uso: pulsa **Lo he cambiado** al poner una pieza nueva (o ahora, si está nueva) y el contador empieza de cero. Toca el nombre de una pieza para cambiar su duración. Por defecto: filtro 60 h, cepillo lateral 120 h, rodillos y rueda 250 h (≈ lo que recomienda iRobot limpiando una hora al día).
- **Historial:** horas y superficie totales, limpiezas terminadas, canceladas y fallidas, veces atascado, zonas sucias detectadas…
- **Robot:** modelo, firmware, señal wifi, ajustes de limpieza y reloj.
- **Todos los datos:** todo lo que envía el robot, para verlo o copiarlo.

Solo aparecen los datos que el robot envía; según el firmware pueden faltar algunos.

## 6. Programación

Pulsa ◷ en la pantalla principal para abrir **Programación**.

1. Toca cada día para activarlo y su hora para cambiarla. El 960 admite **una limpieza por día**. **Misma hora para todos** aplica una hora a todos los días activos.
2. Elige cómo se lanza (puedes dejar las dos opciones activadas, es lo recomendado):
   - **Guardar en el robot:** el robot limpia solo con su calendario interno, aunque el móvil esté apagado. Hace falta que el robot tenga bien la hora.
   - **Respaldo desde el móvil:** 3 minutos después de la hora programada, el móvil se conecta al robot. Si ya está limpiando no hace nada; si no ha empezado, le manda limpiar. El móvil tiene que estar encendido y en la wifi de casa. Si solo usas esta opción, el móvil lanza la limpieza a la hora exacta.
3. Pulsa **Guardar programación** con la app conectada al robot. Cuando el robot la confirma aparece "✓ Programación guardada en el robot y en el móvil".

En la tarjeta **Robot** se ve la programación que tiene guardada el robot, su reloj y su zona horaria. Sin la nube de iRobot el robot puede perder la hora: si aparece "desfasado", su calendario se disparará a deshoras, pero el respaldo del móvil seguirá yendo a su hora. Si la zona horaria del robot no coincide con la del móvil, aparece un botón para cambiarla (puede que el firmware no la acepte).

Para que el respaldo funcione con la pantalla apagada:

- Pulsa **Permitir que funcione en segundo plano** si aparece (quita la optimización de batería para esta app). En algunos móviles (Xiaomi, Huawei, Samsung…) conviene además permitir el inicio automático.
- Acepta el permiso de notificaciones: solo se avisa si algo falla (robot atascado, sin conexión…). El resultado del último respaldo se ve en la pantalla de Programación.

Si desactivas **Guardar en el robot** y guardas, la app borra el calendario interno del robot para que solo limpie el móvil.

## Problemas frecuentes

| Síntoma | Qué probar |
|---|---|
| "No se ha encontrado ningún robot" | Comprueba que móvil y robot están en la misma wifi (la del robot es de 2,4 GHz) y que el router no aísla los dispositivos. Escribe la IP a mano. |
| No se obtiene la contraseña | Repite el paso del botón HOME y pulsa Continuar más rápido. Cierra la app oficial de iRobot. El mensaje de error indica qué variante falló; cópialo al abrir una incidencia. |
| Error de TLS o handshake al conectar | El robot usa TLS 1.2 antiguo con certificado autofirmado. Copia el mensaje exacto del estado. |
| Se conecta pero no responde | Otro cliente puede estar ocupando la única conexión MQTT. Ciérralos y reinicia el robot. |
| La limpieza programada no arranca | Mira la hora del robot en Programación y el texto "Último respaldo". Comprueba que el móvil sigue en la wifi de casa y que la app no tiene restringida la batería. |
| El mapa sale vacío | El robot debe enviar su posición mientras limpia. Comprueba que está en una limpieza real, no solo cargando. |

## Seguridad

- La app **no valida el certificado** del robot, que es autofirmado. Úsala solo en tu red local o por VPN.
- IP, BLID y contraseña se guardan en el almacenamiento privado de la app, sin cifrar.
- No incluye ningún dato personal en el código del repositorio.

## Estado del proyecto

Funciona la compilación, pero la comunicación real con el robot **no se ha probado con hardware** al escribirse este README. Los puntos más delicados son el TLS antiguo y el intercambio para obtener la contraseña. Si algo falla, abre una incidencia con el mensaje exacto que muestra la app.

## Estructura

```
app/src/main/java/com/example/roombalocal/
  MainActivity.kt       pantalla principal: mapa, estado y controles
  SettingsActivity.kt   ajustes, detección del robot y contraseña
  ZonesActivity.kt      mapa guardado, zonas y cobertura
  StatsActivity.kt      estadísticas y mantenimiento
  ScheduleActivity.kt   programación semanal
  Schedule.kt           modelo de la programación y alarmas del móvil
  ScheduleReceiver.kt   alarma y reinicio del móvil
  ScheduleService.kt    respaldo: conecta y manda limpiar si hace falta
  RoombaClient.kt       cliente MQTT local
  RoombaDiscovery.kt    descubrimiento por UDP y obtención de la contraseña
  LegacyTls.kt          TLS compatible con el robot (BouncyCastle)
  MapStore.kt           mapa, zonas y cobertura guardados en el móvil
  MapView.kt            dibujo del mapa
  Ui.kt                 colores y estilos
.github/workflows/build.yml   compilación del APK
```

## Agradecimientos

Protocolo local documentado por los proyectos [dorita980](https://github.com/koalazak/dorita980) y [roombapy](https://github.com/pschmitt/roombapy).
