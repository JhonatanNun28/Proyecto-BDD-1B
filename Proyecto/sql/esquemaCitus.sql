-- 1. Activar la extensión de Citus (si no está activa)
CREATE EXTENSION IF NOT EXISTS citus;

-- 2. Creación de Tablas Maestras (Futuras Tablas de Referencia)
CREATE TABLE tiendas (
                         tienda_id INT PRIMARY KEY,
                         nombre VARCHAR(100) NOT NULL,
                         ciudad VARCHAR(50) NOT NULL,
                         direccion VARCHAR(150)
);

CREATE TABLE productos (
                           producto_id INT PRIMARY KEY,
                           nombre VARCHAR(100) NOT NULL,
                           categoria VARCHAR(50) NOT NULL,
                           precio_unitario NUMERIC(10, 2) NOT NULL
);

-- 3. Creación de Tablas Transaccionales y de Clientes
-- REGLA DE CITUS: Toda Primary Key en una tabla distribuida DEBE incluir la columna de distribución.
CREATE TABLE clientes (
                          cliente_id INT PRIMARY KEY,
                          nombre VARCHAR(100) NOT NULL,
                          email VARCHAR(100) NOT NULL,
                          ciudad_residencia VARCHAR(50)
);

CREATE TABLE ventas (
                        venta_id BIGINT,
                        tienda_id INT NOT NULL,
                        cliente_id INT NOT NULL,
                        fecha_venta TIMESTAMP NOT NULL,
                        total NUMERIC(12, 2) NOT NULL,
                        PRIMARY KEY (venta_id, tienda_id)
);

CREATE TABLE detalle_ventas (
                                detalle_id BIGINT,
                                venta_id BIGINT NOT NULL,
                                tienda_id INT NOT NULL,
                                producto_id INT NOT NULL,
                                cantidad INT NOT NULL,
                                subtotal NUMERIC(10, 2) NOT NULL,
                                PRIMARY KEY (detalle_id, tienda_id),
    -- Llave foránea local garantizada por co-location con 'ventas'
                                FOREIGN KEY (venta_id, tienda_id) REFERENCES ventas (venta_id, tienda_id)
);

-- 4. Configuración de Distribución y Replicación en Citus
-- Tablas replicadas en todos los workers (alta velocidad para JOINs)
SELECT create_reference_table('tiendas');
SELECT create_reference_table('productos');

-- Tablas fragmentadas por hash
SELECT create_distributed_table('clientes', 'cliente_id');
SELECT create_distributed_table('ventas', 'tienda_id');
-- Al usar la misma columna de distribución ('tienda_id'), Citus coloca sus shards junto a 'ventas'
SELECT create_distributed_table('detalle_ventas', 'tienda_id', colocate_with => 'ventas');

-- 5. Llaves foráneas hacia tablas de referencia (permitidas en Citus)
ALTER TABLE ventas ADD CONSTRAINT fk_ventas_tienda FOREIGN KEY (tienda_id) REFERENCES tiendas (tienda_id);
ALTER TABLE detalle_ventas ADD CONSTRAINT fk_detalle_producto FOREIGN KEY (producto_id) REFERENCES productos (producto_id);

-- 6. Índices para acelerar consultas por rango de fechas y agregaciones
CREATE INDEX idx_ventas_fecha ON ventas (fecha_venta);
CREATE INDEX idx_ventas_cliente ON ventas (cliente_id);