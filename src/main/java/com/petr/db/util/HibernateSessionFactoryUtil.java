package com.petr.db.util;

import com.petr.db.entity.Config;
import com.petr.db.entity.User;

import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public class HibernateSessionFactoryUtil {

    // Один экземпляр на всё приложение (eager initialization)
    private static final SessionFactory SESSION_FACTORY = buildSessionFactory();

    private HibernateSessionFactoryUtil() {
        // запрещаем создание экземпляров класса
    }

    private static SessionFactory buildSessionFactory() {
        try {
            StandardServiceRegistry standardServiceRegistry;

            String env = System.getProperty("app.env");

            String propertiesResource;
            if (env.equals("prod")) {
                propertiesResource = "db/hibernate-prod.properties";
            } else if (env.equals("dev")) {
                propertiesResource = "db/hibernate-dev.properties";
            } else {
                throw new IllegalArgumentException("Unknown parameter: " + env);
            }

            Map<String, Object> settings = loadPropertiesAsMap(propertiesResource);
            applyJdbcFromEnvironment(settings, env.equals("prod"));

            standardServiceRegistry = new StandardServiceRegistryBuilder()
                    .applySettings(settings)
                    .build();

            // 2. Создаём Metadata
            Metadata metadata = new MetadataSources(standardServiceRegistry)
                    .addAnnotatedClass(User.class)
                    .addAnnotatedClass(Config.class)
                    .getMetadataBuilder()
                    .build();

            // 3. Строим SessionFactory
            return metadata.getSessionFactoryBuilder().build();

        } catch (Exception ex) {
            // Важно: выбрасываем ошибку при инициализации, чтобы приложение упало сразу
            throw new ExceptionInInitializerError("Не удалось создать SessionFactory: " + ex.getMessage());
        }
    }

    private static Map<String, Object> loadPropertiesAsMap(String classpathResource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Objects.requireNonNull(
                HibernateSessionFactoryUtil.class.getClassLoader().getResourceAsStream(classpathResource),
                "Не найден ресурс: " + classpathResource)) {
            properties.load(in);
        }
        Map<String, Object> map = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return map;
    }

    /**
     * Hibernate не подставляет ${...} в .properties — только литералы. Раз пароль и URL уже
     * попадают в процесс как переменные окружения (Docker, IDE, shell, dotenv-cli и т.д.),
     * читаем их здесь явно.
     */
    private static void applyJdbcFromEnvironment(Map<String, Object> settings, boolean prod) {
        String urlEnv = prod ? "DB_LINK_PROD" : "DB_LINK_DEV";
        String url = requiredEnv(urlEnv);
        String user = requiredEnv("DB_USER");
        String password = System.getenv("DB_PASSWORD");
        if (password == null) {
            password = "";
        }

        settings.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        settings.put("hibernate.connection.url", url);
        settings.put("hibernate.connection.username", user);
        settings.put("hibernate.connection.password", password);

        settings.put("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        settings.put("jakarta.persistence.jdbc.url", url);
        settings.put("jakarta.persistence.jdbc.user", user);
        settings.put("jakarta.persistence.jdbc.password", password);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Для Hibernate задайте переменную окружения «" + name + "» (или экспортируйте из .env до запуска JVM).");
        }
        return value;
    }

    /**
     * Основной метод — возвращает SessionFactory
     */
    public static SessionFactory getSessionFactory() {
        return SESSION_FACTORY;
    }

    /**
     * Корректное завершение работы (вызывать при shutdown приложения)
     */
    public static void shutdown() {
        if (SESSION_FACTORY != null) {
            SESSION_FACTORY.close();
        }
    }
}