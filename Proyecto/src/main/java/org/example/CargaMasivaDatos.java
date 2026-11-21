package org.example;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class CargaMasivaDatos {

    private static final String URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    private static final int TOTAL_TIENDAS = 20;
    private static final int TOTAL_PRODUCTOS = 2_000;
    private static final int TOTAL_CLIENTES = 50_000;
    private static final int TOTAL_VENTAS = 150_000;
    private static final int DETALLES_POR_VENTA = 3;

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);

        System.out.println("=== INICIANDO CARGA MASIVA DISTRIBUIDA EN CITUS ===");
        long tiempoTotalInicio = System.currentTimeMillis();

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            PGConnection pgConn = conn.unwrap(PGConnection.class);
            CopyManager copyManager = pgConn.getCopyAPI();

            // 1. Cargar TIENDAS (Tabla de referencia)
            cargarTiendas(copyManager);

            // 2. Cargar PRODUCTOS (Tabla de referencia) y guardar precios en memoria
            double[] preciosProductos = cargarProductos(copyManager);

            // 3. Cargar CLIENTES (Distribuida por cliente_id)
            cargarClientes(copyManager);

            // 4. Cargar VENTAS y DETALLE_VENTAS (Distribuidas y colocadas por tienda_id)
            cargarVentasYDetalles(copyManager, preciosProductos);

            long tiempoTotalFin = System.currentTimeMillis();
            System.out.println("\n====================================================");
            System.out.println("CARGA COMPLETA EXITOSA (652,020 registros totales)");
            System.out.println("Tiempo total de ejecución: " + (tiempoTotalFin - tiempoTotalInicio) + " ms");
            System.out.println("====================================================");

        } catch (Exception e) {
            System.err.println("Error durante la carga masiva: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void cargarTiendas(CopyManager copyManager) throws Exception {
        String[] ciudades = {"Quito", "Guayaquil", "Cuenca", "Ambato", "Manta"};
        StringBuilder sb = new StringBuilder();

        for (int i = 1; i <= TOTAL_TIENDAS; i++) {
            String ciudad = ciudades[i % ciudades.length];
            sb.append(i).append(',')
                    .append("Supermercado Sucursal ").append(i).append(',')
                    .append(ciudad).append(',')
                    .append("Av. Principal y Calle ").append(i * 10).append('\n');
        }

        ejecutarCopy(copyManager, "tiendas (tienda_id, nombre, ciudad, direccion)", sb);
    }

    private static double[] cargarProductos(CopyManager copyManager) throws Exception {
        String[] categorias = {"Lacteos", "Carnes", "Frutas y Verduras", "Panaderia", "Bebidas", "Limpieza", "Abarrotes"};
        double[] precios = new double[TOTAL_PRODUCTOS + 1];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(TOTAL_PRODUCTOS * 50);

        for (int i = 1; i <= TOTAL_PRODUCTOS; i++) {
            String categoria = categorias[i % categorias.length];
            double precio = Math.round(random.nextDouble(0.50, 45.00) * 100.0) / 100.0;
            precios[i] = precio;

            sb.append(i).append(',')
                    .append("Producto ").append(categoria).append(" #").append(i).append(',')
                    .append(categoria).append(',')
                    .append(String.format("%.2f", precio)).append('\n');
        }

        ejecutarCopy(copyManager, "productos (producto_id, nombre, categoria, precio_unitario)", sb);
        return precios;
    }

    private static void cargarClientes(CopyManager copyManager) throws Exception {
        String[] ciudades = {"Quito", "Guayaquil", "Cuenca", "Ambato", "Manta", "Loja", "Ibarra"};
        StringBuilder sb = new StringBuilder(TOTAL_CLIENTES * 55);

        for (int i = 1; i <= TOTAL_CLIENTES; i++) {
            String ciudad = ciudades[i % ciudades.length];
            sb.append(i).append(',')
                    .append("Cliente ").append(i).append(',')
                    .append("cliente").append(i).append("@correo.com").append(',')
                    .append(ciudad).append('\n');
        }

        ejecutarCopy(copyManager, "clientes (cliente_id, nombre, email, ciudad_residencia)", sb);
    }

    private static void cargarVentasYDetalles(CopyManager copyManager, double[] preciosProductos) throws Exception {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sbVentas = new StringBuilder(TOTAL_VENTAS * 50);
        StringBuilder sbDetalles = new StringBuilder(TOTAL_VENTAS * DETALLES_POR_VENTA * 40);

        long detalleIdCounter = 1;

        for (int ventaId = 1; ventaId <= TOTAL_VENTAS; ventaId++) {
            // Elegimos tienda_id una vez por venta para garantizar co-location con sus detalles
            int tiendaId = random.nextInt(1, TOTAL_TIENDAS + 1);
            int clienteId = random.nextInt(1, TOTAL_CLIENTES + 1);

            // Fecha aleatoria dentro de 2026
            int mes = random.nextInt(1, 12);
            int dia = random.nextInt(1, 29);
            int hora = random.nextInt(8, 22);
            int min = random.nextInt(0, 60);
            String fechaVenta = String.format("2026-%02d-%02d %02d:%02d:00", mes, dia, hora, min);

            double totalVenta = 0.0;

            // Generamos los 3 ítems de detalle para esta venta
            for (int d = 0; d < DETALLES_POR_VENTA; d++) {
                int productoId = random.nextInt(1, TOTAL_PRODUCTOS + 1);
                int cantidad = random.nextInt(1, 6);
                double subtotal = Math.round((preciosProductos[productoId] * cantidad) * 100.0) / 100.0;
                totalVenta += subtotal;

                sbDetalles.append(detalleIdCounter++).append(',')
                        .append(ventaId).append(',')
                        .append(tiendaId).append(',')
                        .append(productoId).append(',')
                        .append(cantidad).append(',')
                        .append(String.format("%.2f", subtotal)).append('\n');
            }

            sbVentas.append(ventaId).append(',')
                    .append(tiendaId).append(',')
                    .append(clienteId).append(',')
                    .append(fechaVenta).append(',')
                    .append(String.format("%.2f", totalVenta)).append('\n');
        }

        // Primero insertamos las cabeceras (ventas) y luego los hijos (detalle_ventas) por la Foreign Key
        ejecutarCopy(copyManager, "ventas (venta_id, tienda_id, cliente_id, fecha_venta, total)", sbVentas);
        ejecutarCopy(copyManager, "detalle_ventas (detalle_id, venta_id, tienda_id, producto_id, cantidad, subtotal)", sbDetalles);
    }

    private static void ejecutarCopy(CopyManager copyManager, String tablaYColumnas, StringBuilder csvData) throws Exception {
        String sql = "COPY " + tablaYColumnas + " FROM STDIN WITH (FORMAT csv)";
        InputStream inputStream = new ByteArrayInputStream(csvData.toString().getBytes(StandardCharsets.UTF_8));

        long inicio = System.currentTimeMillis();
        long filas = copyManager.copyIn(sql, inputStream);
        long fin = System.currentTimeMillis();

        String nombreTabla = tablaYColumnas.split(" ")[0];
        System.out.printf("-> Tabla %-16s | Filas insertadas: %-8d | Tiempo: %d ms%n", nombreTabla, filas, (fin - inicio));
    }
}