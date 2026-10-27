package org.network.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Работа с таблицей карт: список карт, добавление (в т.ч. загруженных .slop), удаление.
 */
public class MapDao {

    private final DatabaseManager db;

    public MapDao(DatabaseManager db) {
        this.db = db;
    }

    /** Строка таблицы maps. */
    public static class MapRow {
        public int id;
        public String mapName;
        public String fileName;     // имя .slop-файла на диске (null для внешних ссылок)
        public String urlDownload;  // ссылка для клиента
        public long fileSize;
        public long crc64;          // CRC64 хеш .slop-файла (0 — для внешних ссылок без файла)
        public String addedAt;
    }

    /** Все карты. Возвращает пустой список при ошибке БД. */
    public List<MapRow> getAll() {
        List<MapRow> result = db.withConnection(conn -> {
            List<MapRow> list = new ArrayList<>();
            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery(
                        "SELECT id, map_name, file_name, url_download, file_size, crc64, added_at FROM maps ORDER BY id");
                while (rs.next()) {
                    MapRow r = new MapRow();
                    r.id = rs.getInt("id");
                    r.mapName = rs.getString("map_name");
                    r.fileName = rs.getString("file_name");
                    r.urlDownload = rs.getString("url_download");
                    r.fileSize = rs.getLong("file_size");
                    r.crc64 = rs.getLong("crc64");
                    if (rs.wasNull()) r.crc64 = 0L;   // внешняя ссылка без файла — хеша нет
                    java.sql.Timestamp t = rs.getTimestamp("added_at");
                    r.addedAt = t == null ? "" : new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(t);
                    list.add(r);
                }
            } finally {
                st.close();
            }
            return list;
        });
        return result == null ? new ArrayList<>() : result;
    }

    /**
     * Добавление/обновление карты (по уникальному имени).
     *
     * @param crc64 CRC64 хеш .slop-файла (0 — для внешних ссылок без локального файла)
     * @return id карты или -1 при ошибке
     */
    public int upsert(String mapName, String fileName, String urlDownload, long fileSize, long crc64) {
        Integer id = db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO maps (map_name, file_name, url_download, file_size, crc64) VALUES (?,?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id), file_name=VALUES(file_name), " +
                    "url_download=VALUES(url_download), file_size=VALUES(file_size), crc64=VALUES(crc64)",
                    Statement.RETURN_GENERATED_KEYS);
            try {
                ps.setString(1, mapName);
                ps.setString(2, fileName);
                ps.setString(3, urlDownload);
                ps.setLong(4, fileSize);
                ps.setLong(5, crc64);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) return keys.getInt(1);
                return -1;
            } finally {
                ps.close();
            }
        });
        return id == null ? -1 : id;
    }

    /** Удаление карты по id. Возвращает true при успехе. */
    public boolean delete(int id) {
        Integer updated = db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM maps WHERE id=?");
            try {
                ps.setInt(1, id);
                return ps.executeUpdate();
            } finally {
                ps.close();
            }
        });
        return updated != null && updated > 0;
    }

    /** Количество карт в базе. */
    public int count() {
        Integer cnt = db.withConnection(conn -> {
            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM maps");
                return rs.next() ? rs.getInt(1) : 0;
            } finally {
                st.close();
            }
        });
        return cnt == null ? 0 : cnt;
    }

    /** Проверка: пуста ли таблица карт (для однократного импорта из maps_info.json). */
    public boolean isEmpty() {
        return count() == 0;
    }
}
