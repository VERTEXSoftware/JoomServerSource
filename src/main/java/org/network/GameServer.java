package org.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import org.network.admin.AdminServer;


public class GameServer {
    public static void main(String[] args) throws Exception {
        // 1. Создаем единый игровой мир для всего сервера.
        // Он будет хранить игроков и генерировать ID.
        GameWorld gameWorld = new GameWorld();

        // НОВОЕ: Запускаем веб-админ-панель (порт настраивается в admin_config.properties)
        AdminServer adminServer = null;   // ссылка хранится для проверки портов ниже
        try {
            adminServer = new AdminServer(gameWorld);
            adminServer.start();
        } catch (Exception e) {
            System.err.println("[АДМИН] Не удалось запустить админ-панель: " + e.getMessage());
            System.err.println("[АДМИН] Игровой сервер продолжает работу без веб-интерфейса.");
        }

        // 2. Настраиваем пулы потоков Netty
        EventLoopGroup bossGroup = new NioEventLoopGroup(1); // Принимает входящие подключения
        EventLoopGroup workerGroup = new NioEventLoopGroup(); // Обрабатывает ввод/вывод и логику

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();

                            // 3. Настраиваем пайплайн (должен совпадать с клиентским)
                            // Разворот склеенных пакетов и добавление префикса длины
                            p.addLast(new LengthFieldBasedFrameDecoder(1024, 0, 2, 0, 2));
                            p.addLast(new LengthFieldPrepender(2));

                            // Наши кодеки (Сериализация/Десериализация)
                            p.addLast(new PacketEncoder());
                            p.addLast(new PacketDecoder());

                            // 4. Передаем gameWorld в наш обработчик бизнес-логики
                            p.addLast(new GameServerHandler(gameWorld));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true); // Отключаем алгоритм Нагла для минимизации задержек в игре

            // 5. Запускаем сервер и привязываемся к игровому порту
            // НОВОЕ: порт берётся из admin_config.properties (ключ game.port), а не из константы.
            int gamePort = gameWorld.getPort();

            // НОВОЕ: предупреждение, если игровой порт совпадает с портом админ-панели
            // (админ-панель уже заняла свой порт — биндинг игры упал бы с "Address already in use")
            if (adminServer != null && gamePort == adminServer.getConfig().getPort()) {
                System.err.println("[ОШИБКА] Игровой порт (" + gamePort
                        + ") совпадает с портом админ-панели! Измените game.port в admin_config.properties.");
                System.err.println("[ОШИБКА] Игровой сервер останавливается.");
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
                return;
            }

            ChannelFuture f = b.bind(gamePort).sync();
            System.out.println("======================================");
            System.out.println("Сервер запущен на порту: " + gamePort
                    + (gamePort == GameWorld.DEFAULT_GAME_PORT ? "" : "  (задан в admin_config.properties: game.port)"));
            System.out.println("Максимальное количество игроков: " + gameWorld.getMaxPlayersLimit());
            System.out.println("Ожидание подключений...");
            System.out.println("======================================");

            // Ждем закрытия сервера (блокирует основной поток)
            f.channel().closeFuture().sync();
        } finally {
            // 6. Корректное завершение работы пулов потоков при остановке сервера
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
