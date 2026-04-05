package com.petr.db.dao;

import com.petr.db.entity.User;
import com.petr.db.util.HibernateSessionFactoryUtil;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

public class UserDao {
    private final SessionFactory sessionFactory;

    public UserDao() {
        this.sessionFactory = HibernateSessionFactoryUtil.getSessionFactory();
    }

    public void registerUser(User user) {
        try(Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            session.persist(user);
            tx.commit();
        }
    }

    public User getUserById(Long id) {
        try(Session session = this.sessionFactory.openSession()) {
            return session.find(User.class, id);
        }
    }

    public void setUserStatus(User user) {
        try(Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            session.clear();
        }
    }

    public boolean getUserHasConfig(Long tgId) {
        try(Session session = this.sessionFactory.openSession()) {
            return session.find(User.class, tgId).getHasConfig();
        }
    }

    public String getUserStatus(Long tgId) {
        try(Session session = this.sessionFactory.openSession()) {
            return session.find(User.class, tgId).getWaitAccept();
        }
    }

    public void setUserHasConfig(Long tgId, boolean status) {
        try(Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            User user = session.find(User.class, tgId);
            user.setHasConfig(status);
            tx.commit();
        }
    }
}
