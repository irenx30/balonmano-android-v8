# Balonmano — Android

App Android independiente, sin Streamlit ni conexión obligatoria.

## Novedades de esta versión
- **Diseño renovado**: tarjetas redondeadas, cabecera con degradado, botones
  con color según su función (dorado para Gol, verde/ámbar para el
  cronómetro, rojo para borrar, teal para Parada), efecto ripple al tocar.
- **Cronómetro del partido**: en vez de un minuto fijo, hay un cronómetro
  real con ▶️ Seguir / ⏸ Pausar (y un botón ✏️ para ajustarlo a mano si
  hace falta, por ejemplo al empezar la segunda parte).
- **Rejilla de portería 3x3**: al marcar un gol (de juego, de 7 metros, de
  contraataque o recibido por la portera) aparece una portería dividida en
  9 zonas para tocar por dónde ha entrado. Se puede omitir si no se quiere
  precisar.
- **Lanzamiento y Contraataque en pantalla propia**: igual que en el flujo
  de Portería, cada paso (zona, resultado…) es su propia pantalla, sin ir
  amontonando botones hacia abajo.
- **"7 metros" ya no es una acción aparte**: ahora es una zona más dentro
  de "Lanzamiento" (como en la app de Python), evitando la opción
  duplicada en la lista de acciones.
- **Tabla de jugadoras transpuesta**: en las estadísticas de cada partido,
  las jugadoras son ahora las columnas y las estadísticas las filas —
  más fácil de leer con la plantilla completa. Incluye el % de éxito y el
  % de lanzamientos por tipo (de juego / 7 metros / contraataque).
- **Visión global de la temporada**: balance de partidos jugados,
  ganados/empatados/perdidos, ranking de goleadoras y de porteras, y
  mapas de calor de dónde se marcan y se reciben los goles.
- Marcador automático corregido: un lanzamiento que termina en gol también
  suma al marcador.
- Jugadoras: se pueden desactivar (mantener historial) o borrar
  definitivamente (con confirmación).
- SQLite local; la base de datos existente se incluye en
  `app/src/main/assets/balonmano.db` y se migra automáticamente.

## Crear APK
Abrir en Android Studio y ejecutar `app > assembleDebug`, o subir el
proyecto a GitHub y ejecutar el workflow `Build APK` (Actions →
`Build APK` → `Run workflow`); el APK queda en el artefacto
`Balonmano-debug`.
