package org.network.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.network.admin.AdminConfig;

/**
 * Менеджер подключения к MySQL.
 *
 * Особенности:
 *  - база данных создаётся автоматически, если её нет (CREATE DATABASE IF NOT EXISTS);
 *  - таблицы создаются автоматически при первом запуске;
 *  - разорванное соединение восстанавливается автоматически;
 *  - все операции выполняются через withConnection() — единая точка входа
 *    с синхронизацией и защитой от ошибок: падение БД не роняет игровой сервер.
 */
public class DatabaseManager {

    private static DatabaseManager instance;

    private final AdminConfig config;
    private volatile Connection connection;
    private volatile boolean available = false;

    // ---------- DAO-доступы ----------
    private final PlayerDao players = new PlayerDao(this);
    private final MapDao maps = new MapDao(this);
    private final MatchDao matches = new MatchDao(this);
    // НОВОЕ: DAO банов (бан по IP)
    private final BanDao bans = new BanDao(this);

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager(AdminConfigHolder.lastConfig);
        }
        return instance;
    }

    /** Хелпер для передачи конфига в getInstance() при первом обращении. */
    public static final class AdminConfigHolder {
        static AdminConfig lastConfig;
        public static void set(AdminConfig cfg) { lastConfig = cfg; }
    }

    private DatabaseManager(AdminConfig config) {
        this.config = config;
    }

    /** Инициализация с конфигом. Должна вызываться один раз при старте сервера. */
    public static synchronized DatabaseManager init(AdminConfig config) throws SQLException {
        AdminConfigHolder.set(config);
        instance = new DatabaseManager(config);
        instance.connectAndCreateSchema();
        return instance;
    }

    private void connectAndCreateSchema() throws SQLException {
        String host = config.getDbHost();
        int port = config.getDbPort();
        String dbName = config.getDbName();
        String user = config.getDbUser();
        String pass = config.getDbPassword();

        String serverUrl = "jdbc:mysql://" + host + ":" + port + "/"
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC"
                + "&connectTimeout=5000&socketTimeout=30000";

        // 1. Подключаемся без базы и создаём её, если отсутствует
        Connection srv = DriverManager.getConnection(serverUrl, user, pass);
        try {
            Statement st = srv.createStatement();
            try {
                st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            } finally {
                st.close();
            }
        } finally {
            srv.close();
        }

        // 2. Подключаемся к самой базе
        String dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC"
                + "&connectTimeout=5000&socketTimeout=30000";

        connection = DriverManager.getConnection(dbUrl, user, pass);
        available = true;

        createSchema();
        System.out.println("[БД] MySQL подключен: " + host + ":" + port + "/" + dbName);
    }

    private void createSchema() throws SQLException {
        String[] ddl = new String[] {
            "CREATE TABLE IF NOT EXISTS players (" +
            "  id INT AUTO_INCREMENT PRIMARY KEY," +
            "  name VARCHAR(64) NOT NULL UNIQUE," +
            "  model INT DEFAULT 0," +
            "  total_matches INT DEFAULT 0," +
            "  total_kills INT DEFAULT 0," +
            "  total_deaths INT DEFAULT 0," +
            "  first_seen DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  last_seen DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

            "CREATE TABLE IF NOT EXISTS maps (" +
            "  id INT AUTO_INCREMENT PRIMARY KEY," +
            "  map_name VARCHAR(128) NOT NULL UNIQUE," +
            "  file_name VARCHAR(255) DEFAULT NULL," +
            "  url_download VARCHAR(512) NOT NULL," +
            "  file_size BIGINT DEFAULT 0," +
            "  crc64 BIGINT DEFAULT NULL," +   // CRC64 хеш .slop-файла (null/0 — для внешних ссылок без файла)
            "  added_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

            "CREATE TABLE IF NOT EXISTS matches (" +
            "  id INT AUTO_INCREMENT PRIMARY KEY," +
            "  map_name VARCHAR(128) NOT NULL," +
            "  started_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  ended_at DATETIME DEFAULT NULL," +
            "  status VARCHAR(20) DEFAULT 'IN_PROGRESS'," +
            "  players_count INT DEFAULT 0," +
            "  winner_name VARCHAR(64) DEFAULT NULL," +
            "  winner_kills INT DEFAULT 0" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

            "CREATE TABLE IF NOT EXISTS match_stats (" +
            "  id INT AUTO_INCREMENT PRIMARY KEY," +
            "  match_id INT NOT NULL," +
            "  player_id INT NOT NULL," +
            "  player_name VARCHAR(64)," +
            "  kills INT DEFAULT 0," +
            "  deaths INT DEFAULT 0," +
            "  CONSTRAINT fk_ms_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE," +
            "  CONSTRAINT fk_ms_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

            // НОВОЕ: баны по IP (проверяются при логине игрока)
            "CREATE TABLE IF NOT EXISTS bans (" +
            "  id INT AUTO_INCREMENT PRIMARY KEY," +
            "  ip VARCHAR(45) NOT NULL UNIQUE," +
            "  player_name VARCHAR(64) DEFAULT NULL," +
            "  reason VARCHAR(255) DEFAULT NULL," +
            "  banned_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
        };

        Statement st = connection.createStatement();
        try {
            for (String sql : ddl) {
                st.executeUpdate(sql);
            }
        } finally {
            st.close();
        }

        migrateSchema();
    }

    /**
     * Миграции для уже существующих таблиц (ALTER выполняется только если колонки ещё нет).
     */
    private void migrateSchema() throws SQLException {
        // НОВОЕ: колонка maps.crc64 — CRC64 хеш .slop-файла для проверки целостности у клиента
        if (!columnExists("maps", "crc64")) {
            Statement st = connection.createStatement();
            try {
                st.executeUpdate("ALTER TABLE maps ADD COLUMN crc64 BIGINT DEFAULT NULL AFTER file_size");
                System.out.println("[БД] Миграция: добавлена колонка maps.crc64");
            } finally {
                st.close();
            }
        }
    }

    /** Проверка существования колонки в таблице текущей базы данных. */
    private boolean columnExists(String table, String column) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?");
        try {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } finally {
            ps.close();
        }
    }

    /**
     * Единая точка выполнения SQL. Все запросы DAO проходят через этот метод:
     *  - синхронизация (одно соединение, потокобезопасно);
     *  - авто-переподключение при обрыве;
     *  - при любой ошибке возвращается null, игра продолжает работать.
     */
    public <T> T withConnection(SqlWork<T> work) {
        synchronized (this) {
            try {
                if (connection == null || connection.isClosed() || !connection.isValid(2)) {
                    System.out.println("[БД] Соединение потеряно, переподключаюсь...");
                    connectAndCreateSchema();
                }
                return work.run(connection);
            } catch (Exception e) {
                available = false;
                System.err.println("[БД] Ошибка SQL: " + e.getMessage());
                return null;
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public PlayerDao players()  { return players; }
    public MapDao maps()        { return maps; }
    public MatchDao matches()   { return matches; }
    /** НОВОЕ: доступ к DAO банов. */
    public BanDao bans()        { return bans; }

    /** Функциональный интерфейс для withConnection. */
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
