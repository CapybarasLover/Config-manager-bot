package com.petr.db.util;

import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

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

            if(env.equals("prod")){
                standardServiceRegistry = new StandardServiceRegistryBuilder()
                        .configure("db/hibernate-prod.properties")
                        .build();
            } else if(env.equals("dev")){
                standardServiceRegistry = new StandardServiceRegistryBuilder()
                        .configure("db/hibernate-dev.properties")
                        .build();
            } else {
                throw new IllegalArgumentException("Unknown parameter: " + env);
            }


            // 2. Создаём Metadata
            Metadata metadata = new MetadataSources(standardServiceRegistry)
                    .getMetadataBuilder()
                    .build();

            // 3. Строим SessionFactory
            return metadata.getSessionFactoryBuilder().build();

        } catch (Exception ex) {
            // Важно: выбрасываем ошибку при инициализации, чтобы приложение упало сразу
            throw new ExceptionInInitializerError("Не удалось создать SessionFactory: " + ex.getMessage());
        }
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