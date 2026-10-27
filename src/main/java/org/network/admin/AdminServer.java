package org.network.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.network.GameWorld;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Встроенный веб-сервер админ-панели (com.sun.net.httpserver, без внешних зависимостей).
 *
 * Маршруты:
 *   GET  /            — веб-интерфейс (web/admin.html)
 *   GET  /maps/<файл> — раздача загруженных карт .slop клиентам
 *   /api/...          — REST API (AdminApiHandler)
 */
public class AdminServer {

    private final GameWorld gameWorld;
    private final AdminConfig config;
    private final AdminAuth auth;
    private HttpServer server;

    public AdminServer(GameWorld gameWorld) {
        this.gameWorld = gameWorld;
        this.config = new AdminConfig("admin_config.properties");
        this.auth = new AdminAuth(config.getPassword());
    }

    public AdminConfig getConfig() { return config; }

    public void start() throws IOException {
        int port = config.getPort();

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new RootPageHandler());
        server.createContext("/maps/", new MapFileHandler());
        server.createContext("/api/", new AdminApiHandler(gameWorld, config, auth));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("======================================");
        System.out.println("[АДМИН] Админ-панель запущена: http://localhost:" + port + "/");
        System.out.println("[АДМИН] Пароль по умолчанию — admin123 (меняется в admin_config.properties)");
        System.out.println("======================================");
    }

    /** Главная страница — admin.html из папки web/. */
    private class RootPageHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path)) {
                send404(exchange);
                return;
            }
            byte[] html = loadAdminHtml();
            if (html == null) {
                html = ("<html><body><h3>Файл web/admin.html не найден.</h3>"
                        + "<p>Положите admin.html в папку <b>" + config.getWebDir()
                        + "</b> рядом с сервером.</p></body></html>").getBytes("UTF-8");
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, html.length);
            OutputStream os = exchange.getResponseBody();
            os.write(html);
            os.close();
        }
    }

    private byte[] loadAdminHtml() throws IOException {
        // 1. Из папки web/ рядом с сервером
        File file = new File(config.getWebDir(), "admin.html");
        if (file.exists()) {
            return readFile(file);
        }
        // 2. Из classpath (если admin.html упакован в jar)
        InputStream in = AdminServer.class.getResourceAsStream("/web/admin.html");
        if (in != null) {
            try {
                return readStream(in);
            } finally {
                in.close();
            }
        }
        return null;
    }

    /** Раздача загруженных .slop-карт: GET /maps/<имя_файла> */
    private class MapFileHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath(); // /maps/xxx.slop
            String fileName = path.substring("/maps/".length());

            // Защита от ../ (path traversal)
            File mapsDir = new File(config.getMapsDir()).getCanonicalFile();
            File target = new File(mapsDir, fileName).getCanonicalFile();
            if (!target.getPath().startsWith(mapsDir.getPath()) || !target.isFile()) {
                send404(exchange);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + target.getName() + "\"");
            exchange.sendResponseHeaders(200, target.length());
            OutputStream os = exchange.getResponseBody();
            FileInputStream fis = new FileInputStream(target);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                fis.close();
                os.close();
            }
        }
    }

    private void send404(HttpExchange exchange) throws IOException {
        byte[] body = "404 Not Found".getBytes("UTF-8");
        exchange.sendResponseHeaders(404, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private byte[] readFile(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return readStream(in);
        } finally {
            in.close();
        }
    }

    private byte[] readStream(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
