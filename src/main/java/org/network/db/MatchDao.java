package org.network.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Работа с таблицами матчей: журнал матчей и статистика игроков в каждом матче.
 * Статусы матча: IN_PROGRESS, FINISHED (обычное окончание по таймеру),
 * STOPPED (остановлен администратором), ABORTED (прерван — все игроки вышли).
 */
public class MatchDao {

    private final DatabaseManager db;

    public MatchDao(DatabaseManager db) {
        this.db = db;
    }

    /** Строка таблицы matches. */
    public static class MatchRow {
        public int id;
        public String mapName;
        public String startedAt;
        public String endedAt;
        public String status;
        public int playersCount;
        public String winnerName;
        public int winnerKills;
    }

    /** Статистика одного игрока в матче. */
    public static class StatRow {
        public String playerName;
        public int kills;
        public int deaths;
    }

    /** Создать запись нового матча. Возвращает id матча или -1. */
    public int create(String mapName) {
        Integer id = db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO matches (map_name, status) VALUES (?, 'IN_PROGRESS')",
                    Statement.RETURN_GENERATED_KEYS);
            try {
                ps.setString(1, mapName);
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

    /** Закрыть матч: статус, победитель, число игроков, время окончания. */
    public void finish(int matchId, String status, String winnerName, int winnerKills, int playersCount) {
        if (matchId <= 0) return;
        db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE matches SET ended_at = CURRENT_TIMESTAMP, status = ?, " +
                    "winner_name = ?, winner_kills = ?, players_count = ? WHERE id = ?");
            try {
                ps.setString(1, status);
                ps.setString(2, winnerName);
                ps.setInt(3, winnerKills);
                ps.setInt(4, playersCount);
                ps.setInt(5, matchId);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
            return true;
        });
    }

    /** Сохранить результат игрока в матче. */
    public void addStat(int matchId, int playerId, String playerName, int kills, int deaths) {
        if (matchId <= 0 || playerId <= 0) return;
        db.withConnection(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO match_stats (match_id, player_id, player_name, kills, deaths) VALUES (?,?,?,?,?)");
            try {
                ps.setInt(1, matchId);
                ps.setInt(2, playerId);
                ps.setString(3, playerName);
                ps.setInt(4, kills);
                ps.setInt(5, deaths);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
            return true;
        });
    }

    /** Последние матчи (для вкладки "Матчи"). */
    public List<MatchRow> getRecent(int limit) {
        List<MatchRow> result = db.withConnection(conn -> {
            List<MatchRow> list = new ArrayList<>();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, map_name, started_at, ended_at, status, players_count, winner_name, winner_kills " +
                    "FROM matches ORDER BY id DESC LIMIT ?");
            try {
                ps.setInt(1, limit);
                ResultSet rs = ps.executeQuery();
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd.MM HH:mm:ss");
                while (rs.next()) {
                    MatchRow r = new MatchRow();
                    r.id = rs.getInt("id");
                    r.mapName = rs.getString("map_name");
                    java.sql.Timestamp s = rs.getTimestamp("started_at");
                    java.sql.Timestamp e = rs.getTimestamp("ended_at");
                    r.startedAt = s == null ? "" : fmt.format(s);
                    r.endedAt = e == null ? "" : fmt.format(e);
                    r.status = rs.getString("status");
                    r.playersCount = rs.getInt("players_count");
                    r.winnerName = rs.getString("winner_name");
                    r.winnerKills = rs.getInt("winner_kills");
                    list.add(r);
                }
            } finally {
                ps.close();
            }
            return list;
        });
        return result == null ? new ArrayList<>() : result;
    }

    /** Статистика игроков конкретного матча (для модалки "Детали матча"). */
    public List<StatRow> getMatchStats(int matchId) {
        List<StatRow> result = db.withConnection(conn -> {
            List<StatRow> list = new ArrayList<>();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT player_name, kills, deaths FROM match_stats WHERE match_id = ? ORDER BY kills DESC");
            try {
                ps.setInt(1, matchId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    StatRow r = new StatRow();
                    r.playerName = rs.getString("player_name");
                    r.kills = rs.getInt("kills");
                    r.deaths = rs.getInt("deaths");
                    list.add(r);
                }
            } finally {
                ps.close();
            }
            return list;
        });
        return result == null ? new ArrayList<>() : result;
    }

    /** Количество матчей в базе. */
    public int count() {
        Integer cnt = db.withConnection(conn -> {
            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM matches");
                return rs.next() ? rs.getInt(1) : 0;
            } finally {
                st.close();
            }
        });
        return cnt == null ? 0 : cnt;
    }
}
