package com.hazelcast.hibernate;

import com.hazelcast.client.test.TestHazelcastFactory;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.hibernate.entity.DummyEntityWithEmbeddedCollection;
import com.hazelcast.hibernate.instance.HazelcastMockInstanceLoader;
import com.hazelcast.test.annotation.SlowTest;
import org.hibernate.BaseSessionEventListener;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertEquals;

@Category(SlowTest.class)
public class EmbeddedCollectionCacheTest extends HibernateTestSupport {
    private static final String TEST_ATTRIBUTE_KEY = "test-key";

    private TestHazelcastFactory factory;
    private SessionFactory sf;

    @Before
    public void postConstruct() {
        HazelcastMockInstanceLoader loader = new HazelcastMockInstanceLoader();
        factory = new TestHazelcastFactory();
        loader.setInstanceFactory(factory);
        sf = createSessionFactory(getCacheProperties(),  loader);
    }

    @After
    public void preDestroy() {
        if (sf != null) {
            sf.close();
            sf = null;
        }
        Hazelcast.shutdownAll();
        factory.shutdownAll();
    }

    /**
     * A test that demonstrates a race condition in which a stale value can be put into collection cache post commit.
     * This is caused by an unnecessary invalidate that wipes out some lock from local cache
     *    which could have been used to prevent stale update in the cache.
     * If using an IDE you can configure to run this test repeatedly until failure.
     * On my machine it usually fails once every 10 attempts.
     */
    @Test
    public void testUpdateAndLoadCollectionConcurrently() throws InterruptedException {
        Session session = sf.openSession();
        Transaction transaction = session.beginTransaction();

        DummyEntityWithEmbeddedCollection someEntity = new DummyEntityWithEmbeddedCollection("Some entity");
        someEntity.setAttribute(TEST_ATTRIBUTE_KEY, "old-value");
        long someId = (long) session.save(someEntity);

        transaction.commit();
        session.close();

        // Change the attribute from old-value to new-value
        session = sf.openSession();
        transaction = session.beginTransaction();

        someEntity = session.get(DummyEntityWithEmbeddedCollection.class, someId);
        someEntity.setAttribute(TEST_ATTRIBUTE_KEY, "new-value");

        sf.getCache().evictCollectionRegions();

        // Load the entity in a new transaction asynchronously
        // Since the transaction is yet to be committed, loaded value of the attribute should still be old-value
        CountDownLatch latch = new CountDownLatch(1);
        loadCollectionAsynchronously(latch, someId);

        // Wait a bit before committing transaction
        sleep(10L);
        transaction.commit();
        session.close();

        // Wait until the asynchronous loading finishes
        latch.await(60, TimeUnit.SECONDS);

        // Verify final value of the collection, loaded from 2nd level cache or DB
        session = sf.openSession();
        transaction = session.beginTransaction();

        assertEquals("new-value",
                session.find(DummyEntityWithEmbeddedCollection.class, someId)
                        .getAttributes()
                        .get(TEST_ATTRIBUTE_KEY));

        transaction.commit();
        session.close();
    }

    @Override
    protected void addMappings(Configuration conf) {
        conf.addResource("/hbm/DummyCollectionWithEmbeddedCollection.xml");
    }

    private void loadCollectionAsynchronously(CountDownLatch latch, long id) {
        new Thread(() -> {
            Session session = sf.openSession();
            session.addEventListeners(new DelayingCachePutEventListener());
            Transaction transaction = session.beginTransaction();

            session.find(DummyEntityWithEmbeddedCollection.class, id);

            transaction.commit();
            session.close();

            latch.countDown();
        }).start();
    }

    private Properties getCacheProperties() {
        Properties props = new Properties();
        props.setProperty(Environment.CACHE_REGION_FACTORY, HazelcastLocalCacheRegionFactory.class.getName());
        return props;
    }

    private static class DelayingCachePutEventListener extends BaseSessionEventListener {
        private static final long DELAY_MS = 10L;

        @Override
        public void cachePutStart() {
            try {
                // Add an artificial slowness before putting loaded values to caches
                sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            super.cachePutStart();
        }
    }
}
