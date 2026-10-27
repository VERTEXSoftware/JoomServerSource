package org.network.admin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Загрузчик конфигурации админ-панели из файла admin_config.properties.
 * Если файла нет — он создаётся с настройками по умолчанию.
 */
public class AdminConfig {

    private static final String DEFAULT_CONFIG =
            "# ============================================\n" +
            "#  Настройки админ-панели игрового сервера\n" +
            "# ============================================\n" +
            "\n" +
            "# Порт веб-интерфейса админ-панели\n" +
            "admin.port=8090\n" +
            "\n" +
            "# Пароль администратора (ОБЯЗАТЕЛЬНО смените!)\n" +
            "admin.password=admin123\n" +
            "\n" +
            "# Публичный URL админки — он подставляется в ссылку скачивания карты,\n" +
            "# которую получает клиент. Укажите внешний IP сервера.\n" +
            "admin.public-url=http://127.0.0.1:8090\n" +
            "\n" +
            "# Папка для хранения загруженных карт (.slop)\n" +
            "maps.dir=maps\n" +
            "\n" +
            "# Папка с файлом интерфейса admin.html\n" +
            "web.dir=web\n" +
            "\n" +
            "# ============================================\n" +
            "#  Подключение к MySQL\n" +
            "# ============================================\n" +
            "db.host=localhost\n" +
            "db.port=3306\n" +
            "db.name=game_server\n" +
            "db.user=root\n" +
            "db.password=\n" +
            "\n" +
            "# ============================================\n" +
            "#  Игровые настройки (можно менять в админ-панели)\n" +
            "# ============================================\n" +
            "# Порт игрового сервера (1-65535). Читается при СТАРТЕ сервера:\n" +
            "# меняется только правкой этого файла и перезапуском сервера.\n" +
            "game.port=12789\n" +
            "# Минимум готовых игроков для автостарта матча (1-8)\n" +
            "game.min-players-start=2\n" +
            "# Максимум игроков на сервере (2-64)\n" +
            "game.max-players=16\n" +
            "# Длительность матча в секундах (30-7200)\n" +
            "game.match-duration=180\n" +
            "\n" +
            "# Хеш соединения — хранится как HEX-строка (до 16 hex-цифр).\n" +
            "# 0 — проверка отключена. Если не 0 — клиент с другим hash отключается при логине.\n" +
            "game.connection-hash=0\n";

    private final Properties props = new Properties();
    private final String configPath;

    public AdminConfig(String configPath) {
        this.configPath = configPath;
        File file = new File(configPath);
        if (file.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                props.load(new java.io.InputStreamReader(in, "UTF-8"));
                System.out.println("[АДМИН] Конфигурация загружена: " + configPath);
            } catch (IOException e) {
                System.err.println("[АДМИН] Ошибка чтения конфига: " + e.getMessage());
            } finally {
                closeQuietly(in);
            }
        } else {
            OutputStream out = null;
            try {
                out = new FileOutputStream(file);
                out.write(DEFAULT_CONFIG.getBytes("UTF-8"));
                System.out.println("[АДМИН] Создан новый конфигурационный файл: " + configPath);
                System.out.println("[АДМИН] Настройте в нём подключение к MySQL и перезапустите сервер!");
            } catch (IOException e) {
                System.err.println("[АДМИН] Не удалось создать конфиг: " + e.getMessage());
            } finally {
                closeQuietly(out);
            }
            // Загружаем значения по умолчанию в память, чтобы сервер мог работать сразу
            try {
                props.load(new java.io.StringReader(DEFAULT_CONFIG));
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }

    // ---------- Админ-панель ----------

    public int getPort() {
        return Integer.parseInt(props.getProperty("admin.port", "8090").trim());
    }

    public String getPassword() {
        return props.getProperty("admin.password", "admin123").trim();
    }

    public String getPublicUrl() {
        String url = props.getProperty("admin.public-url", "").trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public String getMapsDir() {
        return props.getProperty("maps.dir", "maps").trim();
    }

    public String getWebDir() {
        return props.getProperty("web.dir", "web").trim();
    }

    // ---------- Игровые настройки (меняются и через админ-панель) ----------

    /**
     * Порт игрового сервера (ключ game.port). Читается один раз при старте в GameWorld.
     * Если ключа в файле нет (старый конфиг) или значение некорректно — 12789 (прежний порт).
     */
    public int getGamePort() {
        return parseIntSafe(props.getProperty("game.port", "12789"), 12789);
    }

    public int getMinPlayersStart() {
        return parseIntSafe(props.getProperty("game.min-players-start", "2"), 2);
    }

    public int getMaxPlayersLimit() {
        return parseIntSafe(props.getProperty("game.max-players", "16"), 16);
    }

    public int getMatchDuration() {
        return parseIntSafe(props.getProperty("game.match-duration", "180"), 180);
    }

    /** НОВОЕ: хеш соединения в виде HEX-строки, как он хранится в конфиге ("0" — отключён). */
    public String getConnectionHashHex() {
        return props.getProperty("game.connection-hash", "0").trim();
    }

    /**
     * Обновить игровые настройки в памяти и ПЕРСИСТНУТЬ в файл.
     * Файл переписывается построчно: комментарии и остальные ключи сохраняются,
     * отсутствующие ключи добавляются в конец.
     */
    public synchronized void saveGameSettings(int minPlayersStart, int maxPlayers, int matchDuration) {
        props.setProperty("game.min-players-start", String.valueOf(minPlayersStart));
        props.setProperty("game.max-players", String.valueOf(maxPlayers));
        props.setProperty("game.match-duration", String.valueOf(matchDuration));

        try {
            File file = new File(configPath);
            java.util.List<String> lines = file.exists()
                    ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
                    : new java.util.ArrayList<String>();

            boolean seenMin = false, seenMax = false, seenDur = false;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                String trimmed = l.trim();
                if (trimmed.startsWith("game.min-players-start=")) {
                    lines.set(i, "game.min-players-start=" + minPlayersStart); seenMin = true;
                } else if (trimmed.startsWith("game.max-players=")) {
                    lines.set(i, "game.max-players=" + maxPlayers); seenMax = true;
                } else if (trimmed.startsWith("game.match-duration=")) {
                    lines.set(i, "game.match-duration=" + matchDuration); seenDur = true;
                }
            }
            if (!seenMin) lines.add("game.min-players-start=" + minPlayersStart);
            if (!seenMax) lines.add("game.max-players=" + maxPlayers);
            if (!seenDur) lines.add("game.match-duration=" + matchDuration);

            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file);
                for (String l : lines) {
                    out.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } finally {
                closeQuietly(out);
            }
            System.out.println("[АДМИН] Игровые настройки сохранены в " + configPath);
        } catch (IOException e) {
            System.err.println("[АДМИН] Не удалось сохранить настройки в файл: " + e.getMessage());
        }
    }

    /**
     * НОВОЕ: ПЕРСИСТНУТЬ хеш соединения (хранится как HEX-строка).
     * Файл переписывается построчно: комментарии и остальные ключи сохраняются,
     * отсутствующий ключ добавляется в конец.
     */
    public synchronized void saveConnectionHash(String hex) {
        props.setProperty("game.connection-hash", hex);

        try {
            File file = new File(configPath);
            java.util.List<String> lines = file.exists()
                    ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
                    : new java.util.ArrayList<String>();

            boolean seenHash = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("game.connection-hash=")) {
                    lines.set(i, "game.connection-hash=" + hex);
                    seenHash = true;
                }
            }
            if (!seenHash) lines.add("game.connection-hash=" + hex);

            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file);
                for (String l : lines) {
                    out.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } finally {
                closeQuietly(out);
            }
            System.out.println("[АДМИН] Хеш соединения сохранён в " + configPath);
        } catch (IOException e) {
            System.err.println("[АДМИН] Не удалось сохранить хеш соединения в файл: " + e.getMessage());
        }
    }

    private int parseIntSafe(String raw, int def) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- MySQL ----------

    public String getDbHost()   { return props.getProperty("db.host", "localhost").trim(); }
    public int    getDbPort()   { return Integer.parseInt(props.getProperty("db.port", "3306").trim()); }
    public String getDbName()   { return props.getProperty("db.name", "game_server").trim(); }
    public String getDbUser()   { return props.getProperty("db.user", "root").trim(); }
    public String getDbPassword() { return props.getProperty("db.password", ""); }
}
