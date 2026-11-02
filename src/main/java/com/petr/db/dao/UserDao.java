package com.petr.db.dao;

import com.petr.db.entity.User;
import com.petr.db.util.HibernateSessionFactoryUtil;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.util.List;

public class UserDao {
    private final SessionFactory sessionFactory;

    public UserDao() {
        this.sessionFactory = HibernateSessionFactoryUtil.getSessionFactory();
    }

    public void registerUser(User user) {
        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            session.persist(user);
            tx.commit();
        }
    }

    public User getUserById(Long id) {
        try (Session session = this.sessionFactory.openSession()) {
            return session.find(User.class, id);
        }
    }

    public void setUserStatus(String status, Long tgId) {
        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            User user = session.find(User.class, tgId);
            if (user == null) {
                System.out.println("setUserStatus: пользователь не найден, tgId=" + tgId);
                tx.rollback();
                return;
            }
            user.setWaitAccept(status);
            tx.commit();
        }
    }

    public void setUserName(Long tgId, String tgName) {
        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            User user = session.find(User.class, tgId);
            if (user == null) {
                tx.rollback();
                return;
            }
            user.setTgName(tgName);
            tx.commit();
        }
    }

    public boolean getUserHasConfig(Long tgId) {
        try (Session session = this.sessionFactory.openSession()) {
            User user = session.find(User.class, tgId);
            return user != null && Boolean.TRUE.equals(user.getHasConfig());
        }
    }

    public String getUserStatus(Long tgId) {
        try (Session session = this.sessionFactory.openSession()) {
            User user = session.find(User.class, tgId);
            return user != null ? user.getWaitAccept() : "w";
        }
    }

    public List<User> getAllUsers() {
        try (Session session = this.sessionFactory.openSession()) {
            return session.createQuery("FROM User ORDER BY id", User.class).list();
        }
    }

    public void setUserHasConfig(Long tgId, boolean status) {
        try (Session session = this.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            User user = session.find(User.class, tgId);
            if (user == null) {
                System.out.println("setUserHasConfig: пользователь не найден, tgId=" + tgId);
                tx.rollback();
                return;
            }
            user.setHasConfig(status);
            tx.commit();
        }
    }
}
