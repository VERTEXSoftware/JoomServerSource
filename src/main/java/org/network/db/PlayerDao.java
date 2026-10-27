package org.network.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Работа с таблицей игроков: регистрация, накопительная статистика, рейтинг.
 */
public class PlayerDao {

    private final DatabaseManager db;

    public PlayerDao(DatabaseManager db) {
        this.db = db;
    }

    /** Строка таблицы players для рейтинга. */
    public static class PlayerRow {
        public int id;
        public String name;
        public int model;
        public int totalMatches;
        public int totalKills;
        public int totalDeaths;
        public String firstSeen;
        public String lastSeen;
    }

    /**
     * Регистрация игрока или обновление last_seen/model.
     * Возвращает id игрока в БД или -1 при ошибке.
     */
    public int upsert(String name, int model) {
        Integer id = db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (name, model) VALUES (?,?) " +
                    "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id), model=VALUES(model), last_seen=CURRENT_TIMESTAMP",
                    Statement.RETURN_GENERATED_KEYS);
            try {
                ps.setString(1, name);
                ps.setInt(2, model);
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

    /** Найти id игрока по имени (или -1). */
    public int findIdByName(String name) {
        Integer id = db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE name=?");
            try {
                ps.setString(1, name);
                ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getInt(1) : -1;
            } finally {
                ps.close();
            }
        });
        return id == null ? -1 : id;
    }

    /** Добавить результат матча в накопительную статистику игрока. */
    public void addResult(int playerId, int kills, int deaths) {
        if (playerId <= 0) return;
        db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET total_matches = total_matches + 1, " +
                    "total_kills = total_kills + ?, total_deaths = total_deaths + ? WHERE id = ?");
            try {
                ps.setInt(1, kills);
                ps.setInt(2, deaths);
                ps.setInt(3, playerId);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
            return true;
        });
    }

    /** Отметить активность игрока (обновить last_seen) без влияния на статистику. */
    public void touch(int playerId) {
        if (playerId <= 0) return;
        db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET last_seen = CURRENT_TIMESTAMP WHERE id = ?");
            try {
                ps.setInt(1, playerId);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
            return true;
        });
    }

    /** Рейтинг игроков (сортировка по убийствам). */
    public List<PlayerRow> getTop(int limit) {
        List<PlayerRow> result = db.withConnection(conn -> {
            List<PlayerRow> list = new ArrayList<>();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, name, model, total_matches, total_kills, total_deaths, first_seen, last_seen " +
                    "FROM players ORDER BY total_kills DESC, total_deaths ASC LIMIT ?");
            try {
                ps.setInt(1, limit);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    PlayerRow r = new PlayerRow();
                    r.id = rs.getInt("id");
                    r.name = rs.getString("name");
                    r.model = rs.getInt("model");
                    r.totalMatches = rs.getInt("total_matches");
                    r.totalKills = rs.getInt("total_kills");
                    r.totalDeaths = rs.getInt("total_deaths");
                    java.sql.Timestamp fs = rs.getTimestamp("first_seen");
                    java.sql.Timestamp ls = rs.getTimestamp("last_seen");
                    java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm");
                    r.firstSeen = fs == null ? "" : fmt.format(fs);
                    r.lastSeen = ls == null ? "" : fmt.format(ls);
                    list.add(r);
                }
            } finally {
                ps.close();
            }
            return list;
        });
        return result == null ? new ArrayList<>() : result;
    }

    /** Количество игроков в базе. */
    public int count() {
        Integer cnt = db.withConnection(conn -> {
            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM players");
                return rs.next() ? rs.getInt(1) : 0;
            } finally {
                st.close();
            }
        });
        return cnt == null ? 0 : cnt;
    }
}
