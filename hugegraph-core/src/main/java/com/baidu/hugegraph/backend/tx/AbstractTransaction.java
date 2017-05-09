package com.baidu.hugegraph.backend.tx;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.Transaction;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.id.IdGenerator;
import com.baidu.hugegraph.backend.id.IdGeneratorFactory;
import com.baidu.hugegraph.backend.query.IdQuery;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.serializer.AbstractSerializer;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.backend.store.BackendMutation;
import com.baidu.hugegraph.backend.store.BackendStore;
import com.baidu.hugegraph.type.HugeType;
import com.google.common.base.Preconditions;

public abstract class AbstractTransaction implements Transaction {

    private boolean autoCommit;

    // parent graph
    protected final HugeGraph graph;
    protected AbstractSerializer serializer;
    protected IdGenerator idGenerator;

    protected BackendStore store;

    protected Set<BackendEntry> additions;
    protected Set<BackendEntry> deletions;

    private static final Logger logger = LoggerFactory.getLogger(AbstractTransaction.class);

    public AbstractTransaction(HugeGraph graph, BackendStore store) {
        Preconditions.checkNotNull(graph);
        Preconditions.checkNotNull(store);

        this.autoCommit = false;

        this.graph = graph;
        this.serializer = this.graph.serializer();
        this.idGenerator = IdGeneratorFactory.generator();

        this.store = store;
        this.additions = new LinkedHashSet<>();
        this.deletions = new LinkedHashSet<>();
    }

    public HugeGraph graph() {
        return this.graph;
    }

    public BackendStore store() {
        return this.store;
    }

    public Iterable<BackendEntry> query(Query query) {
        logger.debug("Transaction query: {}", query);
        query = this.serializer.writeQuery(query);

        this.beforeRead();
        Iterable<BackendEntry> result = this.store.query(query);
        this.afterRead();

        return result;
    }

    public BackendEntry query(HugeType type, Id id) {
        IdQuery q = new IdQuery(type, id);
        Iterator<BackendEntry> results = this.query(q).iterator();
        if (results.hasNext()) {
            BackendEntry entry = results.next();
            assert !results.hasNext();
            return entry;
        }
        return null;
    }

    public BackendEntry get(HugeType type, Id id) {
        BackendEntry entry = query(type, id);
        if (entry == null) {
            throw new BackendException(String.format(
                    "Not found id '%s' with type %s", id, type));
        }
        return entry;
    }

    @Override
    public void commit() throws BackendException {
        logger.debug("Transaction commit() [auto: {}]...", this.autoCommit);
        this.prepareCommit();

        BackendMutation m = new BackendMutation(this.additions, this.deletions);

        if (m.isEmpty()) {
            logger.debug("Transaction has no data to commit({})", this.store());
            return;
        }

        // if an exception occurred, catch in the upper layer and roll back
        this.store.beginTx();
        this.store.mutate(m);
        this.store.commitTx();

        this.additions.clear();
        this.deletions.clear();
    }

    @Override
    public void rollback() throws BackendException {
        logger.debug("Transaction rollback()...");
        this.store.rollbackTx();
    }

    @Override
    public boolean autoCommit() {
        return this.autoCommit;
    }

    public void autoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public void beforeWrite() {
        // TODO: auto open()
    }

    @Override
    public void afterWrite() {
        if (autoCommit()) {
            this.commitOrRollback();
        }
    }

    @Override
    public void beforeRead() {
        // TODO: auto open()
        if (this.hasUpdates()) {
            this.commitOrRollback();
        }
    }

    @Override
    public void afterRead() {
        // pass
    }

    protected void prepareCommit() {
        // for sub-class preparing data, nothing to do here
        logger.debug("Transaction prepareCommit()...");
    }

    protected void commitOrRollback() {
        logger.debug("Transaction commitOrRollback()");
        boolean success = false;

        try {
            this.commit();
            success = true;
        } catch (BackendException e1) {
            logger.error("Failed to commit changes:", e1);
            try {
                this.rollback();
            } catch (BackendException e2) {
                logger.error("Failed to rollback changes:", e2);
            }
        }

        if (!success) {
            throw new BackendException("Failed to commit changes");
        }
    }

    public void addEntry(BackendEntry entry) {
        logger.debug("Transaction add entry {}", entry);
        Preconditions.checkNotNull(entry);
        Preconditions.checkNotNull(entry.id());

        this.additions.add(entry);
    }

    public void removeEntry(BackendEntry entry) {
        logger.debug("Transaction remove entry {}", entry);
        Preconditions.checkNotNull(entry);
        Preconditions.checkNotNull(entry.id());

        this.deletions.add(entry);
    }

    public void removeEntry(HugeType type, Id id) {
        this.removeEntry(this.serializer.writeId(type, id));
    }

    public boolean hasUpdates() {
        return !this.additions.isEmpty() || !this.deletions.isEmpty();
    }
}