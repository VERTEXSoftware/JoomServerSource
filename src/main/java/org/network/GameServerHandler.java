package org.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.network.maps.MapInfo;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GameServerHandler extends SimpleChannelInboundHandler<Object> {

    // ИЗМЕНЕНО: максимально допустимый урон за одну атаку.
    // Сервер валидирует урон: всё, что клиент пришлет сверх этого значения, обрезается.
    // Если у какого-то оружия легитимный урон больше — поднимите константу.
    private static final int MAX_ATTACK_DAMAGE = 21;

    private final GameWorld gameWorld;

    public GameServerHandler(GameWorld gameWorld) {
        this.gameWorld = gameWorld;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("Новое подключение ожидает логина...");
    }

    // Тихая обработка обрывов соединения (Connection reset и т.п.) — без стек-трейсов
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof java.io.IOException) {
            // Обрыв соединения (клиент закрыл сокет, network reset) — штатная ситуация
            ctx.close();
        } else {
            System.err.println("Ошибка обработки пакета: " + cause.getMessage());
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel disconnectedChannel = ctx.channel();
        PlayerSession removedPlayer = gameWorld.removePlayer(disconnectedChannel);

        if (removedPlayer != null) {
            System.out.println("Игрок " + removedPlayer.name + " (ID: " + removedPlayer.id + ") отключился.");
            broadcastPacket(new DespawnPacket(removedPlayer.id), disconnectedChannel);
        }
    }

    // Обработка готовности клиента
    private void handleClientReady(PlayerSession sender) {
        // 1. Сообщаем GameWorld, что игрок готов (GameWorld отправит MatchStartPacket если матч уже идет)
        gameWorld.setPlayerReady(sender.channel);

        // 2. Отправляем новому игроку данные об уже подключенных игроках (только живых)
        for (Map.Entry<Channel, PlayerSession> entry : gameWorld.getPlayers().entrySet()) {
            if (!entry.getKey().equals(sender.channel)) {
                if (entry.getValue().health > 0) {
                    sender.channel.writeAndFlush(new SpawnPacket(entry.getValue().id, entry.getValue().model, entry.getValue().name));
                }
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        Channel senderChannel = ctx.channel();

        if (msg instanceof LoginPacket) {
            handleLogin((LoginPacket) msg, senderChannel, ctx);
            return;
        }

        PlayerSession sender = gameWorld.getPlayer(senderChannel);
        if (sender == null) return;

        // Обработка готовности клиента
        if (msg instanceof ClientReadyPacket) {
            handleClientReady(sender);
            return;
        }

        if (msg instanceof DespawnPacket) {
            channelInactive(ctx);
            return;
        }

        // Блокируем действия, если игрок мертв ИЛИ матч еще не начался (фаза загрузки/статистики)
        // ИЗМЕНЕНО: health <= 0 вместо == 0 — здоровье теперь хранит только сервер
        if (sender.health <= 0 || !gameWorld.isMatchInProgress()) {
            if (msg instanceof PlayerStatePacket || msg instanceof PlayerAttackPacket) {
                return;
            }
        }

        if (msg instanceof PlayerStatePacket) {
            PlayerStatePacket packet = (PlayerStatePacket) msg;
            sender.x = packet.x; sender.y = packet.y; sender.z = packet.z; sender.rotY = packet.rotY;
            sender.gunType = packet.gunType;

            // ИЗМЕНЕНО: здоровье игроков хранит СЕРВЕР.
            // Присланное клиентом значение health игнорируется — в пакет подставляется
            // актуальное серверное здоровье, и в таком виде пакет уходит всем.
            packet.health = sender.health;

            // ИЗМЕНЕНО: рассылаем всем, ВКЛЮЧАЯ отправителя. Отправитель получает свой
            // пакет состояния с серверным здоровьем — так клиент синхронизирует свой HP.
            // (Клиент для своего id применяет только health, позицию не трогает.)
            broadcastPacket(packet, null);

        } else if (msg instanceof PlayerAttackPacket) {
            PlayerAttackPacket packet = (PlayerAttackPacket) msg;

            // НОВОЕ: обработка атак игрока отключена из админ-панели — пакет атаки
            // игнорируется ПОЛНОСТЬЮ: урон цели не наносится и рассылка не выполняется
            // (другие клиенты не видят и не слышат его атаки). Без лога — чтобы не спамить
            // консоль при автоматической стрельбе.
            if (sender.attacksDisabled) {
                return;
            }

            // ИЗМЕНЕНО: валидация урона на сервере (клиенту верить нельзя)
            int damage = packet.damage;
            if (damage < 0) damage = 0;
            if (damage > MAX_ATTACK_DAMAGE) damage = MAX_ATTACK_DAMAGE;
            packet.damage = damage;

            // ИЗМЕНЕНО: урон применяет СЕРВЕР к серверному здоровью цели.
            // Убран старый хак «damage > 30 — атакующий бьет сам себя»:
            // завышенный урон просто обрезается, здоровье атакующего не трогается.
            PlayerSession target = gameWorld.getPlayerById(packet.idEnemy);
            if (target != null && !target.isDead && target.health > 0) {
                target.health = Math.max(0, target.health - damage);

                if (target.health == 0) {
                    // Килл засчитывается только если смерть реально обработана
                    if (handlePlayerDeath(target, sender.id)) {
                        sender.killcount++;
                    }
                }
            }

            // Рассылаем атаку всем (для анимаций, звуков и отброса у клиентов)
            broadcastPacket(packet, null);

        } else if (msg instanceof PlayerDeathPacket) {
            handlePlayerDeath(sender, -1);
        }
    }

    // Единая логика смерти и респавна.
    // ИЗМЕНЕНО: возвращает true, если смерть обработана (для одноразового начисления килла)
    private boolean handlePlayerDeath(PlayerSession target, int killerId) {
        // Защита от повторной обработки смерти (если игрок умер, но пакет смерти пришел дважды)
        if (target.isDead) return false;

        target.health = 0;
        target.deadcount++;

        // Устанавливаем состояние смерти и запускаем таймер
        target.isDead = true;
        target.respawnTimer = 10; // 10 секунд до респавна

        System.out.println("Игрок " + target.name + " убит! (Кем: " + killerId + ")");

        // Отправляем всем (включая жертву) сообщение о смерти
        PlayerDeathPacket deathPacket = new PlayerDeathPacket(target.id, killerId);
        broadcastPacket(deathPacket, null);
        return true;
    }

    /**
     * Возвращает уникальное имя в пределах текущей игровой сессии.
     * Если baseName свободно — возвращает его как есть.
     * Иначе подбирает baseName_1, baseName_2, ... пока не найдёт свободное.
     */
    private void handleLogin(LoginPacket login, Channel senderChannel, ChannelHandlerContext ctx) {


        System.out.printf("Хешь соединения: %016x%n",  login.hash);

        if (gameWorld.getConnetHash()!=0 &&  login.hash!=gameWorld.getConnetHash()) {
            ctx.close();
            return;
        }

        // Проверка бана по IP — забаненный не проходит даже до регистрации
        String ip = extractIp(senderChannel);
        if (gameWorld.isIpBanned(ip)) {
            System.out.println("[ЛОГИН] Отказ: IP " + ip + " забанен"
                    + (login.name != null && !login.name.isEmpty() ? " (ник: " + login.name + ")" : ""));
            ctx.close();
            return;
        }

        if (gameWorld.getPlayers().size() >= gameWorld.getMaxPlayersLimit()) {
            System.out.println("Отказ логина: сервер заполнен (макс. " + gameWorld.getMaxPlayersLimit() + " игроков).");
            ctx.close();
            return;
        }

        // Уникализация имени — если имя занято, добавляем _1, _2, ...
        String uniqueName = gameWorld.makeUniqueName(login.name);
        if (!uniqueName.equals(login.name)) {
            System.out.println("[ЛОГИН] Имя '" + login.name + "' занято, назначено: '" + uniqueName + "'");
        }

        int newId = gameWorld.generateId();
        PlayerSession newPlayer = new PlayerSession(newId, login.model, uniqueName);
        newPlayer.health = 100;
        newPlayer.name = uniqueName;          // в глобальной сессии — УЖЕ уникальное имя
        newPlayer.model = login.model;
        newPlayer.channel = senderChannel;

        // Регистрируем в БД с уникальным именем
        newPlayer.ip = ip;   // запоминаем IP для кика/бана из админ-панели
        newPlayer.dbId = gameWorld.registerPlayerInDb(uniqueName, login.model);

        gameWorld.addPlayer(senderChannel, newPlayer);

        // Клиент узнаёт финальное имя из LoginResponsePacket
        senderChannel.writeAndFlush(new LoginResponsePacket(newId, newPlayer.model, uniqueName));

        // Оповещаем всех остальных (уже с уникальным именем)
        broadcastPacket(new SpawnPacket(newId, newPlayer.model, uniqueName), senderChannel);

        if (gameWorld.getCurMap() != null) {
            // НОВОЕ: отправляем карту вместе с CRC64 хешем (null-safe: 0 — хеш неизвестен)
            long mapCrc = gameWorld.getCurMap().getHashcrc64() == null ? 0L : gameWorld.getCurMap().getHashcrc64();
            senderChannel.writeAndFlush(new MapInfoPacket(gameWorld.getCurMap().mapName, gameWorld.getCurMap().urlDownload, mapCrc));
        }
    }

    /** IP-адрес удалённой стороны канала (пустая строка при ошибке). */
    static String extractIp(Channel channel) {
        try {
            InetSocketAddress addr = (InetSocketAddress) channel.remoteAddress();
            if (addr != null && addr.getAddress() != null) {
                return addr.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void broadcastPacket(Object packet, Channel senderChannel) {
        for (Channel targetChannel : gameWorld.getPlayers().keySet()) {
            if (senderChannel == null || !targetChannel.equals(senderChannel)) {
                targetChannel.writeAndFlush(packet);
            }
        }
    }
}
