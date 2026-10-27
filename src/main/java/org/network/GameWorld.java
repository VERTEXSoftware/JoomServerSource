package org.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.netty.channel.Channel;
import org.network.admin.AdminConfig;
import org.network.db.BanDao;
import org.network.db.DatabaseManager;
import org.network.db.MapDao;
import org.network.db.crc64hash;
import org.network.maps.MapInfo;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class GameWorld {
    // ДЕФОЛТЫ (значения для нового admin_config.properties; дальше — динамические поля,
    // меняются в админ-панели и сохраняются в конфиг)
    public static final int DEFAULT_MAX_PLAYERS = 16;
    public static final int DEFAULT_MIN_PLAYERS_START = 2;
    public static final int DEFAULT_MATCH_DURATION = 180; // 3 минуты в секундах

    // Допустимые диапазоны настроек (проверяются в админ-панели и API)
    public static final int MIN_START_MIN = 1, MIN_START_MAX = 8;      // мин. игроков для старта
    public static final int MAX_PLAYERS_MIN = 2, MAX_PLAYERS_MAX = 64; // максимум игроков
    public static final int DURATION_MIN = 30, DURATION_MAX = 7200;    // время матча, сек (30 сек .. 2 ч)

    // Порт игрового сервера по умолчанию (если в admin_config.properties нет ключа game.port).
    // Реальный порт задаётся в admin_config.properties (game.port) и читается в конструкторе
    // в поле gamePort; применяется при СТАРТЕ сервера (биндинг порта в GameServer).
    public static final int DEFAULT_GAME_PORT = 12789;
    // Допустимый диапазон портов для проверки значения из конфига
    public static final int GAME_PORT_MIN = 1, GAME_PORT_MAX = 65535;
    public static final int LOADING_TIMEOUT = 30; // 30 сек на загрузку карты
    // НОВОЕ: пауза между матчами в непрерывном цикле (статистика разослана — ждём — новая карта)
    public static final int CYCLE_PAUSE_AFTER_MATCH_SEC = 10;

    // ---- Динамические игровые настройки (volatile — читаются из разных потоков) ----
    private volatile int minPlayersToStart = DEFAULT_MIN_PLAYERS_START;
    private volatile int maxPlayers = DEFAULT_MAX_PLAYERS;
    private volatile int matchDurationSec = DEFAULT_MATCH_DURATION;
    // НОВОЕ: игровой порт из admin_config.properties (game.port); задаётся один раз при старте
    private volatile int gamePort = DEFAULT_GAME_PORT;

    private final AtomicInteger idGenerator = new AtomicInteger(1);
    private final ConcurrentHashMap<Channel, PlayerSession> players = new ConcurrentHashMap<>();
    private final long startTime = System.currentTimeMillis();
    // НОВОЕ: volatile — хеш обновляется из потока админ-API, а читается в потоках Netty при логине
    private volatile long connetHash=0;

    // ==========================================
    //          БАЗА ДАННЫХ (админ-панель)
    // ==========================================
    private DatabaseManager db;
    private AdminConfig adminCfg;   // для персиста игровых настроек в admin_config.properties
    private String mapsDir = "maps";

    // Карты из БД: id -> MapInfo (порядок сохраняется)
    private final LinkedHashMap<Integer, MapInfo> dbMaps = new LinkedHashMap<>();
    // Карта, назначенная администратором на следующий матч (null = случайная не текущая)
    private volatile Integer nextMapId = null;
    // id текущего матча в БД
    private volatile int currentMatchId = -1;

    // НОВОЕ: бан-лист (IP -> запись). Источник истины при наличии БД — таблица bans;
    // в памяти держим зеркало для быстрой проверки при логине (и работы без БД).
    private final LinkedHashMap<String, BanEntry> banList = new LinkedHashMap<>();

    private List<MapInfo> Maps_list = new ArrayList<>();
    private MapInfo Cur_map;

    // Таймер и состояние матча
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> matchTimerTask;
    // НОВОЕ: отложенный автостарт следующего матча цикла (пауза CYCLE_PAUSE_AFTER_MATCH_SEC после конца матча)
    private volatile ScheduledFuture<?> nextMatchTask;
    private volatile boolean matchInProgress = false;
    // НОВОЕ: цикл матча запущен кнопкой в админ-панели. При старте сервера — НЕ запущен.
    private volatile boolean cycleRunning = false;
    private volatile int timeLeft = DEFAULT_MATCH_DURATION;

    /**
     * Импорт карт из старого maps_info.json в БД — ОДИН раз, если таблица карт пуста.
     */
    private void seedMapsFromJson(String filePath) {
        try {
            String jsonContent = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            Gson gson = new GsonBuilder().setLenient().create();
            Type mapListType = new TypeToken<List<MapInfo>>() {}.getType();
            List<MapInfo> imported = gson.fromJson(jsonContent, mapListType);
            if (imported == null || imported.isEmpty()) return;

            int added = 0;
            for (MapInfo map : imported) {
                if (map.getMapName() == null || map.getUrlDownload() == null) continue;
                // НОВОЕ: хеш берём из старого maps_info.json (если был), иначе 0 — файла локально нет
                long crc = map.getHashcrc64() == null ? 0L : map.getHashcrc64();
                db.maps().upsert(map.getMapName(), null, map.getUrlDownload(), 0, crc);
                added++;
            }
            System.out.println("[БД] Импортировано карт из maps_info.json: " + added);
        } catch (Exception e) {
            // Файла может не быть — это нормально
            System.out.println("[БД] maps_info.json не найден или пусто (это не ошибка): " + e.getMessage());
        }
    }

    public long getConnetHash(){
        return connetHash;
    }
    /**
     * Возвращает уникальное в пределах текущей игровой сессии имя.
     * Если baseName свободно — возвращает его как есть.
     * Иначе подбирает baseName_1, baseName_2, ... пока не найдёт свободное.
     * Сравнение регистронезависимое (Ivan == ivan).
     */
    public synchronized String makeUniqueName(String baseName) {
        if (baseName == null || baseName.trim().isEmpty()) {
            baseName = "Player";
        }
        baseName = baseName.trim();

        // Собираем занятые имена из глобальной сессии
        Set<String> taken = new HashSet<>();
        for (PlayerSession p : players.values()) {
            if (p.name != null) taken.add(p.name.toLowerCase());
        }

        if (!taken.contains(baseName.toLowerCase())) {
            return baseName;
        }

        int suffix = 1;
        String candidate;
        do {
            candidate = baseName + "_" + suffix;
            suffix++;
        } while (taken.contains(candidate.toLowerCase()));

        return candidate;
    }

    /**
     * НОВОЕ: Перечитать список карт из БД.
     */
    public synchronized void reloadMapsFromDb() {
        dbMaps.clear();
        for (MapDao.MapRow r : db.maps().getAll()) {
            // НОВОЕ: третий аргумент — CRC64 хеш карты из БД (уходит клиенту через MapInfoPacket)
            dbMaps.put(r.id, new MapInfo(r.fileName, r.urlDownload, r.crc64));
            System.out.println("[БД] карта: " + r.fileName + " (crc64=" + String.format("%016x", r.crc64) + ")");
        }
        Maps_list = new ArrayList<>(dbMaps.values());
        System.out.println("[БД] Загружено карт из базы: " + Maps_list.size());
    }

    /**
     * НОВОЕ: Добавление карты (из админ-панели). Записывает в БД и перечитывает список.
     *
     * @param fileName имя .slop-файла на диске или null (для внешних ссылок)
     * @return id карты в БД или -1 при ошибке
     */
    public synchronized int addMap(String mapName, String fileName, String urlDownload, long fileSize) {
        return addMap(mapName, fileName, urlDownload, fileSize, 0L);
    }

    /**
     * НОВОЕ: Добавление карты с заранее посчитанным CRC64 хешем .slop-файла.
     * Если хеш не передан (0), но файл лежит локально — хеш считается автоматически.
     * Именно это значение потом уходит клиенту через MapInfoPacket.
     *
     * @param crc64 CRC64 хеш файла (0 — неизвестен/внешняя ссылка)
     * @return id карты в БД или -1 при ошибке
     */
    public synchronized int addMap(String mapName, String fileName, String urlDownload, long fileSize, long crc64) {
        if (db == null) return -1;

        // НОВОЕ: если хеш не передан, но файл есть локально — считаем CRC64 при добавлении
        if (crc64 == 0 && fileName != null) {
            File mapFile = new File(mapsDir, fileName);
            if (mapFile.isFile()) {
                try {
                    crc64 = crc64hash.crc64File(mapFile.getAbsolutePath());
                    System.out.println("[АДМИН] CRC64 карты " + fileName + ": " + String.format("%016x", crc64));
                } catch (Exception e) {
                    System.err.println("[АДМИН] Не удалось посчитать CRC64 для " + fileName + ": " + e.getMessage());
                }
            }
        }

        int id = db.maps().upsert(mapName, fileName, urlDownload, fileSize, crc64);
        if (id > 0) {
            reloadMapsFromDb();
            System.out.println("[АДМИН] Карта добавлена: " + mapName + " -> " + urlDownload);
        }
        return id;
    }

    /**
     * НОВОЕ: Удаление карты (из админ-панели). Удаляет запись из БД, .slop-файл и перечитывает список.
     * Можно удалить ЛЮБУЮ карту, включая последнюю и текущую:
     *  - удалена ТЕКУЩАЯ карта и идёт матч — матч корректно останавливается (статистика сохраняется, STOPPED);
     *  - удалена ТЕКУЩАЯ карта в лобби — цикл останавливается, сервер переходит в состояние «Не запущен».
     */
    public synchronized boolean removeMap(int mapId) {
        if (db == null) return false;
        MapInfo removed = dbMaps.get(mapId);
        MapDao.MapRow row = null;
        for (MapDao.MapRow r : db.maps().getAll()) {
            if (r.id == mapId) { row = r; break; }
        }
        if (row == null) return false;

        // НОВОЕ: удаляемая карта — та, на которой сейчас идёт цикл/матч?
        boolean wasCurrentMap = (removed != null && Cur_map == removed);

        if (!db.maps().delete(mapId)) return false;

        // Удаляем .slop-файл, если он есть
        if (row.fileName != null) {
            try {
                File mapFile = new File(mapsDir, row.fileName);
                if (mapFile.exists()) mapFile.delete();
            } catch (Exception e) {
                System.err.println("[АДМИН] Не удалось удалить файл карты: " + e.getMessage());
            }
        }

        // Если удалили назначенную следующую карту — сбрасываем выбор
        if (nextMapId != null && nextMapId == mapId) nextMapId = null;

        // НОВОЕ: отложенный автостарт тоже сворачиваем — карту могли только что удалить
        cancelNextMatchTask();

        reloadMapsFromDb();
        if (removed != null) {
            System.out.println("[АДМИН] Карта удалена: " + removed.getMapName());
        }

        // НОВОЕ: удалили карту, на которой играют — сворачиваем цикл в состояние «Не запущен»
        if (wasCurrentMap) {
            Cur_map = null;
            if (matchInProgress) {
                System.out.println("[АДМИН] Удалена текущая карта — матч остановлен, статистика сохранена (STOPPED).");
                if (matchTimerTask != null) matchTimerTask.cancel(false);
                matchInProgress = false;
                broadcastPacket(new MatchEndPacket());
                finalizeMatch("STOPPED");
            } else if (cycleRunning) {
                System.out.println("[АДМИН] Удалена текущая карта — цикл остановлен.");
            }
            enterIdleState();
        }
        return true;
    }

    /** НОВОЕ: запись бан-листа. */
    public static class BanEntry {
        public String ip;
        public String playerName; // ник, под которым игрок был забанен (может быть пустым)
        public String reason;     // причина бана (может быть пустой)
        public String bannedAt;   // «дд.ММ.гггг ЧЧ:мм»
    }

    // ==========================================
    //  НОВОЕ: БАН ПО IP (проверка при логине в GameServerHandler)
    // ==========================================

    /** Перечитать бан-лист из БД (вызывается при старте сервера). */
    private void reloadBansFromDb() {
        banList.clear();
        if (db == null) return;
        for (BanDao.BanRow r : db.bans().getAll()) {
            BanEntry e = new BanEntry();
            e.ip = r.ip;
            e.playerName = r.playerName == null ? "" : r.playerName;
            e.reason = r.reason == null ? "" : r.reason;
            e.bannedAt = r.bannedAt;
            banList.put(r.ip, e);
        }
        if (!banList.isEmpty()) {
            System.out.println("[БАН] Загружено забаненных IP из БД: " + banList.size());
        }
    }

    /** НОВОЕ: забанен ли IP (вызывается при каждом логине). */
    public boolean isIpBanned(String ip) {
        return ip != null && !ip.isEmpty() && banList.containsKey(ip);
    }

    /**
     * НОВОЕ: забанить IP. БД — персистентность, память — мгновенное действие.
     * Если БД недоступна, бан всё равно действует до рестарта сервера.
     */
    public synchronized boolean banIp(String ip, String playerName, String reason) {
        if (ip == null || ip.trim().isEmpty()) return false;
        ip = ip.trim();
        if (db != null) {
            db.bans().upsert(ip, playerName, reason);   // best-effort: ошибка БД не мешает бану
        }
        BanEntry e = new BanEntry();
        e.ip = ip;
        e.playerName = playerName == null ? "" : playerName;
        e.reason = reason == null ? "" : reason;
        e.bannedAt = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(new java.util.Date());
        banList.put(ip, e);
        System.out.println("[БАН] IP " + ip + " забанен"
                + (playerName != null && !playerName.isEmpty() ? " (ник: " + playerName + ")" : "")
                + (reason != null && !reason.isEmpty() ? " — " + reason : ""));
        return true;
    }

    /** НОВОЕ: снять бан по IP. true если IP действительно был в бане. */
    public synchronized boolean unbanIp(String ip) {
        if (ip == null) return false;
        BanEntry removed = banList.remove(ip.trim());
        if (db != null) db.bans().delete(ip.trim());   // best-effort
        if (removed != null) {
            System.out.println("[БАН] Бан снят: " + ip.trim());
        }
        return removed != null;
    }

    /** НОВОЕ: копия бан-листа для админ-панели. */
    public synchronized List<BanEntry> getBanList() {
        return new ArrayList<>(banList.values());
    }

    /** НОВОЕ: количество активных банов. */
    public synchronized int getBanCount() { return banList.size(); }

    // ==========================================
    //  НОВОЕ: МОДЕРАЦИЯ ИГРОКОВ (убить / кикнуть / забанить по нику)
    // ==========================================

    /** НОВОЕ: найти игрока по нику (регистронезависимо). */
    public PlayerSession findPlayerByName(String name) {
        if (name == null) return null;
        String target = name.trim().toLowerCase();
        for (PlayerSession p : players.values()) {
            if (p.name != null && p.name.toLowerCase().equals(target)) return p;
        }
        return null;
    }

    /**
     * НОВОЕ: кикнуть игрока по нику — разорвать соединение.
     * Дальше сработает штатная логика отключения (channelInactive): удаление с сервера,
     * рассылка Despawn, при пустом сервере — корректное сворачивание матча.
     */
    public synchronized boolean kickPlayer(String name) {
        PlayerSession p = findPlayerByName(name);
        if (p == null || p.channel == null) return false;
        System.out.println("[АДМИН] Игрок " + p.name + " (IP " + p.ip + ") кикнут администратором.");
        p.channel.close();
        return true;
    }

    /**
     * НОВОЕ: убить игрока по нику (штатная смерть: рассылка PlayerDeathPacket,
     * респавн через 10 сек таймером матча). killerId = -1 — «убит администратором».
     */
    public synchronized boolean adminKillPlayer(String name) {
        PlayerSession p = findPlayerByName(name);
        if (p == null) return false;
        if (p.isDead || p.health <= 0) return false;   // уже мёртв — киллить повторно нечего

        p.health = 0;
        p.deadcount++;
        p.isDead = true;
        p.respawnTimer = 10;
        System.out.println("[АДМИН] Игрок " + p.name + " убит администратором.");
        broadcastPacket(new PlayerDeathPacket(p.id, -1));
        return true;
    }

    /**
     * НОВОЕ: включить/выключить обработку атак игрока (из админ-панели).
     * disabled = true — сервер полностью игнорирует пакеты атаки этого игрока:
     * урон другим не наносится и рассылка атаки не выполняется (анимации/звуки
     * его атак другие клиенты тоже не видят).
     * Действует до повторного включения или до отключения игрока от сервера
     * (при новом логине атаки снова включены).
     */
    public synchronized boolean setPlayerAttacksDisabled(String name, boolean disabled) {
        PlayerSession p = findPlayerByName(name);
        if (p == null) return false;
        if (p.attacksDisabled != disabled) {
            p.attacksDisabled = disabled;
            System.out.println("[АДМИН] Игрок " + p.name + " (IP " + p.ip + "): обработка атак "
                    + (disabled ? "ОТКЛЮЧЕНА — урон другим игрокам не наносится." : "ВКЛЮЧЕНА обратно."));
        }
        return true;
    }

    // ==========================================
    //          КОНСТРУКТОР
    // ==========================================

    public GameWorld() {
        // 1. Подключение к MySQL (admin_config.properties создаётся при первом запуске)
        try {
            AdminConfig cfg = new AdminConfig("admin_config.properties");
            mapsDir = cfg.getMapsDir();
            adminCfg = cfg;

            // НОВОЕ: игровые настройки из конфига (с безопасной нормализацией диапазонов)
            minPlayersToStart = clamp(cfg.getMinPlayersStart(), MIN_START_MIN, MIN_START_MAX, DEFAULT_MIN_PLAYERS_START);
            maxPlayers        = clamp(cfg.getMaxPlayersLimit(), MAX_PLAYERS_MIN, MAX_PLAYERS_MAX, DEFAULT_MAX_PLAYERS);
            matchDurationSec  = clamp(cfg.getMatchDuration(), DURATION_MIN, DURATION_MAX, DEFAULT_MATCH_DURATION);

            // НОВОЕ: игровой порт из конфига (game.port). Вне диапазона 1-65535 — берётся значение по умолчанию.
            gamePort = clamp(cfg.getGamePort(), GAME_PORT_MIN, GAME_PORT_MAX, DEFAULT_GAME_PORT);

            // НОВОЕ: хеш соединения из конфига — хранится как HEX, на сервере преобразуется в long
            try {
                connetHash = parseConnectionHash(cfg.getConnectionHashHex());
            } catch (Exception e) {
                System.err.println("[НАСТРОЙКИ] Некорректный хеш соединения в конфиге ('"
                        + cfg.getConnectionHashHex() + "') — проверка отключена (0): " + e.getMessage());
                connetHash = 0;
            }

            System.out.println("[НАСТРОЙКИ] Мин. игроков для старта: " + minPlayersToStart
                    + " · Макс. игроков: " + maxPlayers
                    + " · Время матча: " + matchDurationSec + " сек"
                    + " · Игровой порт: " + gamePort);
            System.out.println(String.format("[НАСТРОЙКИ] Хеш соединения: %016x%s", connetHash,
                    connetHash == 0 ? " (проверка отключена)" : ""));

            db = DatabaseManager.init(cfg);
            seedMapsFromJson("maps_info.json");   // импорт старых карт, только если БД пуста
            reloadMapsFromDb();
            reloadBansFromDb();   // НОВОЕ: бан-лист из БД (проверка при логине)
        } catch (Exception e) {
            System.err.println("[БД] НЕ УДАЛОСЬ ПОДКЛЮЧИТЬСЯ К MYSQL: " + e.getMessage());
            System.err.println("[БД] Сервер продолжит работу БЕЗ базы данных и статистики!");
            db = null;
            loadMapsFromJson("maps_info.json");   // аварийный режим: старый способ загрузки карт
        }

        // НОВОЕ: при старте сервера матч НЕ запускается — запуск только кнопкой в админ-панели
        System.out.println("Матч НЕ запущен. Для старта нажмите «Запустить матч» в админ-панели (http://localhost:"
                + (adminCfg != null ? adminCfg.getPort() : 8090) + "/).");
    }

    /** Старый метод загрузки карт из JSON — теперь используется только как аварийный fallback. */
    public void loadMapsFromJson(String filePath) {
        try {
            String jsonContent = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            Gson gson = new GsonBuilder().setLenient().create();
            Type mapListType = new TypeToken<List<MapInfo>>() {}.getType();
            this.Maps_list = gson.fromJson(jsonContent, mapListType);
            if (this.Maps_list == null) {
                this.Maps_list = new ArrayList<>();
            }
            System.out.println("Успешно загружено карт из JSON: " + Maps_list.size());
        } catch (Exception e) {
            System.err.println("Ошибка при чтении файла " + filePath + ": " + e.getMessage());
        }
    }

    public int generateId() {
        return idGenerator.getAndIncrement();
    }

    /** НОВОЕ: приведение значения к диапазону (для значений из конфига). */
    private static int clamp(int v, int min, int max, int def) {
        if (v < min || v > max) {
            System.out.println("[НАСТРОЙКИ] Значение " + v + " вне диапазона [" + min + ".." + max + "], используем " + def);
            return def;
        }
        return v;
    }

    public boolean addPlayer(Channel channel, PlayerSession session) {
        if (players.size() >= maxPlayers) return false;
        players.put(channel, session);
        return true;
    }

    public PlayerSession removePlayer(Channel channel) {
        PlayerSession removedPlayer = players.remove(channel);

        // Если сервер пуст И матч сейчас идет
        if (players.isEmpty() && matchInProgress) {
            System.out.println("Все игроки вышли! Прерывание матча без статистики.");

            if (matchTimerTask != null) {
                matchTimerTask.cancel(false);
            }

            // НОВОЕ: закрываем запись матча в БД со статусом ABORTED
            if (db != null && currentMatchId > 0) {
                db.matches().finish(currentMatchId, "ABORTED", null, 0, 0);
                currentMatchId = -1;
            }

            matchInProgress = false;

            // НОВОЕ: если цикл не остановлен вручную — он продолжает работать в штатном
            // режиме: пауза 10 сек, новая карта, ожидание игроков. Иначе — «Не запущен».
            continueOrIdleAfterMatch();
        } else if (removedPlayer != null && db != null) {
            // НОВОЕ: обновляем время последней активности игрока в БД
            int dbId = removedPlayer.dbId > 0 ? removedPlayer.dbId : db.players().findIdByName(removedPlayer.name);
            if (dbId > 0) {
                db.players().touch(dbId);
            }
        }

        return removedPlayer;
    }

    public PlayerSession getPlayer(Channel channel) {
        return players.get(channel);
    }

    public PlayerSession getPlayerById(int id) {
        for (PlayerSession session : players.values()) {
            if (session.id == id) {
                return session;
            }
        }
        return null;
    }

    public ConcurrentHashMap<Channel, PlayerSession> getPlayers() {
        return players;
    }

    // ==========================================
    //         ЛОГИКА МАТЧА И ТАЙМЕРА
    // ==========================================

    private int lastMapIndex = -1;

    public MapInfo getRandomMap() {
        Random random = new Random();

        if (Maps_list.size() <= 1) {
            lastMapIndex = 0;
            return Maps_list.isEmpty() ? null : Maps_list.get(0);
        }

        int newIndex;
        do {
            newIndex = random.nextInt(Maps_list.size());
        } while (newIndex == lastMapIndex);

        lastMapIndex = newIndex;
        return Maps_list.get(newIndex);
    }

    /**
     * НОВОЕ: Выбор карты для цикла.
     * Если администратор назначил конкретную карту — она и выбирается,
     * иначе случайная (не текущая, как и раньше).
     */
    private MapInfo pickMap() {
        if (nextMapId != null) {
            MapInfo fixed = dbMaps.get(nextMapId);
            nextMapId = null; // выбор сработал один раз
            if (fixed != null) return fixed;
        }
        return getRandomMap();
    }

    // Запуск цикла матча (выбор карты, ожидание загрузки).
    // НОВОЕ: возвращает false, если запустить цикл нельзя (нет ни одной карты) —
    // состояние «Не запущен» при этом НЕ меняется.
    public synchronized boolean startMatchCycle() {
        // НОВОЕ: guard пустого списка — раньше cycleRunning залипал в true без карты
        if (Maps_list.isEmpty()) {
            cycleRunning = false;
            matchInProgress = false;
            Cur_map = null;
            System.err.println("[АДМИН] Запуск цикла невозможен: НЕТ КАРТ! Добавьте карту через админ-панель.");
            return false;
        }

        cycleRunning = true;   // НОВОЕ: цикл активен
        matchInProgress = false;
        timeLeft = matchDurationSec;

        for (PlayerSession p : players.values()) {
            p.isReady = false;
            p.isDead = false;
            p.respawnTimer = 0;
            p.health = 100;   // НОВОЕ: новый матч всегда начинается с полным HP (после админ-килла вне матча)
        }

        // Выбор карты (назначенная админом или случайная)
        Cur_map = pickMap();
        if (Cur_map == null) {
            // Теоретически недостижимо (список не пуст), но на всякий случай — откат
            cycleRunning = false;
            System.err.println("Новый цикл: НЕТ КАРТ В БАЗЕ! Добавьте карту через админ-панель.");
            return false;
        }
        System.out.println("Новый цикл: Выбрана карта " + Cur_map.mapName);

        // Оповещение всех игроков о новой карте (НОВОЕ: вместе с CRC64 хешем карты)
        long mapCrc = Cur_map.getHashcrc64() == null ? 0L : Cur_map.getHashcrc64();
        MapInfoPacket mapPacket = new MapInfoPacket(Cur_map.mapName, Cur_map.urlDownload, mapCrc);
        broadcastPacket(mapPacket);

        // Запуск таймаута загрузки (если через 30 сек не все загрузили - начинаем без них)
        scheduler.schedule(this::checkAndStartMatch, LOADING_TIMEOUT, TimeUnit.SECONDS);
        return true;
    }

    public synchronized void checkAndStartMatch() {
        if (matchInProgress) return;
        if (!cycleRunning) return;   // НОВОЕ: в состоянии «Не запущен» автостарта нет

        int readyCount = getReadyCount();

        if (readyCount >= minPlayersToStart) {
            System.out.println("Готово " + readyCount + " из " + minPlayersToStart + " игроков. Старт матча!");
            startMatch();
        } else {
            System.out.println("Ожидание игроков... Готово: " + readyCount + " из " + minPlayersToStart);
        }
    }

    /** НОВОЕ: количество готовых игроков (нужно для статуса в админке). */
    public int getReadyCount() {
        int readyCount = 0;
        for (PlayerSession p : players.values()) {
            if (p.isReady) readyCount++;
        }
        return readyCount;
    }

    private void processRespawns() {
        for (PlayerSession p : players.values()) {
            if (p.isDead) {
                p.respawnTimer--;

                if (p.respawnTimer <= 0) {
                    p.isDead = false;
                    p.health = 100;
                    p.x = 0;
                    p.y = 0;
                    p.z = 0;

                    System.out.println("Игрок " + p.name + " возродился.");
                    broadcastPacket(new PlayerRespawnPacket(p.id, p.model, p.name));
                }
            }
        }
    }

    // Старт самого таймера
    private synchronized void startMatch() {
        matchInProgress = true;
        timeLeft = matchDurationSec;
        System.out.println("Матч начался! Таймер: " + matchDurationSec + " сек.");

        // НОВОЕ: создаём запись матча в БД
        if (db != null && Cur_map != null) {
            currentMatchId = db.matches().create(Cur_map.getMapName());
        }

        broadcastPacket(new MatchStartPacket(matchDurationSec));

        matchTimerTask = scheduler.scheduleAtFixedRate(() -> {
            timeLeft--;
            if (timeLeft <= 0) {
                endMatch();
            } else {
                processRespawns();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    // Конец матча и отправка статистики
    private void endMatch() {
        if (matchTimerTask != null) matchTimerTask.cancel(false);
        matchInProgress = false;
        System.out.println("Матч окончен! Отправка статистики.");

        broadcastPacket(new MatchEndPacket());

        // НОВОЕ: сохраняем статистику в БД и рассылаем её игрокам
        finalizeMatch("FINISHED");

        // НОВОЕ: если цикл был запущен и не остановлен вручную — он работает в штатном
        // режиме: пауза CYCLE_PAUSE_AFTER_MATCH_SEC сек, затем выбор карты и новый матч.
        // Иначе (кнопка «Стоп» нажата в момент окончания) — состояние «Не запущен».
        continueOrIdleAfterMatch();
    }

    /**
     * НОВОЕ: атомарное решение после конца матча (устраняет гонку с кнопкой «Стоп»):
     *  - цикл запущен и не остановлен вручную → пауза 10 сек и следующий матч;
     *  - цикл остановлен/не запущен → состояние «Не запущен» (ждём ручного запуска).
     */
    private synchronized void continueOrIdleAfterMatch() {
        if (cycleRunning) {
            scheduleNextCycleMatch();
        } else {
            enterIdleState();
        }
    }

    // ==========================================================
    //  НОВОЕ: НЕПРЕРЫВНЫЙ ЦИКЛ МАТЧЕЙ (пока не остановят кнопкой)
    // ==========================================================

    /**
     * НОВОЕ: отложить следующий матч цикла на CYCLE_PAUSE_AFTER_MATCH_SEC секунд.
     * В timeLeft идёт обратный отсчёт паузы (виден в админ-панели).
     * Вызов ТОЛЬКО когда matchInProgress == false.
     */
    private synchronized void scheduleNextCycleMatch() {
        cancelNextMatchTask();
        timeLeft = CYCLE_PAUSE_AFTER_MATCH_SEC;
        System.out.println("[ЦИКЛ] Пауза " + CYCLE_PAUSE_AFTER_MATCH_SEC
                + " сек (статистика разослана), затем — новая карта и новый матч.");
        nextMatchTask = scheduler.scheduleAtFixedRate(() -> {
            if (timeLeft > 0) timeLeft--;
            if (timeLeft <= 0) {
                cancelNextMatchTask();
                runNextCycleMatch();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** НОВОЕ: отмена отложенного автостарта следующего матча (если был). */
    private synchronized void cancelNextMatchTask() {
        if (nextMatchTask != null) {
            nextMatchTask.cancel(false);
            nextMatchTask = null;
        }
    }

    /**
     * НОВОЕ: очередной матч цикла — выбрать карту (назначенная админом или случайная)
     * и разослать её игрокам. Дальше штатная логика: ожидание загрузки/готовности.
     */
    private synchronized void runNextCycleMatch() {
        if (!cycleRunning || matchInProgress) return;   // цикл остановили вручную — выходим
        if (Maps_list.isEmpty()) {                      // карты исчезли — циклу нечего запускать
            System.err.println("[ЦИКЛ] Нет карт для следующего матча — цикл остановлен.");
            enterIdleState();
            return;
        }
        System.out.println("[ЦИКЛ] Запуск следующего матча цикла.");
        startMatchCycle();
    }

    /** НОВОЕ: идёт ли сейчас пауза между матчами (для статуса в админ-панели). */
    public boolean isWaitingNextMatch() {
        return nextMatchTask != null && !matchInProgress;
    }

    /**
     * НОВОЕ: Перевести сервер в состояние «Матч не запущен» (после конца матча,
     * остановки админом или выхода всех игроков). Новый матч стартует только
     * кнопкой «Запустить матч» в админ-панели.
     */
    private synchronized void enterIdleState() {
        cycleRunning = false;
        matchInProgress = false;
        cancelNextMatchTask();   // НОВОЕ: никакой отложенный автостарт не должен пережить «Стоп»
        timeLeft = matchDurationSec;
        for (PlayerSession p : players.values()) {
            p.isReady = false;
            p.isDead = false;
            p.respawnTimer = 0;
        }
        System.out.println("Состояние: матч НЕ запущен. Для старта нажмите «Запустить матч» в админ-панели.");
    }

    /**
     * НОВОЕ: Единое сохранение итогов матча — в БД и рассылка PlayerStatsPacket.
     * Используется при обычном окончании (FINISHED) и остановке админом (STOPPED).
     */
    private synchronized void finalizeMatch(String status) {
        // Определяем победителя (больше всего киллов) — ДО сброса счётчиков
        PlayerSession winner = null;
        for (PlayerSession p : players.values()) {
            if (winner == null || p.killcount > winner.killcount) winner = p;
        }
        String winnerName = winner != null ? winner.name : null;
        int winnerKills = winner != null ? winner.killcount : 0;

        for (PlayerSession p : players.values()) {
            broadcastPacket(new PlayerStatsPacket(p.id, p.name, p.killcount, p.deadcount));

            // НОВОЕ: запись в БД
            if (db != null) {
                int dbId = p.dbId > 0 ? p.dbId : db.players().findIdByName(p.name);
                if (dbId <= 0) dbId = db.players().upsert(p.name, p.model);
                if (dbId > 0) {
                    p.dbId = dbId;
                    db.players().addResult(dbId, p.killcount, p.deadcount);
                    if (currentMatchId > 0) {
                        db.matches().addStat(currentMatchId, dbId, p.name, p.killcount, p.deadcount);
                    }
                }
            }

            p.killcount = 0;
            p.deadcount = 0;
            p.health = 100;
            p.x = 0; p.y = 0; p.z = 0; p.rotY = 0;
            p.isDead = false;
            p.respawnTimer = 0;
        }

        // НОВОЕ: закрываем запись матча в БД
        if (db != null && currentMatchId > 0) {
            db.matches().finish(currentMatchId, status, winnerName, winnerKills, players.size());
            System.out.println("[БД] Матч #" + currentMatchId + " сохранён со статусом " + status
                    + (winnerName != null ? ", победитель: " + winnerName + " (" + winnerKills + ")" : ""));
        }
        currentMatchId = -1;
    }

    // Отметить игрока готовым
    public void setPlayerReady(Channel channel) {
        PlayerSession p = players.get(channel);
        if (p != null && !p.isReady) {
            p.isReady = true;
            System.out.println("Игрок " + p.name + " загрузился.");

            if (matchInProgress) {
                channel.writeAndFlush(new MatchStartPacket(timeLeft));
            } else {
                checkAndStartMatch();
            }
        }
    }

    // ==========================================
    //      НОВОЕ: УПРАВЛЕНИЕ ИЗ АДМИН-ПАНЕЛИ
    // ==========================================

    /**
     * Остановить текущий матч принудительно (кнопка "Стоп").
     * Статистика матча сохраняется в БД со статусом STOPPED и рассылается игрокам.
     * НОВОЕ: работает в ЛЮБОЙ фазе — идёт матч, пауза между матчами или лобби.
     * После «Стоп» сервер всегда ждёт ручного запуска цикла матчей.
     */
    public synchronized boolean stopMatchByAdmin() {
        boolean stoppedSomething = false;

        if (matchInProgress) {
            if (matchTimerTask != null) matchTimerTask.cancel(false);
            matchInProgress = false;

            System.out.println("[АДМИН] Матч остановлен администратором!");
            broadcastPacket(new MatchEndPacket());
            finalizeMatch("STOPPED");
            stoppedSomething = true;
        }

        // НОВОЕ: «Стоп» во время паузы между матчами — снимаем отложенный автостарт
        if (nextMatchTask != null) {
            System.out.println("[АДМИН] Отложенный автостарт следующего матча отменён администратором.");
            stoppedSomething = true;
        }

        // НОВОЕ: «Стоп» в фазе лобби (цикл запущен, матча нет) — останавливаем цикл
        if (cycleRunning) {
            System.out.println("[АДМИН] Цикл матчей остановлен администратором.");
            stoppedSomething = true;
        }

        // НОВОЕ: состояние «Не запущен» — следующий матч только кнопкой
        enterIdleState();
        return stoppedSomething;
    }

    /**
     * НОВОЕ: Запустить цикл матча кнопкой «Запустить матч» в админ-панели
     * (из состояния «Не запущен»). Игрокам рассылается карта, дальше — обычный
     * автостарт по готовности или таймауту загрузки.
     */
    public synchronized boolean startCycleByAdmin() {
        if (cycleRunning) return false;
        if (!startMatchCycle()) return false;   // НОВОЕ: false при отсутствии карт
        System.out.println("[АДМИН] Цикл матча запущен администратором!");
        return true;
    }

    /**
     * Принудительно начать матч немедленно (кнопка "Форс-старт"), не дожидаясь готовности.
     * НОВОЕ: если цикл ещё не запущен (состояние «Не запущен») — сначала запускается
     * цикл (игрокам рассылается карта), затем матч стартует немедленно.
     */
    public synchronized boolean forceStartMatch() {
        if (matchInProgress) return false;
        if (players.isEmpty()) return false;

        // НОВОЕ: из состояния «Не запущен» — сначала цикл (карта игрокам), потом матч
        if (!cycleRunning) {
            System.out.println("[АДМИН] Форс-старт из состояния «Не запущен»: сначала цикл с картой.");
            if (!startMatchCycle()) return false;   // НОВОЕ: false при отсутствии карт
        }

        for (PlayerSession p : players.values()) {
            p.isReady = true;
            p.isDead = false;
            p.respawnTimer = 0;
        }
        System.out.println("[АДМИН] Матч запущен принудительно администратором!");
        startMatch();
        return true;
    }

    /**
     * Полный перезапуск цикла (кнопка "Рестарт"): текущий матч прерывается
     * (статус ABORTED, без статистики), всем рассылается новая карта.
     */
    public synchronized boolean restartCycle() {
        // НОВОЕ: отменяем отложенный автостарт — сейчас начнётся свежий цикл
        cancelNextMatchTask();
        if (matchInProgress) {
            if (matchTimerTask != null) matchTimerTask.cancel(false);

            if (db != null && currentMatchId > 0) {
                db.matches().finish(currentMatchId, "ABORTED", null, 0, players.size());
                currentMatchId = -1;
            }
        }
        System.out.println("[АДМИН] Цикл перезапущен администратором!");
        matchInProgress = false;
        return startMatchCycle();   // НОВОЕ: false при отсутствии карт
    }

    /**
     * Назначить карту на следующий матч (null = случайная, не текущая).
     */
    public void setNextMap(Integer mapId) {
        this.nextMapId = mapId;
        if (mapId == null) {
            System.out.println("[АДМИН] Следующий матч: случайная карта (не текущая)");
        } else {
            String name = getMapNameById(mapId);
            System.out.println("[АДМИН] Следующий матч назначен: " + name + " (id=" + mapId + ")");
        }
    }

    /**
     * Применить карту немедленно: назначить и перезапустить цикл прямо сейчас.
     */
    public boolean applyMapNow(Integer mapId) {
        if (mapId != null && !dbMaps.containsKey(mapId)) return false;
        setNextMap(mapId);
        return restartCycle();   // НОВОЕ: false при отсутствии карт
    }

    // ==========================================
    //      НОВОЕ: ИГРОВЫЕ НАСТРОЙКИ ИЗ АДМИН-ПАНЕЛИ
    // ==========================================

    /**
     * Применить игровые настройки (валидация диапазонов выполнена в AdminApiHandler).
     * Вступают в силу немедленно:
     *  - maxPlayers — новые логины отклоняются при заполненности сервера;
     *  - minPlayersToStart — используется при следующей проверке готовности;
     *  - matchDuration — если матч СЕЙЧАС идёт, таймер текущего матча перезапускается
     *    на новую длительность; иначе значение будет использовано в следующем матче.
     * Настройки сохраняются в admin_config.properties (переживают рестарт сервера).
     */
    public synchronized void applyGameSettings(int minStart, int maxP, int durationSec) {
        this.minPlayersToStart = minStart;
        this.maxPlayers = maxP;

        boolean durationChanged = this.matchDurationSec != durationSec;
        this.matchDurationSec = durationSec;

        // Если матч идёт и время изменилось — перезапускаем таймер текущего матча на новую длительность
        if (matchInProgress && durationChanged) {
            timeLeft = durationSec;
            System.out.println("[АДМИН] Таймер идущего матча перезапущен: " + durationSec + " сек.");
        }

        if (adminCfg != null) {
            adminCfg.saveGameSettings(minStart, maxP, durationSec);
        }
        System.out.println("[АДМИН] Игровые настройки применены: мин. для старта=" + minStart
                + ", макс. игроков=" + maxP + ", время матча=" + durationSec + " сек");
    }

    public int getMinPlayersToStart() { return minPlayersToStart; }
    public int getMaxPlayersLimit()  { return maxPlayers; }
    public int getMatchDurationSec() { return matchDurationSec; }

    // ==========================================
    //      НОВОЕ: ХЕШ СОЕДИНЕНИЯ (настройка)
    // ==========================================

    /**
     * Преобразование хеша соединения из HEX (формат хранения в настройках) в long.
     * "0" или пустая строка — 0 (проверка отключена).
     * Некорректный формат — IllegalArgumentException.
     */
    public static long parseConnectionHash(String hex) {
        String h = (hex == null ? "" : hex.trim());
        if (h.isEmpty() || h.equalsIgnoreCase("0")) return 0L;
        if (h.length() > 16 || !h.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Ожидался HEX (1-16 цифр 0-9, A-F), получено: '" + hex + "'");
        }
        return Long.parseUnsignedLong(h, 16);   // unsigned — любые 64 бита
    }

    /** long -> HEX-строка из 16 цифр (как в логе сервера: %016x). */
    public static String formatConnectionHash(long hash) {
        return String.format("%016x", hash);
    }

    /**
     * Применить хеш соединения из админ-панели (0 — проверка отключена).
     * Вступает в силу немедленно для новых логинов и ПЕРСИСТРУЕТСЯ
     * в admin_config.properties в виде HEX (переживает рестарт сервера).
     */
    public synchronized void applyConnectionHash(long hash) {
        this.connetHash = hash;
        if (adminCfg != null) {
            adminCfg.saveConnectionHash(hash == 0 ? "0" : formatConnectionHash(hash));
        }
        if (hash == 0) {
            System.out.println("[АДМИН] Хеш соединения отключён (0) — проверка хеша при логине выключена.");
        } else {
            System.out.println(String.format("[АДМИН] Хеш соединения применён: %016x", hash));
        }
    }

    /** Текущий хеш соединения в виде HEX-строки (для админ-панели); "0" — отключён. */
    public String getConnetHashHex() {
        return connetHash == 0 ? "0" : formatConnectionHash(connetHash);
    }

    // ==========================================
    //      НОВОЕ: ГЕТТЕРЫ ДЛЯ АДМИН-ПАНЕЛИ
    // ==========================================

    public DatabaseManager getDb() { return db; }

    public int getUptimeSeconds() {
        return (int) ((System.currentTimeMillis() - startTime) / 1000);
    }

    public int getMatchDuration() { return matchDurationSec; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getMaxPlayersFromStart() { return minPlayersToStart; }

    /** НОВОЕ: игровой порт из admin_config.properties (game.port), применённый при старте. */
    public int getPort() { return gamePort; }

    public Integer getNextMapId() { return nextMapId; }

    /** Название карты по её id в БД (или null). */
    public String getMapNameById(int mapId) {
        MapInfo m = dbMaps.get(mapId);
        return m == null ? null : m.getMapName();
    }

    /**
     * НОВОЕ: регистрация игрока в БД при логине. Возвращает id игрока (или -1).
     */
    public int registerPlayerInDb(String name, int model) {
        if (db == null) return -1;
        int id = db.players().upsert(name, model);
        if (id > 0) System.out.println("[БД] Игрок " + name + " зарегистрирован (id=" + id + ")");
        return id;
    }

    // Вспомогательные методы
    public void broadcastPacket(Object packet) {
        for (Channel ch : players.keySet()) {
            ch.writeAndFlush(packet);
        }
    }

    public boolean isMatchInProgress() { return matchInProgress; }
    /** НОВОЕ: запущен ли цикл матча (кнопкой в админ-панели). */
    public boolean isCycleRunning() { return cycleRunning; }
    /** НОВОЕ: пауза между матчами непрерывного цикла, сек (для подсказки в админке). */
    public int getCyclePauseSec() { return CYCLE_PAUSE_AFTER_MATCH_SEC; }
    /** НОВОЕ: сколько карт сейчас загружено в память (в т.ч. fallback-режим без БД). */
    public int getMapsCount() { return Maps_list.size(); }
    public int getTimeLeft() { return timeLeft; }
    public MapInfo getCurMap() { return Cur_map; }
}
