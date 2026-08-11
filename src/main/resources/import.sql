-- Seed de planes de facturacion.
-- Hibernate ejecuta este fichero SOLO tras crear el schema (ddl-auto=create/create-drop),
-- por eso los INSERT son directos: la tabla siempre esta vacia cuando corre.
-- En prod (ddl-auto=validate) no se ejecuta; los planes se cargan por otra via.

INSERT INTO BILLING_PLAN (ID, NAME, MAX_SPEED, MAX_TRAFFIC, MAX_DEVICES, MAX_CONCURRENT_TRANSFERS, MONTHLY_PRICE, ANNUAL_PRICE, ENCRYPTED_FOLDERS_INCLUDED, ENABLED) VALUES
    (1, 'FREE',      10,       2000, 2,   1, 0,     0,     FALSE, TRUE);
INSERT INTO BILLING_PLAN (ID, NAME, MAX_SPEED, MAX_TRAFFIC, MAX_DEVICES, MAX_CONCURRENT_TRANSFERS, MONTHLY_PRICE, ANNUAL_PRICE, ENCRYPTED_FOLDERS_INCLUDED, ENABLED) VALUES
    (2, 'BASIC',     50,      20000, 5,   3, 499,   4990,  FALSE, TRUE);
INSERT INTO BILLING_PLAN (ID, NAME, MAX_SPEED, MAX_TRAFFIC, MAX_DEVICES, MAX_CONCURRENT_TRANSFERS, MONTHLY_PRICE, ANNUAL_PRICE, ENCRYPTED_FOLDERS_INCLUDED, ENABLED) VALUES
    (3, 'PRO',      200,     200000, 10, 10, 999,   9990,  TRUE,  TRUE);
INSERT INTO BILLING_PLAN (ID, NAME, MAX_SPEED, MAX_TRAFFIC, MAX_DEVICES, MAX_CONCURRENT_TRANSFERS, MONTHLY_PRICE, ANNUAL_PRICE, ENCRYPTED_FOLDERS_INCLUDED, ENABLED) VALUES
    (4, 'BUSINESS',   0,          0, 50,  0, 2499,  24990, TRUE,  TRUE);

-- La secuencia gen es compartida por todas las entidades (@SequenceGenerator(name="gen")).
-- Reservamos el rango bajo para seeds, el resto para IDs generados.
ALTER SEQUENCE GEN RESTART WITH 1000;
