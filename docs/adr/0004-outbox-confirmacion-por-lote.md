# ADR-0004 · Outbox: confirmacion del broker por lote

**Estado:** aceptada · **Historias:** US-02, US-04 · **Defecto:** DEF-01

**Contexto.** La prueba de carga (20 VUs, ~89 reservas/s) mostro el outbox con ~2 700 eventos pendientes y el dashboard
~40 s atrasado. El publicador esperaba `waitForConfirmsOrDie` por **cada** mensaje y tomaba 50 filas cada 500 ms.

**Decision.** Publicar el lote completo (hasta `app.outbox.batch-size`, 200) y esperar **una** confirmacion; si llega,
marcar todo el lote con `published_at`. Si el lote salio lleno, drenar otro en el mismo ciclo (hasta
`max-batches-per-poll`, 10).

**Alternativas.** Subir solo el tamano del lote (sigue un round-trip por mensaje); confirmaciones asincronas por
correlacion (mas complejo de razonar y probar); CDC (excesivo).

**Costos.** Si falla la confirmacion se re-publica el lote entero: mas duplicados en ese caso, absorbidos por
`processed_event`. Transaccion de lectura del outbox algo mas larga (sigue `SKIP LOCKED`, otras instancias no esperan).

**Prueba del beneficio.** `docs/evidencias/rendimiento.md`: pendiente maximo 78 (antes ~2 700), drenado inmediato al
terminar la carga, +39 % de reservas en el mismo tiempo. `MessagingFlowIT` cubre publicacion, duplicados y DLQ.
