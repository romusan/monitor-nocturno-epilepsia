# Viabilidad tecnica y plan de investigacion

## Decision

La idea es viable como **sistema experimental de deteccion de movimiento motor nocturno**
y herramienta de recoleccion de datos personalizados. No es viable afirmar deteccion
clinica confiable con un unico telefono situado en una cama compartida: el sensor mide la
vibracion del soporte y no la identidad de quien se mueve; ademas, las crisis sin componente
motor fuerte pueden ser invisibles para el acelerometro.

## Lo que aportan los papers locales

- *Tonic-clonic seizure detection using accelerometry-based wearable sensors* es el
  antecedente de mayor valor metodologico. Es prospectivo y controlado con video-EEG:
  75 candidatos a cirugia, dos acelerometros triaxiales (uno en cada muneca), 37 crisis
  tonico-clonicas repartidas entre entrenamiento y prueba. KNN alcanzo 100% de sensibilidad
  con 0,05 falsos positivos/hora; Random Forest, 90% con 0,01 falsos positivos/hora.
  Sus caracteristicas incluyen amplitud, entropia, cruces por cero, autocorrelacion y
  energia por bandas, seguidas por una capa temporal de decision y veto frecuencial.
- *Neonatal Seizure Detection Using a Wearable Multi-Sensor System* muestra que combinar
  modalidades mejora el balance global y reduce falsas alarmas frente a modalidades
  aisladas. Su poblacion neonatal y sensores fisiologicos no son transferibles directamente
  al telefono en el colchon.
- *An IoT Approach to Personalised Remote Monitoring and Management of Epilepsy* apoya la
  personalizacion y reconoce que para dormir puede ser preferible un sensor de colchon,
  pero es una propuesta arquitectonica, no una validacion clinica del telefono.
- Los trabajos basados en EEG con exactitudes cercanas al 99% no validan este proyecto:
  usan otra senal, bases de datos balanceadas y clasificacion offline.
- Los articulos de bandas inteligentes e IoT aportan patrones de comunicacion y alerta,
  pero varias cifras son resultados secundarios o prototipos pequenos. No deben usarse
  como promesa de desempeno.
- Hay copias duplicadas de dos PDFs; deben contarse una sola vez en la revision.

No se localizo en estos 13 PDFs la combinacion exacta 88,2% de exactitud, 87,1% de
especificidad y 42,8% de precision mencionada en el resumen inicial. Esa referencia debe
identificarse por titulo/DOI antes de citarla en una propuesta academica.

## Arquitectura propuesta

### Etapa 1: adquisicion y disparador local

- acelerometro triaxial, objetivo 50 Hz;
- magnitud independiente de orientacion y eliminacion lenta de gravedad;
- energia corta de 1 s / energia larga de 20 s (STA/LTA adaptativo);
- confirmacion por energia relativa, periodicidad en 2-5 Hz y persistencia;
- notificacion local como "movimiento sostenido: verificar", nunca "convulsion detectada";
- buffer circular y CSV del intervalo anterior y posterior al disparo.

### Etapa 2: modelo personalizado

Con suficientes eventos etiquetados, extraer por ventanas RMS, desviacion, pico, factor de
cresta, cruces por cero, frecuencia dominante, energia 0,5-2 / 2-5 / 5-10 / 10-20 Hz,
autocorrelacion y duracion. Empezar con Random Forest o SVM y calibracion por persona. La
unidad de particion debe ser la noche completa. Las metricas primarias son sensibilidad por
episodio y falsas alarmas por hora; accuracy no es informativa con clases muy desbalanceadas.

### Evolucion de hardware

1. telefono solo: factibilidad de adquisicion y etiquetado;
2. segundo telefono o sensor BLE cerca de la nina: comparacion espacial y rechazo de
   movimiento de adultos;
3. sensor corporal validado o multimodal (movimiento + frecuencia cardiaca/EDA), sujeto a
   aprobacion clinica y etica.

## Criterio de avance

Antes de habilitar Telegram automaticamente: al menos 20 noches normales, pruebas de
movimientos imitadores (giro, tos, levantarse, vibracion externa), verificacion de todos los
episodios motores disponibles y revision de falsas alarmas. El umbral final debe elegirse
priorizando sensibilidad y reportando intervalo de confianza, no por una sola noche.

## Riesgos y salvaguardas

- Un falso negativo es silencioso: mantener siempre el protocolo medico independiente.
- Un falso positivo puede producir fatiga de alarmas: usar escalamiento y periodo refractario.
- Los CSV son datos sensibles: conservarlos localmente, sin nombres, cifrar respaldos y no
  subirlos al repositorio.
- Telegram es util para el piloto, pero el token nunca debe incluirse en el APK ni en Git.
  Una version posterior debe llamar a un backend propio que guarde el secreto.

