package com.petr.db.dao;

import com.petr.db.entity.Config;
import com.petr.db.entity.User;
import com.petr.db.util.HibernateSessionFactoryUtil;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

public class ConfigDao {
    private final SessionFactory sessionFactory;

    public ConfigDao() {
        this.sessionFactory = HibernateSessionFactoryUtil.getSessionFactory();
    }

    /**
     * Сохраняет новый конфиг или обновляет существующий. Для {@link com.petr.db.entity.Config}
     * с {@code @MapsId} обязательна ссылка на управляемый {@link User} в той же сессии.
     */
    public void saveConfig(Long tgId, String configName, String vlessLink, String subLink) {
        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            User user = session.find(User.class, tgId);
            if (user == null) {
                throw new IllegalArgumentException("Пользователь не найден: " + tgId);
            }
            Config existing = session.find(Config.class, tgId);
            if (existing == null) {
                Config config = new Config();
                config.setTgUser(user);
                config.setConfigName(configName);
                config.setVlessLink(vlessLink);
                config.setSubLink(subLink);
                session.persist(config);
            } else {
                existing.setConfigName(configName);
                existing.setVlessLink(vlessLink);
                existing.setSubLink(subLink);
            }
            tx.commit();
        }
    }

    public Config getConfigByUserId(Long userId) {
        try(Session session = this.sessionFactory.openSession()) {
            return session.find(Config.class, userId);
        }
    }
}
