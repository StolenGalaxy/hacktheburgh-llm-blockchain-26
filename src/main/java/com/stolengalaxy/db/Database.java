package com.stolengalaxy.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.Map;
import java.util.function.Function;

public final class Database {

    private static final EntityManagerFactory EMF = Persistence.createEntityManagerFactory("StolenGalaxy",
            System.getenv("DB_URL") == null ? Map.of() : Map.of("jakarta.persistence.jdbc.url", System.getenv("DB_URL")));

    private Database() {
    }

    public static <T> T transaction(Function<EntityManager, T> work) {
        EntityManager em = EMF.createEntityManager();
        try {
            em.getTransaction().begin();
            T result = work.apply(em);
            em.getTransaction().commit();
            return result;
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }

    public static <T> T read(Function<EntityManager, T> work) {
        EntityManager em = EMF.createEntityManager();
        try {
            return work.apply(em);
        } finally {
            em.close();
        }
    }

    public static void close() {
        EMF.close();
    }
}
