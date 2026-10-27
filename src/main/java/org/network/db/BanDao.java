package org.network.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * НОВОЕ: Работа с таблицей банов (ban по IP).
 * Таблица создаётся автоматически (см. DatabaseManager.createSchema).
 * Проверка при логине идёт по кэшу в GameWorld — БД нужна только для хранения списка.
 */
public class BanDao {

    private final DatabaseManager db;

    public BanDao(DatabaseManager db) {
        this.db = db;
    }

    /** Строка таблицы bans. */
    public static class BanRow {
        public int id;
        public String ip;
        public String playerName;  // ник, под которым был забанен (может быть пустым)
        public String reason;      // причина (может быть пустой)
        public String bannedAt;    // форматированная дата
    }

    /** Добавить/обновить бан по IP (уникальный IP). true при успехе. */
    public boolean upsert(String ip, String playerName, String reason) {
        Boolean ok = db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO bans (ip, player_name, reason) VALUES (?,?,?) " +
                    "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), reason=VALUES(reason)");
            try {
                ps.setString(1, ip);
                ps.setString(2, playerName == null ? "" : playerName);
                ps.setString(3, reason == null ? "" : reason);
                ps.executeUpdate();
                return true;
            } finally {
                ps.close();
            }
        });
        return ok != null && ok;
    }

    /** Снять бан по IP. true если запись удалена. */
    public boolean delete(String ip) {
        Integer updated = db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM bans WHERE ip=?");
            try {
                ps.setString(1, ip);
                return ps.executeUpdate();
            } finally {
                ps.close();
            }
        });
        return updated != null && updated > 0;
    }

    /** Весь список банов (новые сверху). */
    public List<BanRow> getAll() {
        List<BanRow> result = db.withConnection(conn -> {
            List<BanRow> list = new ArrayList<>();
            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery(
                        "SELECT id, ip, player_name, reason, banned_at FROM bans ORDER BY banned_at DESC, id DESC");
                while (rs.next()) {
                    BanRow r = new BanRow();
                    r.id = rs.getInt("id");
                    r.ip = rs.getString("ip");
                    r.playerName = rs.getString("player_name");
                    r.reason = rs.getString("reason");
                    java.sql.Timestamp t = rs.getTimestamp("banned_at");
                    r.bannedAt = t == null ? "" : new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(t);
                    list.add(r);
                }
            } finally {
                st.close();
            }
            return list;
        });
        return result == null ? new ArrayList<>() : result;
    }

    /** Количество банов. */
    public int count() {
        Integer cnt = db.withConnection(conn -> {
            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bans");
                return rs.next() ? rs.getInt(1) : 0;
            } finally {
                st.close();
            }
        });
        return cnt == null ? 0 : cnt;
    }
}
