package com.petr.bot;

import io.github.natanimn.telebof.BotClient;
import io.github.natanimn.telebof.requests.Api;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * telebof 1.2.3 собирает все запросы к Bot API одним общим {@link Request.Builder}. Одновременные
 * запросы (потоки обработчиков и long polling) перетирают друг другу URL и тело: на нагрузочном
 * прогоне из 4000 сообщений 165 терялись и 155 дублировались без всякой ошибки.
 * Подменяем его на builder, у которого своё состояние на каждый поток.
 */
final class TelebofThreadSafety {

    private TelebofThreadSafety() {
    }

    static void install(BotClient client) {
        Set<Api> apis = Collections.newSetFromMap(new IdentityHashMap<>());
        collectApis(client, apis, 2);
        if (apis.isEmpty()) {
            System.out.println("[Bot] внимание: Api в telebof не найден, запросы к Telegram не изолированы");
        }
        for (Api api : apis) {
            try {
                Field builder = Api.class.getDeclaredField("builder");
                builder.setAccessible(true);
                builder.set(api, new PerThreadRequestBuilder());
            } catch (ReflectiveOperationException e) {
                System.out.println("[Bot] внимание: запросы к Telegram не изолированы: " + e);
            }
        }
    }

    private static void collectApis(Object holder, Set<Api> apis, int depth) {
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (!type.getName().startsWith("io.github.natanimn.telebof")) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof Api api) {
                        apis.add(api);
                    } else if (value != null && depth > 0) {
                        collectApis(value, apis, depth - 1);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Поле недоступно: Api ищем в остальных
                }
            }
        }
    }

    private static final class PerThreadRequestBuilder extends Request.Builder {
        private final ThreadLocal<Request.Builder> current = ThreadLocal.withInitial(Request.Builder::new);

        @Override
        public Request.Builder url(String url) {
            current.get().url(url);
            return this;
        }

        @Override
        public Request.Builder post(RequestBody body) {
            current.get().post(body);
            return this;
        }

        @Override
        public Request build() {
            return current.get().build();
        }
    }
}
