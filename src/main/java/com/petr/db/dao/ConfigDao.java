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

    /** У одного имени может быть по конфигу на страну. */
    public List<Config> getConfigsByName(String configName) {
        try (Session session = this.sessionFactory.openSession()) {
            return session.createQuery("""
                    FROM Config c JOIN FETCH c.tgUser
                    WHERE c.configName = :name
                    """, Config.class)
                    .setParameter("name", configName)
                    .getResultList();
        }
    }

    public boolean isConfigNameUsedByOtherUser(String configName, Long tgId) {
        try (Session session = this.sessionFactory.openSession()) {
            return session.createQuery("""
                    SELECT count(*) FROM Config
                    WHERE configName = :name AND tgUser.id <> :tgId
                    """, Long.class)
                    .setParameter("name", configName)
                    .setParameter("tgId", tgId)
                    .uniqueResult() > 0;
        }
    }

    /**
     * Меняет статус, только если он сейчас равен expected. Условие проверяет сама БД,
     * поэтому из двух одновременных решений применяется ровно одно.
     */
    public boolean compareAndSetStatus(Long tgId, String country, String expected, String status) {
        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            int updated = session.createMutationQuery("""
                    UPDATE Config SET status = :status
                    WHERE tgUser.id = :tgId AND country = :country AND status = :expected
                    """)
                    .setParameter("status", status)
                    .setParameter("tgId", tgId)
                    .setParameter("country", country)
                    .setParameter("expected", expected)
                    .executeUpdate();
            tx.commit();
            return updated > 0;
        }
    }

    public void setStatus(Long tgId, String country, String status) {
        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            session.createMutationQuery("""
                    UPDATE Config SET status = :status
                    WHERE tgUser.id = :tgId AND country = :country
                    """)
                    .setParameter("status", status)
                    .setParameter("tgId", tgId)
                    .setParameter("country", country)
                    .executeUpdate();
            tx.commit();
        }
    }

    public Config getConfigByNameAndCountry(String configName, String country) {
        try (Session session = this.sessionFactory.openSession()) {
            return session.createQuery("""
                    FROM Config c JOIN FETCH c.tgUser
                    WHERE c.configName = :name AND c.country = :country
                    """, Config.class)
                    .setParameter("name", configName)
                    .setParameter("country", country)
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