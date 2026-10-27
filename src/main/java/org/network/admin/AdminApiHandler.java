package org.network.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.network.GameWorld;
import org.network.PlayerSession;
import org.network.db.MapDao;
import org.network.db.MatchDao;
import org.network.db.PlayerDao;
import org.network.db.crc64hash;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * REST API админ-панели. Все ответы — JSON (UTF-8).
 * Авторизация: заголовок X-Auth-Token (кроме /api/login).
 */
public class AdminApiHandler implements HttpHandler {

    /** Максимальный размер загружаемой карты — 500 МБ. */
    private static final long MAX_UPLOAD_SIZE = 500L * 1024 * 1024;

    private final GameWorld gameWorld;
    private final AdminConfig config;
    private final AdminAuth auth;

    public AdminApiHandler(GameWorld gameWorld, AdminConfig config, AdminAuth auth) {
        this.gameWorld = gameWorld;
        this.config = config;
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // Логин — единственный эндпоинт без токена
            if ("/api/login".equals(path) && "POST".equals(method)) {
                handleLogin(exchange);
                return;
            }

            // Проверка токена
            String token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
            if (!auth.isValid(token)) {
                sendJson(exchange, 401, err("Требуется авторизация"));
                return;
            }

            // ---------- Статус и игроки ----------
            if ("/api/status".equals(path) && "GET".equals(method)) {
                handleStatus(exchange);
            } else if ("/api/players/online".equals(path) && "GET".equals(method)) {
                handleOnlinePlayers(exchange);
            } else if ("/api/players".equals(path) && "GET".equals(method)) {
                handlePlayersRating(exchange);

            // ---------- НОВОЕ: Модерация игроков ----------
            } else if ("/api/player/kill".equals(path) && "POST".equals(method)) {
                handlePlayerKill(exchange);
            } else if ("/api/player/kick".equals(path) && "POST".equals(method)) {
                handlePlayerKick(exchange);
            } else if ("/api/player/ban".equals(path) && "POST".equals(method)) {
                handlePlayerBan(exchange);
            } else if ("/api/player/attacks".equals(path) && "POST".equals(method)) {
                handlePlayerAttacks(exchange);   // НОВОЕ: вкл/выкл обработку атак игрока
            } else if ("/api/bans".equals(path) && "GET".equals(method)) {
                handleBansList(exchange);
            } else if ("/api/bans".equals(path) && "DELETE".equals(method)) {
                handleBanDelete(exchange);

            // ---------- Управление матчем ----------
            } else if ("/api/match/stop".equals(path) && "POST".equals(method)) {
                boolean ok = gameWorld.stopMatchByAdmin();
                sendJson(exchange, ok ? 200 : 400,
                        ok ? okMsg("Матч остановлен, статистика сохранена. Сервер ждёт ручного запуска цикла матчей")
                           : err("Сейчас нет активного матча и запущенного цикла"));
            } else if ("/api/match/start".equals(path) && "POST".equals(method)) {
                boolean ok = gameWorld.forceStartMatch();
                sendJson(exchange, ok ? 200 : 400,
                        ok ? okMsg("Матч запущен принудительно")
                           : err("Матч уже идёт, на сервере нет игроков или нет карт"));
            } else if ("/api/match/run".equals(path) && "POST".equals(method)) {
                // НОВОЕ: запуск матча из состояния «Не запущен» (кнопка «Запустить матч»)
                if (gameWorld.isCycleRunning()) {
                    sendJson(exchange, 400, err("Матч уже запущен"));
                } else if (gameWorld.getMapsCount() == 0) {
                    sendJson(exchange, 400, err("Нет ни одной карты — сначала добавьте карту во вкладке «Карты»"));
                } else {
                    boolean ok = gameWorld.startCycleByAdmin();
                    sendJson(exchange, ok ? 200 : 400,
                            ok ? okMsg("Цикл матча запущен, игрокам разослана карта")
                               : err("Не удалось запустить матч"));
                }
            } else if ("/api/match/restart".equals(path) && "POST".equals(method)) {
                boolean ok = gameWorld.restartCycle();
                sendJson(exchange, ok ? 200 : 400,
                        ok ? okMsg("Цикл перезапущен, всем разослана новая карта")
                           : err("Нет карт для перезапуска цикла — сначала добавьте карту"));
            } else if ("/api/match/nextmap".equals(path) && "POST".equals(method)) {
                handleSetNextMap(exchange, false);
            } else if ("/api/match/apply-map".equals(path) && "POST".equals(method)) {
                handleSetNextMap(exchange, true);

            // ---------- Карты ----------
            } else if ("/api/maps".equals(path) && "GET".equals(method)) {
                handleMapsList(exchange);
            } else if ("/api/maps/add".equals(path) && "POST".equals(method)) {
                handleMapAdd(exchange);
            } else if ("/api/maps/upload".equals(path) && "POST".equals(method)) {
                handleMapUpload(exchange);
            } else if ("/api/maps".equals(path) && "DELETE".equals(method)) {
                handleMapDelete(exchange);

            // ---------- Матчи ----------
            } else if ("/api/matches".equals(path) && "GET".equals(method)) {
                handleMatchesList(exchange);
            } else if (path.startsWith("/api/matches/") && "GET".equals(method)) {
                handleMatchDetails(exchange, path.substring("/api/matches/".length()));

            // ---------- Настройки сервера ----------
            } else if ("/api/settings".equals(path) && "GET".equals(method)) {
                handleGetSettings(exchange);
            } else if ("/api/settings".equals(path) && "POST".equals(method)) {
                handleSaveSettings(exchange);

            } else {
                sendJson(exchange, 404, err("Неизвестный эндпоинт"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendJson(exchange, 500, err("Внутренняя ошибка: " + e.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    // =====================================================
    //  Авторизация
    // =====================================================

    private void handleLogin(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        if (body == null) {
            sendJson(exchange, 400, err("Некорректный JSON"));
            return;
        }
        String password = jsonStr(body, "password");
        String token = auth.login(password);
        if (token == null) {
            sendJson(exchange, 401, err("Неверный пароль"));
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("token", token);
        sendJson(exchange, 200, resp);
    }

    // =====================================================
    //  Статус сервера
    // =====================================================

    private void handleStatus(HttpExchange exchange) throws IOException {
        GameWorld w = gameWorld;
        JsonObject s = new JsonObject();
        s.addProperty("ok", true);
        s.addProperty("matchInProgress", w.isMatchInProgress());
        s.addProperty("cycleRunning", w.isCycleRunning());   // НОВОЕ: запущен ли матч (кнопкой)
        // НОВОЕ: непрерывный цикл — идёт пауза между матчами (обратный отсчёт в timeLeft)
        s.addProperty("waitingNextMatch", w.isWaitingNextMatch());
        s.addProperty("cyclePauseSec", w.getCyclePauseSec());
        s.addProperty("timeLeft", w.getTimeLeft());
        s.addProperty("matchDuration", w.getMatchDuration());
        s.addProperty("currentMap", w.getCurMap() == null ? "—" : w.getCurMap().getMapName());
        s.addProperty("playersOnline", w.getPlayers().size());
        s.addProperty("readyCount", w.getReadyCount());
        s.addProperty("maxPlayers", w.getMaxPlayers());
        s.addProperty("maxFromStart", w.getMaxPlayersFromStart());
        s.addProperty("uptimeSeconds", w.getUptimeSeconds());
        s.addProperty("gamePort", w.getPort());
        s.addProperty("adminPort", config.getPort());
        s.addProperty("bansCount", w.getBanCount());   // НОВОЕ: активных банов

        if (w.getDb() != null && w.getDb().isAvailable()) {
            s.addProperty("dbAvailable", true);
            s.addProperty("totalPlayers", w.getDb().players().count());
            s.addProperty("totalMatches", w.getDb().matches().count());
            s.addProperty("mapsCount", w.getDb().maps().count());
        } else {
            s.addProperty("dbAvailable", false);
            s.addProperty("totalPlayers", 0);
            s.addProperty("totalMatches", 0);
            s.addProperty("mapsCount", 0);
        }
        // НОВОЕ: карт в памяти сервера (в fallback-режиме без БД может отличаться от mapsCount)
        s.addProperty("mapsLoaded", w.getMapsCount());

        // Следующая карта
        JsonObject next = new JsonObject();
        Integer nextId = w.getNextMapId();
        if (nextId == null) {
            next.addProperty("mode", "random");
            next.addProperty("name", "Случайная (не текущая)");
        } else {
            next.addProperty("mode", "fixed");
            next.addProperty("id", nextId);
            next.addProperty("name", w.getMapNameById(nextId) == null ? "Карта удалена" : w.getMapNameById(nextId));
        }
        s.add("nextMap", next);

        sendJson(exchange, 200, s);
    }

    // =====================================================
    //  Игроки
    // =====================================================

    private void handleOnlinePlayers(HttpExchange exchange) throws IOException {
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        JsonArray arr = new JsonArray();
        for (PlayerSession p : gameWorld.getPlayers().values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id);
            o.addProperty("name", p.name);
            o.addProperty("ip", p.ip == null ? "" : p.ip);   // НОВОЕ: IP для модерации
            o.addProperty("model", p.model);
            o.addProperty("health", p.health);
            o.addProperty("kills", p.killcount);
            o.addProperty("deaths", p.deadcount);
            o.addProperty("isDead", p.isDead);
            o.addProperty("isReady", p.isReady);
            o.addProperty("attacksDisabled", p.attacksDisabled);   // НОВОЕ: атаки отключены админом?
            arr.add(o);
        }
        resp.add("players", arr);
        sendJson(exchange, 200, resp);
    }

    private void handlePlayersRating(HttpExchange exchange) throws IOException {
        if (gameWorld.getDb() == null) {
            sendJson(exchange, 503, err("База данных недоступна"));
            return;
        }
        List<PlayerDao.PlayerRow> rows = gameWorld.getDb().players().getTop(200);
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        JsonArray arr = new JsonArray();
        for (PlayerDao.PlayerRow r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id);
            o.addProperty("name", r.name);
            o.addProperty("totalMatches", r.totalMatches);
            o.addProperty("totalKills", r.totalKills);
            o.addProperty("totalDeaths", r.totalDeaths);
            double kd = r.totalDeaths == 0 ? r.totalKills : (double) r.totalKills / r.totalDeaths;
            o.addProperty("kd", Math.round(kd * 100.0) / 100.0);
            o.addProperty("firstSeen", r.firstSeen);
            o.addProperty("lastSeen", r.lastSeen);
            arr.add(o);
        }
        resp.add("players", arr);
        sendJson(exchange, 200, resp);
    }

    // =====================================================
    //  НОВОЕ: Модерация игроков (килл / кик / бан по IP)
    // =====================================================

    /** POST /api/player/kill {name} — убить игрока по нику. */
    private void handlePlayerKill(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        String name = jsonStr(body, "name").trim();
        if (name.isEmpty()) {
            sendJson(exchange, 400, err("Укажите ник игрока"));
            return;
        }
        PlayerSession p = gameWorld.findPlayerByName(name);
        if (p == null) {
            sendJson(exchange, 404, err("Игрок \"" + name + "\" не найден на сервере"));
            return;
        }
        if (!gameWorld.adminKillPlayer(p.name)) {
            sendJson(exchange, 400, err("Игрок " + p.name + " уже мёртв"));
            return;
        }
        sendJson(exchange, 200, okMsg("Игрок " + p.name + " убит (респавн через 10 сек)"));
    }

    /** POST /api/player/kick {name} — отключить игрока от сервера. */
    private void handlePlayerKick(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        String name = jsonStr(body, "name").trim();
        if (name.isEmpty()) {
            sendJson(exchange, 400, err("Укажите ник игрока"));
            return;
        }
        PlayerSession p = gameWorld.findPlayerByName(name);
        if (p == null) {
            sendJson(exchange, 404, err("Игрок \"" + name + "\" не найден на сервере"));
            return;
        }
        gameWorld.kickPlayer(p.name);
        sendJson(exchange, 200, okMsg("Игрок " + p.name + " отключён от сервера"));
    }

    /**
     * POST /api/player/ban — забанить по IP.
     * Вариант 1: {"name": "Ник", "reason": "..."} — IP берётся у игрока онлайн,
     *            игрок после бана отключается от сервера.
     * Вариант 2: {"ip": "1.2.3.4", "reason": "..."} — бан по IP напрямую.
     */
    private void handlePlayerBan(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        String name = jsonStr(body, "name").trim();
        String ip = jsonStr(body, "ip").trim();
        String reason = jsonStr(body, "reason").trim();

        if (name.isEmpty() && ip.isEmpty()) {
            sendJson(exchange, 400, err("Укажите ник игрока или IP-адрес"));
            return;
        }

        PlayerSession online = null;
        if (!name.isEmpty()) {
            online = gameWorld.findPlayerByName(name);
            if (online == null) {
                sendJson(exchange, 404, err("Игрок \"" + name + "\" не найден на сервере. Банить можно игроков онлайн или IP напрямую"));
                return;
            }
            ip = online.ip;
        }

        if (ip.isEmpty() || ip.length() > 45 || !ip.matches("[0-9a-fA-F.:]+")) {
            sendJson(exchange, 400, err("Некорректный IP-адрес: " + ip));
            return;
        }

        gameWorld.banIp(ip, online != null ? online.name : "", reason);

        // Забаненный игрок онлайн — отключаем сразу (вход по этому IP теперь закрыт)
        if (online != null) {
            gameWorld.kickPlayer(online.name);
            sendJson(exchange, 200, okMsg("Игрок " + online.name + " забанен по IP " + ip
                    + (reason.isEmpty() ? "" : " (причина: " + reason + ")") + " и отключён от сервера"));
        } else {
            sendJson(exchange, 200, okMsg("IP " + ip + " забанен"
                    + (reason.isEmpty() ? "" : " (причина: " + reason + ")")));
        }
    }

    /**
     * НОВОЕ: POST /api/player/attacks {name, disabled} — включить/выключить обработку атак игрока.
     * disabled = true  — сервер игнорирует пакеты атаки игрока (урон другим не наносится);
     * disabled = false — вернуть атаки обратно.
     * Если поле "disabled" не передано — состояние просто переключается.
     */
    private void handlePlayerAttacks(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        String name = jsonStr(body, "name").trim();
        if (name.isEmpty()) {
            sendJson(exchange, 400, err("Укажите ник игрока"));
            return;
        }
        PlayerSession p = gameWorld.findPlayerByName(name);
        if (p == null) {
            sendJson(exchange, 404, err("Игрок \"" + name + "\" не найден на сервере"));
            return;
        }

        boolean disabled;
        if (body.has("disabled") && !body.get("disabled").isJsonNull()) {
            try {
                disabled = body.get("disabled").getAsBoolean();
            } catch (Exception e) {
                sendJson(exchange, 400, err("Поле \"disabled\" должно быть true или false"));
                return;
            }
        } else {
            disabled = !p.attacksDisabled;   // поле не передано — переключаем состояние
        }

        gameWorld.setPlayerAttacksDisabled(p.name, disabled);
        sendJson(exchange, 200, okMsg(disabled
                ? "Игроку " + p.name + " отключены атаки — его удары больше не наносят урон другим игрокам"
                : "Игроку " + p.name + " включены атаки обратно"));
    }

    /** GET /api/bans — список активных банов. */
    private void handleBansList(HttpExchange exchange) throws IOException {
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        JsonArray arr = new JsonArray();
        for (GameWorld.BanEntry e : gameWorld.getBanList()) {
            JsonObject o = new JsonObject();
            o.addProperty("ip", e.ip);
            o.addProperty("playerName", e.playerName == null ? "" : e.playerName);
            o.addProperty("reason", e.reason == null ? "" : e.reason);
            o.addProperty("bannedAt", e.bannedAt == null ? "" : e.bannedAt);
            arr.add(o);
        }
        resp.add("bans", arr);
        sendJson(exchange, 200, resp);
    }

    /** DELETE /api/bans?ip=... — снять бан. */
    private void handleBanDelete(HttpExchange exchange) throws IOException {
        String ip = parseParam(exchange.getRequestURI().getQuery(), "ip");
        if (ip.isEmpty()) {
            sendJson(exchange, 400, err("Не указан IP (?ip=...)"));
            return;
        }
        boolean ok = gameWorld.unbanIp(ip);
        sendJson(exchange, ok ? 200 : 404,
                ok ? okMsg("Бан по IP " + ip + " снят")
                   : err("Бан по IP " + ip + " не найден"));
    }

    /** НОВОЕ: значение параметра из query-строки (URL-decoded). */
    private String parseParam(String query, String key) {
        if (query == null) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    // =====================================================
    //  Карты
    // =====================================================

    private void handleMapsList(HttpExchange exchange) throws IOException {
        if (gameWorld.getDb() == null) {
            sendJson(exchange, 503, err("База данных недоступна"));
            return;
        }
        List<MapDao.MapRow> rows = gameWorld.getDb().maps().getAll();
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        JsonArray arr = new JsonArray();
        for (MapDao.MapRow r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id);
            o.addProperty("mapName", r.mapName);
            o.addProperty("fileName", r.fileName == null ? "" : r.fileName);
            o.addProperty("urlDownload", r.urlDownload);
            o.addProperty("fileSize", r.fileSize);
            // НОВОЕ: CRC64 хеш. Как БЕЗЗНАКОВАЯ строка — JS теряет точность на long > 2^53
            o.addProperty("crc64", Long.toUnsignedString(r.crc64));
            o.addProperty("crc64Hex", String.format("%016x", r.crc64));
            o.addProperty("addedAt", r.addedAt);
            arr.add(o);
        }
        resp.add("maps", arr);
        sendJson(exchange, 200, resp);
    }

    /** Добавление карты вручную (имя + внешняя ссылка). */
    private void handleMapAdd(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        if (body == null) {
            sendJson(exchange, 400, err("Некорректный JSON"));
            return;
        }
        String mapName = jsonStr(body, "mapName");
        String url = jsonStr(body, "urlDownload");
        if (mapName.isEmpty() || url.isEmpty()) {
            sendJson(exchange, 400, err("Укажите название карты и ссылку"));
            return;
        }
        int id = gameWorld.addMap(mapName, null, url, 0);
        if (id <= 0) {
            sendJson(exchange, 500, err("Не удалось добавить карту (ошибка БД?)"));
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("id", id);
        resp.addProperty("message", "Карта \"" + mapName + "\" добавлена");
        sendJson(exchange, 200, resp);
    }

    /**
     * Загрузка .slop карты: тело запроса — сам файл,
     * имя файла — в параметре ?name= (с URL-кодированием).
     */
    private void handleMapUpload(HttpExchange exchange) throws IOException {
        String rawName = exchange.getRequestURI().getQuery();
        String fileName = null;
        if (rawName != null && rawName.startsWith("name=")) {
            fileName = URLDecoder.decode(rawName.substring(5), "UTF-8");
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            sendJson(exchange, 400, err("Не указано имя файла (?name=...)"));
            return;
        }

        // Санитизация имени: только буквы/цифры/._-() и пробел, убираем пути
        fileName = new File(fileName).getName().replaceAll("[^A-Za-z0-9._() -]", "_").trim();
        if (fileName.isEmpty()) {
            fileName = "map_" + System.currentTimeMillis() + ".slop";
        }
        if (!fileName.toLowerCase().endsWith(".slop")) {
            fileName = fileName + ".slop";
        }

        // Читаем тело запроса (файл)
        File mapsDir = new File(config.getMapsDir());
        if (!mapsDir.exists() && !mapsDir.mkdirs()) {
            sendJson(exchange, 500, err("Не удалось создать папку " + config.getMapsDir()));
            return;
        }
        File target = new File(mapsDir, fileName);

        InputStream in = exchange.getRequestBody();
        OutputStream out = new FileOutputStream(target);
        long total = 0;
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_UPLOAD_SIZE) {
                    sendJson(exchange, 413, err("Файл слишком большой (максимум 500 МБ)"));
                    return;
                }
                out.write(buf, 0, n);
            }
        } finally {
            out.close();
        }

        String mapName = fileName.substring(0, fileName.length() - ".slop".length());
        String url = config.getPublicUrl() + "/maps/" + fileName.replace(" ", "%20");

        // НОВОЕ: считаем CRC64 хеш .slop-файла сразу после сохранения на диск.
        // Именно это значение ляжет в БД и уйдёт клиенту через MapInfoPacket.
        long mapCrc64 = 0;
        try {
            mapCrc64 = crc64hash.crc64File(target.getAbsolutePath());
            System.out.println("[АДМИН] CRC64 карты " + fileName + ": " + String.format("%016x", mapCrc64));
        } catch (Exception e) {
            // Файл сохранён, но хеш не посчитался — карта всё равно добавится (crc64 = 0)
            System.err.println("[АДМИН] Ошибка подсчёта CRC64 для " + fileName + ": " + e.getMessage());
        }

        int id = gameWorld.addMap(mapName, fileName, url, total, mapCrc64);
        if (id <= 0) {
            sendJson(exchange, 500, err("Файл сохранён, но ошибка записи в БД"));
            return;
        }

        System.out.println("[АДМИН] Загружена карта: " + fileName + " (" + (total / 1024) + " КБ)");
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("id", id);
        resp.addProperty("mapName", mapName);
        resp.addProperty("urlDownload", url);
        resp.addProperty("fileSize", total);
        resp.addProperty("crc64", Long.toUnsignedString(mapCrc64));
        resp.addProperty("crc64Hex", String.format("%016x", mapCrc64));
        resp.addProperty("message", "Карта \"" + mapName + "\" загружена (" + (total / 1024) + " КБ)");
        sendJson(exchange, 200, resp);
    }

    private void handleMapDelete(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getQuery();
        int id = parseIdParam(q);
        if (id <= 0) {
            sendJson(exchange, 400, err("Не указан id карты (?id=...)"));
            return;
        }
        if (gameWorld.getDb() == null) {
            sendJson(exchange, 503, err("База данных недоступна"));
            return;
        }
        // НОВОЕ: удалять можно ЛЮБУЮ карту, включая последнюю — сервер останется без карт,
        // это корректное состояние («Не запущен»); новую карту можно добавить в любой момент.
        // Если удалённая карта была текущей — цикл/матч корректно сворачивается (см. GameWorld.removeMap).
        String mapName = gameWorld.getMapNameById(id);
        if (mapName == null) {
            sendJson(exchange, 404, err("Карта не найдена"));
            return;
        }
        boolean ok = gameWorld.removeMap(id);
        sendJson(exchange, ok ? 200 : 404,
                ok ? okMsg("Карта \"" + mapName + "\" удалена") : err("Карта не найдена"));
    }

    // =====================================================
    //  Матчи
    // =====================================================

    /**
     * @param applyNow true — применить немедленно (перезапуск цикла),
     *                 false — назначить только на следующий матч.
     */
    private void handleSetNextMap(HttpExchange exchange, boolean applyNow) throws IOException {
        JsonObject body = readJsonBody(exchange);
        Integer mapId = null;
        if (body != null && body.has("mapId") && !body.get("mapId").isJsonNull()) {
            JsonElement el = body.get("mapId");
            mapId = el.getAsInt();
        }
        // mapId == null -> вернуть случайный выбор (не текущая карта)
        if (mapId != null && gameWorld.getMapNameById(mapId) == null) {
            sendJson(exchange, 404, err("Карта не найдена"));
            return;
        }

        String mapName = mapId == null ? "случайная (не текущая)" : gameWorld.getMapNameById(mapId);
        if (applyNow) {
            boolean started = gameWorld.applyMapNow(mapId);
            if (started) {
                sendJson(exchange, 200, okMsg("Карта \"" + mapName + "\" применена: цикл перезапущен"));
            } else {
                sendJson(exchange, 400, err("Нет карт для применения"));
            }
        } else {
            gameWorld.setNextMap(mapId);
            sendJson(exchange, 200, okMsg("Следующий матч: \"" + mapName + "\""));
        }
    }

    private void handleMatchesList(HttpExchange exchange) throws IOException {
        if (gameWorld.getDb() == null) {
            sendJson(exchange, 503, err("База данных недоступна"));
            return;
        }
        List<MatchDao.MatchRow> rows = gameWorld.getDb().matches().getRecent(50);
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        JsonArray arr = new JsonArray();
        for (MatchDao.MatchRow r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id);
            o.addProperty("mapName", r.mapName);
            o.addProperty("startedAt", r.startedAt);
            o.addProperty("endedAt", r.endedAt);
            o.addProperty("status", r.status);
            o.addProperty("playersCount", r.playersCount);
            o.addProperty("winnerName", r.winnerName == null ? "" : r.winnerName);
            o.addProperty("winnerKills", r.winnerKills);
            arr.add(o);
        }
        resp.add("matches", arr);
        sendJson(exchange, 200, resp);
    }

    private void handleMatchDetails(HttpExchange exchange, String idStr) throws IOException {
        int matchId;
        try {
            matchId = Integer.parseInt(idStr.trim());
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, err("Некорректный id матча"));
            return;
        }
        if (gameWorld.getDb() == null) {
            sendJson(exchange, 503, err("База данных недоступна"));
            return;
        }
        List<MatchDao.StatRow> stats = gameWorld.getDb().matches().getMatchStats(matchId);
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("matchId", matchId);
        JsonArray arr = new JsonArray();
        for (MatchDao.StatRow r : stats) {
            JsonObject o = new JsonObject();
            o.addProperty("playerName", r.playerName);
            o.addProperty("kills", r.kills);
            o.addProperty("deaths", r.deaths);
            arr.add(o);
        }
        resp.add("stats", arr);
        sendJson(exchange, 200, resp);
    }

    // =====================================================
    //  Настройки сервера
    // =====================================================

    /** Текущие игровые настройки + допустимые диапазоны (для UI). */
    private void handleGetSettings(HttpExchange exchange) throws IOException {
        GameWorld w = gameWorld;
        JsonObject s = new JsonObject();
        s.addProperty("ok", true);
        s.addProperty("minPlayersStart", w.getMinPlayersToStart());
        s.addProperty("minPlayersStartMin", GameWorld.MIN_START_MIN);
        s.addProperty("minPlayersStartMax", GameWorld.MIN_START_MAX);
        s.addProperty("maxPlayers", w.getMaxPlayersLimit());
        s.addProperty("maxPlayersMin", GameWorld.MAX_PLAYERS_MIN);
        s.addProperty("maxPlayersMax", GameWorld.MAX_PLAYERS_MAX);
        s.addProperty("matchDuration", w.getMatchDurationSec());
        s.addProperty("matchDurationMin", GameWorld.DURATION_MIN);
        s.addProperty("matchDurationMax", GameWorld.DURATION_MAX);
        // НОВОЕ: хеш соединения в виде HEX-строки ("0" — отключён)
        s.addProperty("connHash", w.getConnetHashHex());
        s.addProperty("playersOnline", w.getPlayers().size());
        s.addProperty("matchInProgress", w.isMatchInProgress());
        sendJson(exchange, 200, s);
    }

    /**
     * Сохранение игровых настроек.
     * Тело: { "minPlayersStart": 1..8, "maxPlayers": 2..64, "matchDuration": 30..7200 (сек),
     *         "connHash": HEX-строка (опционально; "0" или пустая — проверка отключена) }
     * Значения применяются немедленно и сохраняются в admin_config.properties.
     */
    private void handleSaveSettings(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        if (body == null) {
            sendJson(exchange, 400, err("Некорректный JSON"));
            return;
        }

        int minStart = jsonInt(body, "minPlayersStart", -1);
        int maxP     = jsonInt(body, "maxPlayers", -1);
        int duration = jsonInt(body, "matchDuration", -1);

        if (minStart < 0 || maxP < 0 || duration < 0) {
            sendJson(exchange, 400, err("Не переданы все настройки (minPlayersStart, maxPlayers, matchDuration)"));
            return;
        }
        if (minStart < GameWorld.MIN_START_MIN || minStart > GameWorld.MIN_START_MAX) {
            sendJson(exchange, 400, err("Минимум игроков для старта должен быть от "
                    + GameWorld.MIN_START_MIN + " до " + GameWorld.MIN_START_MAX));
            return;
        }
        if (maxP < GameWorld.MAX_PLAYERS_MIN || maxP > GameWorld.MAX_PLAYERS_MAX) {
            sendJson(exchange, 400, err("Максимум игроков должен быть от "
                    + GameWorld.MAX_PLAYERS_MIN + " до " + GameWorld.MAX_PLAYERS_MAX));
            return;
        }
        if (duration < GameWorld.DURATION_MIN || duration > GameWorld.DURATION_MAX) {
            sendJson(exchange, 400, err("Время матча должно быть от " + GameWorld.DURATION_MIN
                    + " до " + GameWorld.DURATION_MAX + " секунд"));
            return;
        }
        if (maxP < minStart) {
            sendJson(exchange, 400, err("Максимум игроков (" + maxP
                    + ") не может быть меньше минимума для старта (" + minStart + ")"));
            return;
        }

        // НОВОЕ: хеш соединения — опциональное поле запроса. Если не передано — текущее значение не меняется.
        // В настройках хранится как HEX, здесь преобразуется в long ("0"/пустая строка — 0, проверка отключена).
        Long connHash = null;
        if (body.has("connHash") && !body.get("connHash").isJsonNull()) {
            try {
                connHash = GameWorld.parseConnectionHash(jsonStr(body, "connHash"));
            } catch (Exception e) {
                sendJson(exchange, 400, err("Хеш соединения: некорректный формат (ожидается HEX, 1–16 цифр 0-9/A-F, или 0 — отключить)"));
                return;
            }
        }

        boolean wasInProgress = gameWorld.isMatchInProgress();
        gameWorld.applyGameSettings(minStart, maxP, duration);
        if (connHash != null) {
            gameWorld.applyConnectionHash(connHash);
        }

        String msg = "Настройки сохранены: мин. для старта " + minStart
                + " · макс. игроков " + maxP
                + " · время матча " + (duration / 60) + " мин " + (duration % 60) + " сек";
        if (connHash != null) {
            msg += " · хеш соединения: " + (connHash == 0L
                    ? "отключён (0)"
                    : String.format("%016x", connHash));
        }
        if (wasInProgress) {
            msg += ". Таймер идущего матча перезапущен на новую длительность.";
        }
        sendJson(exchange, 200, okMsg(msg));
    }

    // =====================================================
    //  Вспомогательные методы
    // =====================================================

    private JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        String text = buf.toString("UTF-8").trim();
        if (text.isEmpty()) return new JsonObject();
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private String jsonStr(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsString();
        return "";
    }

    private int jsonInt(JsonObject o, String key, int def) {
        try {
            if (o.has(key) && !o.get(key).isJsonNull()) {
                return Integer.parseInt(o.get(key).getAsString().trim());
            }
        } catch (Exception ignored) { }
        return def;
    }

    private int parseIdParam(String query) {
        if (query == null) return -1;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "id".equals(pair.substring(0, eq))) {
                try {
                    return Integer.parseInt(URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } catch (Exception e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private JsonObject okMsg(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", message);
        return o;
    }

    private JsonObject err(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    private void sendJson(HttpExchange exchange, int code, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, body.length);
        OutputStream os = exchange.getResponseBody();
        os.write(body);
        os.close();
    }
}
