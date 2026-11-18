# API que la web debe exponer para la app GPSCamiones

Todas las llamadas usan el token del login (`Authorization: Bearer <token>`)
y el identificador del celular `deviceId` (= `ANDROID_ID`, se ve en la app en
**Administración → Código del celular**).

---

## 1. Configuración del celular (asignación de camión, ruta y geocercas)

`GET /api/mobile/device/config?deviceId=<ANDROID_ID>`
Cabeceras extra: `X-Device-Id`, `X-Device-Model`.

La app la consulta al abrir Inicio/Administración, al recuperar señal y cada
10 minutos mientras el seguimiento está activo. La última respuesta se guarda
en el celular, así que ruta y geocercas siguen funcionando sin señal.

**Recomendación para la web:** si el `deviceId` no existe, regístralo como
"pendiente de asignación" (con el modelo del celular y el usuario que inició
sesión) y responde `assigned: false`. Así en tu web aparece una lista de
celulares nuevos a los que les asignas un camión.

```json
{
  "assigned": true,
  "configVersion": "2026-11-18T07:00:00Z",
  "vehicle": { "providerUnitId": 1234, "plate": "ABC-123", "name": "Camión 5" },
  "route": {
    "id": 77,
    "name": "Ruta Norte",
    "date": "2026-11-18",
    "stops": [
      { "id": 501, "order": 1, "name": "Bodega Don Lucho", "latitude": -12.0461, "longitude": -77.0428, "radiusMeters": 60 },
      { "id": 502, "order": 2, "name": "Minimarket Sol",   "latitude": -12.0502, "longitude": -77.0391, "radiusMeters": 60 }
    ]
  },
  "geofences": [
    { "id": "10", "name": "Almacén central", "type": "BASE", "shape": "circle",
      "latitude": -12.0700, "longitude": -77.0500, "radiusMeters": 150 },
    { "id": "11", "name": "Mercado Mayorista", "type": "CLIENTE", "shape": "polygon",
      "points": [[-12.051, -77.041], [-12.051, -77.039], [-12.053, -77.039], [-12.053, -77.041]] },
    { "id": "12", "name": "Zona de riesgo", "type": "RESTRINGIDA", "shape": "circle",
      "latitude": -12.0800, "longitude": -77.0300, "radiusMeters": 300 }
  ],
  "thresholds": {
    "stopSpeedMps": 1.5,
    "stopRadiusMeters": 40,
    "stopExitRadiusMeters": 60,
    "stopMinSeconds": 60,
    "trafficMaxSeconds": 180,
    "deliveryMinSeconds": 120,
    "maxAccuracyMeters": 50
  }
}
```

- `assigned: false` (o sin `vehicle`) → la app quita la asignación web y vuelve
  al nombre manual del camión.
- `type` de geocerca: `BASE`, `CLIENTE`, `ZONA`, `RESTRINGIDA`.
- `shape`: `circle` (latitude, longitude, radiusMeters) o `polygon`
  (`points` = lista de `[lat, lng]`, mínimo 3).
- Cada parada de la ruta se trata automáticamente como geocerca de entrega.
- `thresholds` es opcional; si falta se usan los valores mostrados.

## 2. Posiciones (ya existía, con dos cambios)

`POST /api/mobile/positions/batch` — igual que antes, más:

- Cada punto trae `recordedOffline: true|false` (se registró sin señal y se
  subió después).
- Un mismo celular puede mandar lotes con **distinto** `providerUnitId`: las
  posiciones guardan el camión que tenía asignado cuando se registraron. Si
  reasignas el celular mientras estaba sin señal, cada posición va al camión
  correcto.
- Las posiciones acumuladas sin señal llegan en orden y en lotes de 50
  seguidos al recuperar conexión. Deben insertarse por `gpsTimestamp`, no por
  hora de llegada. Responder `{ "processedIds": [...] }` como hasta ahora (los
  ids repetidos deben aceptarse y devolverse, para que el reintento sea seguro).

## 3. Eventos de paradas y geocercas (nuevo)

`POST /api/mobile/events/batch`

```json
{
  "deviceId": "a1b2c3d4e5f6a7b8",
  "providerUnitId": 1234,
  "plate": "ABC-123",
  "events": [
    { "id": "uuid", "type": "GEOFENCE_ENTER", "timestamp": 1763450000000,
      "latitude": -12.0461, "longitude": -77.0428, "recordedOffline": false,
      "routeId": 77, "routeStopId": 501, "geofenceType": "CLIENTE", "placeName": "Bodega Don Lucho" },
    { "id": "uuid", "type": "STOP_START", "timestamp": 1763450030000,
      "latitude": -12.0461, "longitude": -77.0428, "recordedOffline": false, "routeId": 77 },
    { "id": "uuid", "type": "STOP_END", "timestamp": 1763450630000,
      "latitude": -12.0461, "longitude": -77.0428, "recordedOffline": false, "routeId": 77,
      "stopType": "POSIBLE_ENTREGA", "startTimestamp": 1763450030000, "endTimestamp": 1763450630000,
      "durationSeconds": 600, "routeStopId": 501, "placeName": "Bodega Don Lucho" },
    { "id": "uuid", "type": "GEOFENCE_EXIT", "timestamp": 1763450660000,
      "latitude": -12.0465, "longitude": -77.0425, "recordedOffline": false,
      "routeId": 77, "routeStopId": 501, "geofenceType": "CLIENTE", "placeName": "Bodega Don Lucho" }
  ]
}
```

Respuesta: `{ "processedIds": ["uuid", ...] }`

| `type`           | Significado |
|------------------|-------------|
| `STOP_START`     | El camión lleva ≥ `stopMinSeconds` quieto (permite mostrar "detenido hace X min") |
| `STOP_END`       | Arrancó de nuevo; trae duración y clasificación `stopType` |
| `GEOFENCE_ENTER` | Entró a una geocerca o parada de ruta (`geofenceId` o `routeStopId`) |
| `GEOFENCE_EXIT`  | Salió de ella |

| `stopType`      | Regla en el celular |
|-----------------|---------------------|
| `POSIBLE_ENTREGA` | Quieto ≥ `deliveryMinSeconds` dentro de un CLIENTE o una parada de la ruta |
| `BASE`            | Quieto dentro de una geocerca BASE (almacén) |
| `TRAFICO`         | Quieto menos de `trafficMaxSeconds` fuera de puntos de entrega (probable, no comprobado) |
| `NO_PROGRAMADA`   | Quieto más de `trafficMaxSeconds` fuera de geocercas (almuerzo, avería, cliente no registrado) |

Las detenciones menores a `stopMinSeconds` (semáforos) no generan evento.

### Qué prueba y qué no prueba el GPS

Todas las clasificaciones son **inferencias por ubicación y tiempo**, no hechos:

- `POSIBLE_ENTREGA` solo dice que el camión estuvo detenido el tiempo suficiente
  donde está el cliente. **No prueba que el pedido se entregó**: el camión pudo
  estacionar ahí sin descargar, el cliente pudo estar cerrado o rechazar la
  mercadería. Tampoco prueba qué se entregó ni en qué cantidad.
- `TRAFICO` y `NO_PROGRAMADA` tampoco son seguros: una entrega a un cliente que
  no está como geocerca saldrá `NO_PROGRAMADA`, y una descarga rápida puede
  salir como `TRAFICO`.

En la web, trata la entrega como **confirmada** solo cuando haya evidencia
operativa además del GPS, por ejemplo:

1. Confirmación del conductor en la app (botón "Entregado / Rechazado / Parcial",
   con foto o firma). *Aún no implementado en la app.*
2. Recepción del cliente (firma, código o llamada de verificación).
3. Documentos del ERP: guía de remisión, factura o nota de entrega cerrada.

Recomendación para el modelo de datos: guarda el estado de la parada aparte de
la clasificación GPS, por ejemplo `estado_entrega = PENDIENTE | POSIBLE (GPS) |
CONFIRMADA | RECHAZADA | PARCIAL`, y que solo la evidencia la mueva a
`CONFIRMADA`. El evento GPS sirve para detectar inconsistencias: una entrega
confirmada en el ERP sin que el camión haya pasado por el cliente, o una parada
larga en el cliente sin documento.

### Tablas sugeridas (SQL Server)

```sql
CREATE TABLE dbo.DispositivosMoviles (
  device_id        varchar(64)  NOT NULL PRIMARY KEY,
  modelo           varchar(120) NULL,
  id_vehiculo      int          NULL REFERENCES dbo.Vehiculos(id),  -- NULL = pendiente
  activo           bit          NOT NULL DEFAULT 1,
  ultimo_contacto  datetime2    NULL
);

CREATE TABLE dbo.EventosMovil (
  id               uniqueidentifier NOT NULL PRIMARY KEY,
  device_id        varchar(64)  NOT NULL,
  id_unidad_proveedor int       NOT NULL,
  tipo             varchar(20)  NOT NULL,       -- STOP_START, STOP_END, GEOFENCE_ENTER, GEOFENCE_EXIT
  tipo_parada      varchar(20)  NULL,           -- POSIBLE_ENTREGA, TRAFICO, NO_PROGRAMADA, BASE (inferido por GPS)
  fecha_utc        datetime2    NOT NULL,
  inicio_utc       datetime2    NULL,
  fin_utc          datetime2    NULL,
  duracion_s       int          NULL,
  latitud          float        NOT NULL,
  longitud         float        NOT NULL,
  id_ruta          bigint       NULL,
  id_parada_ruta   bigint       NULL,
  id_geocerca      varchar(64)  NULL,
  registrado_sin_senal bit      NOT NULL DEFAULT 0,
  recibido_utc     datetime2    NOT NULL DEFAULT sysutcdatetime()
);
```

---

## Prueba en campo (obligatoria antes de producción)

Las pruebas unitarias (`DetectorParadasTest`) solo comprueban la lógica con
puntos GPS simulados. **No prueban** que Android entregue posiciones en un
recorrido real. Eso depende del celular, de la versión de Android, del
fabricante y del ahorro de batería. Antes de confiar en los datos, hay que
probar en al menos un recorrido real por cada modelo de celular que se use:

| Escenario | Cómo probarlo | Qué revisar en la web |
|---|---|---|
| Pantalla apagada | Recorrido de 1 h con el celular bloqueado en el bolsillo o soporte | Que no haya huecos mayores a 1–2 min entre posiciones |
| Sin internet | Activar modo avión (dejando el GPS encendido) 20–30 min en marcha, luego quitarlo | Que lleguen todas las posiciones del tramo con `recordedOffline: true` y en orden |
| Ahorro de batería | Repetir con el ahorro de batería del sistema activado y con la app sin "batería sin restricciones" | Si Android detiene el servicio (Xiaomi, Huawei, Samsung, Oppo suelen hacerlo) |
| Reinicio | Apagar y encender el celular a mitad de ruta | Que el seguimiento se reanude solo |
| Turno completo | Jornada entera de 8–10 h | Consumo de batería, temperatura, datos móviles |
| Paradas reales | Anotar a mano 10–20 paradas (hora, motivo real) y compararlas con lo detectado | Cuántas entregas, semáforos y atascos clasificó bien o mal; ajustar `thresholds` |
| Geocercas | Pasar por el borde, entrar y salir de clientes y del almacén | Entradas/salidas duplicadas o faltantes |

Ajustes del celular que suelen ser necesarios: ubicación "Permitir todo el
tiempo", batería "Sin restricciones" para GPSCamiones, inicio automático
permitido (en Xiaomi/Huawei/Oppo) y no cerrar la app desde "recientes" en
equipos que matan los procesos.
