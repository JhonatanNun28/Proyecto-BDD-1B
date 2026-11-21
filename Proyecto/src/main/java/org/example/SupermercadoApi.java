package org.example;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.sql.*;
import java.util.*;

public class SupermercadoApi {

    // Conexión al coordinador de Citus
    private static final String URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String USER = "postgres";
    private static final String PASSWORD = "admin_citus";

    public static void main(String[] args) {
        // Iniciamos el servidor Javalin en el puerto 8081 (el 8080 lo usa pgAdmin)
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
        }).start(8081);

        System.out.println("=========================================================");
        System.out.println("API REST y Frontend activos en: http://localhost:8081");
        System.out.println("=========================================================");

        // 1. INSERTAR DATOS (Nueva venta + detalle en transacción distribuida)
        app.post("/api/ventas", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            long ventaId = ((Number) body.get("venta_id")).longValue();
            int tiendaId = ((Number) body.get("tienda_id")).intValue();
            int clienteId = ((Number) body.get("cliente_id")).intValue();
            int productoId = ((Number) body.get("producto_id")).intValue();
            int cantidad = ((Number) body.get("cantidad")).intValue();
            double precioUnitario = ((Number) body.get("precio_unitario")).doubleValue();
            double total = Math.round((cantidad * precioUnitario) * 100.0) / 100.0;

            String sqlVenta = "INSERT INTO ventas (venta_id, tienda_id, cliente_id, fecha_venta, total) VALUES (?, ?, ?, NOW(), ?)";
            String sqlDetalle = "INSERT INTO detalle_ventas (detalle_id, venta_id, tienda_id, producto_id, cantidad, subtotal) VALUES (?, ?, ?, ?, ?, ?)";

            long inicio = System.nanoTime();
            try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
                conn.setAutoCommit(false);
                try (PreparedStatement psVenta = conn.prepareStatement(sqlVenta);
                     PreparedStatement psDetalle = conn.prepareStatement(sqlDetalle)) {

                    psVenta.setLong(1, ventaId);
                    psVenta.setInt(2, tiendaId);
                    psVenta.setInt(3, clienteId);
                    psVenta.setDouble(4, total);
                    psVenta.executeUpdate();

                    psDetalle.setLong(1, System.currentTimeMillis()); // ID único rápido para detalle
                    psDetalle.setLong(2, ventaId);
                    psDetalle.setInt(3, tiendaId);
                    psDetalle.setInt(4, productoId);
                    psDetalle.setInt(5, cantidad);
                    psDetalle.setDouble(6, total);
                    psDetalle.executeUpdate();

                    conn.commit();
                } catch (Exception ex) {
                    conn.rollback();
                    throw ex;
                }
            }
            double tiempoMs = (System.nanoTime() - inicio) / 1_000_000.0;

            ctx.json(Map.of(
                    "mensaje", "Venta y detalle insertados en el shard de la tienda " + tiendaId,
                    "tiempo_ms", Math.round(tiempoMs * 100.0) / 100.0,
                    "total_venta", total
            ));
        });

        // 2. CONSULTA POR RANGO DE FECHAS (Multi-shard)
        app.get("/api/ventas/rango", ctx -> {
            String inicioFecha = ctx.queryParamAsClass("inicio", String.class).getOrDefault("2026-01-01");
            String finFecha = ctx.queryParamAsClass("fin", String.class).getOrDefault("2026-03-31");

            String sql = "SELECT tienda_id, COUNT(*) AS cantidad_ventas, SUM(total) AS monto_total " +
                    "FROM ventas WHERE fecha_venta BETWEEN ?::timestamp AND ?::timestamp " +
                    "GROUP BY tienda_id ORDER BY monto_total DESC";

            ctx.json(ejecutarConsultaConTiempo(sql, inicioFecha + " 00:00:00", finFecha + " 23:59:59"));
        });

        // 3. CONSULTA AGREGADA (JOIN con tabla de referencia 'tiendas' + GROUP BY)
        app.get("/api/ventas/agregado", ctx -> {
            String sql = "SELECT t.ciudad, t.nombre AS sucursal, COUNT(v.venta_id) AS total_facturas, " +
                    "ROUND(SUM(v.total), 2) AS ingresos_totales, ROUND(AVG(v.total), 2) AS ticket_promedio " +
                    "FROM ventas v JOIN tiendas t ON v.tienda_id = t.tienda_id " +
                    "GROUP BY t.ciudad, t.nombre ORDER BY ingresos_totales DESC";

            ctx.json(ejecutarConsultaConTiempo(sql));
        });

        // 4. PRUEBA DISTRIBUIDA A: JOIN CON COLOCACIÓN DE SHARDS (ventas + detalle_ventas por tienda_id)
        app.get("/api/pruebas/join-colocado", ctx -> {
            String sql = "SELECT p.categoria, COUNT(d.detalle_id) AS items_vendidos, SUM(d.subtotal) AS total_categoria " +
                    "FROM ventas v " +
                    "JOIN detalle_ventas d ON v.venta_id = d.venta_id AND v.tienda_id = d.tienda_id " +
                    "JOIN productos p ON d.producto_id = p.producto_id " +
                    "GROUP BY p.categoria ORDER BY total_categoria DESC";

            ctx.json(ejecutarConsultaConTiempo(sql));
        });

        // 5. PRUEBA DISTRIBUIDA B: JOIN SIN COLOCACIÓN DE SHARDS (ventas [tienda_id] + clientes [cliente_id])
        app.get("/api/pruebas/join-no-colocado", ctx -> {
            String sql = "SELECT c.ciudad_residencia, COUNT(v.venta_id) AS compras_realizadas, SUM(v.total) AS gasto_total " +
                    "FROM ventas v " +
                    "JOIN clientes c ON v.cliente_id = c.cliente_id " +
                    "GROUP BY c.ciudad_residencia ORDER BY gasto_total DESC";

            ctx.json(ejecutarConsultaConTiempo(sql));
        });

        // 6. PRUEBA DISTRIBUIDA C: CONSULTA LOCAL (1 solo shard) vs DISTRIBUIDA (todos los shards)
        app.get("/api/pruebas/local-vs-distribuida", ctx -> {
            String tipo = ctx.queryParamAsClass("tipo", String.class).getOrDefault("local");
            String sql;
            if ("local".equalsIgnoreCase(tipo)) {
                // Citus enruta esta consulta a 1 solo shard en 1 solo worker
                sql = "SELECT tienda_id, COUNT(*) AS total_ventas, SUM(total) AS ingresos FROM ventas WHERE tienda_id = 1 GROUP BY tienda_id";
            } else {
                // Citus consulta en paralelo los 32 shards repartidos en todos los workers
                sql = "SELECT COUNT(*) AS total_ventas_globales, SUM(total) AS ingresos_globales FROM ventas";
            }
            ctx.json(ejecutarConsultaConTiempo(sql));
        });
    }

    // Método auxiliar que ejecuta cualquier SELECT, mide los milisegundos y devuelve los resultados
    private static Map<String, Object> ejecutarConsultaConTiempo(String sql, Object... params) throws SQLException {
        List> filas = new ArrayList<>();

        long inicio = System.nanoTime();
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnas = meta.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    for (int c = 1; c <= columnas; c++) {
                        fila.put(meta.getColumnLabel(c), rs.getObject(c));
                    }
                    filas.add(fila);
                }
            }
        }
        double tiempoMs = (System.nanoTime() - inicio) / 1_000_000.0;

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tiempo_ms", Math.round(tiempoMs * 100.0) / 100.0);
        respuesta.put("filas_retornadas", filas.size());
        respuesta.put("sql_ejecutado", sql);
        respuesta.put("datos", filas);
        return respuesta;
    }
}