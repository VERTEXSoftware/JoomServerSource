package org.network.admin;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Простая авторизация админ-панели: пароль из конфига -> сессионный токен на 12 часов.
 */
public class AdminAuth {

    private static final long TOKEN_TTL_MS = 12L * 60 * 60 * 1000; // 12 часов

    private final String password;
    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<>();

    public AdminAuth(String password) {
        this.password = password;
    }

    /**
     * Проверить пароль и выдать токен.
     * @return токен или null, если пароль неверный
     */
    public String login(String inputPassword) {
        if (inputPassword == null || !inputPassword.equals(password)) {
            return null;
        }
        cleanup();
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return token;
    }

    /** Проверить действительность токена. */
    public boolean isValid(String token) {
        if (token == null || token.isEmpty()) return false;
        Long expiresAt = tokens.get(token);
        if (expiresAt == null) return false;
        if (expiresAt < System.currentTimeMillis()) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    /** Удалить просроченные токены. */
    private void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) it.remove();
        }
    }
}
