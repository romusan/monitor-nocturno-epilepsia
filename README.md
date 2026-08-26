# Monitor nocturno de movimiento - prototipo de investigacion

Prototipo Android para estudiar si el acelerometro de un telefono colocado sobre el
colchon puede reconocer movimiento ritmico nocturno sostenido. No diagnostica epilepsia,
no detecta todas las crisis y no reemplaza el protocolo indicado por el equipo medico.

## Alcance viable

La primera version se limita a tres funciones verificables:

1. adquirir aceleracion a aproximadamente 50 Hz en un servicio visible;
2. disparar un **evento candidato** cuando la energia STA/LTA y la periodicidad de
   2-5 Hz persisten durante un tiempo configurable;
3. guardar CSV etiquetados (`normal`, `evento_confirmado`, `falsa_alarma`) para construir
   posteriormente un clasificador personalizado.

El telefono debe estar apoyado firmemente en el colchon, cerca de la nina. Si duermen
varias personas en la misma cama, el sistema no puede atribuir el movimiento a una
persona. Esta limitacion no se resuelve con aprendizaje automatico sin sensores o
referencias adicionales.

## Compilar

Desde `android/`:

```text
gradle assembleDebug
```

El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`.
El workflow `.github/workflows/android-apk.yml` compila el APK en cada push a `main` y
tambien se puede ejecutar manualmente desde GitHub Actions.

## Protocolo inicial de datos

- Usar siempre el mismo telefono, posicion, funda, superficie y frecuencia de muestreo.
- Grabar primero varias noches normales y marcar movimientos conocidos.
- Ante un episodio real, anotar inicio/fin observados sin interrumpir el protocolo medico.
- Evaluar por noche y por paciente: sensibilidad de episodios motores y falsas alarmas/hora.
- Separar noches completas entre entrenamiento y prueba; nunca mezclar ventanas de una
  misma noche entre ambos conjuntos.
- No activar alertas remotas hasta superar una validacion prospectiva acordada con el
  equipo clinico.

La sintesis de literatura y el plan experimental estan en `docs/viabilidad.md`.

