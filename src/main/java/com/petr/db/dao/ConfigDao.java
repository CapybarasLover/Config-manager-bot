package com.petr.db.dao;

import com.petr.db.entity.Config;
import com.petr.db.entity.User;
import com.petr.db.util.HibernateSessionFactoryUtil;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.util.List;

public class ConfigDao {
    private final SessionFactory sessionFactory;

    public ConfigDao() {
        this.sessionFactory = HibernateSessionFactoryUtil.getSessionFactory();
    }

    public void saveConfig(Long tgId, String configName, String vlessLink, String subLink, String xhttpLink, String realityLink, String country) {
        String resolvedCountry = (country != null && !country.isBlank()) ? country : "latv";

        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();

            User user = session.find(User.class, tgId);
            if (user == null) {
                throw new IllegalArgumentException("Пользователь не найден: " + tgId);
            }

            Config existing = session.createQuery("""
                    FROM Config
                    WHERE tgUser.id = :tgId AND country = :country
                    """, Config.class)
                    .setParameter("tgId", tgId)
                    .setParameter("country", resolvedCountry)
                    .uniqueResult();

            if (existing == null) {
                Config config = new Config();
                config.setTgUser(user);
                config.setConfigName(configName);
                config.setVlessLink(vlessLink);
                config.setSubLink(subLink);
                config.setXhttpLink(xhttpLink);
                config.setRealityLink(realityLink);
                config.setCountry(resolvedCountry);
                session.persist(config);
            } else {
                existing.setConfigName(configName);
                existing.setVlessLink(vlessLink);
                existing.setSubLink(subLink);
                existing.setXhttpLink(xhttpLink);
                existing.setRealityLink(realityLink);
                existing.setCountry(resolvedCountry);
            }

            tx.commit();
        }
    }

    public Config getConfigByUserIdAndCountry(Long userId, String country) {
        String resolvedCountry = (country != null && !country.isBlank()) ? country : "latv";

        try (Session session = this.sessionFactory.openSession()) {
            return session.createQuery("""
                    FROM Config
                    WHERE tgUser.id = :userId AND country = :country
                    """, Config.class)
                    .setParameter("userId", userId)
                    .setParameter("country", resolvedCountry)
                    .uniqueResult();
        }
    }

    public List<Config> getConfigsByUserId(Long userId) {
        try (Session session = this.sessionFactory.openSession()) {
            return session.createQuery("""
                    FROM Config
                    WHERE tgUser.id = :userId
                    ORDER BY country
                    """, Config.class)
                    .setParameter("userId", userId)
                    .getResultList();
        }
    }

    public Config getConfigByName(String configName) {
        try (Session session = this.sessionFactory.openSession()) {
            return session.createQuery("""
                    FROM Config
                    WHERE configName = :name
                    """, Config.class)
                    .setParameter("name", configName)
                    .uniqueResult();
        }
    }

    public void updateXhttpLink(Long tgId, String country, String xhttpLink) {
        String resolvedCountry = (country != null && !country.isBlank()) ? country : "latv";

        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();

            Config config = session.createQuery("""
                    FROM Config
                    WHERE tgUser.id = :tgId AND country = :country
                    """, Config.class)
                    .setParameter("tgId", tgId)
                    .setParameter("country", resolvedCountry)
                    .uniqueResult();

            if (config != null) {
                config.setXhttpLink(xhttpLink);
            }

            tx.commit();
        }
    }

    public void deleteConfigByUserIdAndCountry(Long tgId, String country) {
        String resolvedCountry = (country != null && !country.isBlank()) ? country : "latv";

        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();

            Config config = session.createQuery("""
                    FROM Config
                    WHERE tgUser.id = :tgId AND country = :country
                    """, Config.class)
                    .setParameter("tgId", tgId)
                    .setParameter("country", resolvedCountry)
                    .uniqueResult();

            if (config != null) {
                session.remove(config);
            }

            tx.commit();
        }
    }
}