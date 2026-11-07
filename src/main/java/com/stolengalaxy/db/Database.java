package com.stolengalaxy.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.Map;
import java.util.function.Function;

/** Opens the "StolenGalaxy" persistence unit and runs work in short-lived EntityManagers. */
public final class Database {

    /** DB_URL (e.g. jdbc:sqlite:/tmp/test.db) overrides the JDBC URL in persistence.xml. */
    private static final EntityManagerFactory EMF = Persistence.createEntityManagerFactory("StolenGalaxy",
            System.getenv("DB_URL") == null ? Map.of() : Map.of("jakarta.persistence.jdbc.url", System.getenv("DB_URL")));

    private Database() {
    }

    /** Runs {@code work} in a transaction, committing if it returns and rolling back if it throws. */
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

    /** Runs read-only {@code work}. Map entities to DTOs inside it: lazy relationships need the open EntityManager. */
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
