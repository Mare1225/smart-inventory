-- Stock exclusivo para la prueba de carga (no forma parte de las migraciones: no debe existir en staging/prod).
-- Idempotente: re-ejecutarlo repone el stock del SKU de carga.
insert into batch (sku, location, lot_code, expires_on, physical, reserved)
values ('SKU-PERF', 'BOD-UIO', 'PERF-1', current_date + 365, 5000000, 0)
on conflict (sku, location, lot_code) do update set physical = excluded.physical, reserved = 0;
