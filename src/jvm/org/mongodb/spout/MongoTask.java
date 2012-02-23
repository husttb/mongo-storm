package org.mongodb.spout;

import com.mongodb.*;
import org.apache.log4j.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

// We need to handle the actual messages in an internal thread to ensure we never block, so we will be using a non blocking queue between the
// driver and the db
class MongoTask implements Callable<Boolean>, Runnable {
    static Logger LOG = Logger.getLogger(MongoTask.class);
    private ConcurrentLinkedQueue<DBObject> queue;
    private Mongo mongo;
    private DB db;
    private DBCollection collection;
    private DBCursor cursor;


    // Keeps the running state
    private AtomicBoolean running = new AtomicBoolean(true);
    private String[] collectionNames;
    private DBObject query;

    public void stopThread() {
        running.set(false);
    }

    public MongoTask(ConcurrentLinkedQueue<DBObject> queue, Mongo mongo, DB db, String[] collectionNames, DBObject query) {
        this.queue = queue;
        this.mongo = mongo;
        this.db = db;
        this.collectionNames = collectionNames;
        this.query = query == null ? new BasicDBObject() : query;
    }

    @Override
    public Boolean call() throws Exception {
        String collectionName = locateValidOpCollection(collectionNames);
        if(collectionName == null) throw new Exception("Could not locate any of the collections provided");
        // Set up the collection
        this.collection = this.db.getCollection(collectionName);
        // provide the query object
        this.cursor = this.collection.find(query)
                .sort(new BasicDBObject("$natural", 1))
                .addOption(Bytes.QUERYOPTION_TAILABLE)
                .addOption(Bytes.QUERYOPTION_TAILABLE);

        // While the thread is set to running
        while(running.get()) {
            // Check if we have a next item in the collection
            if(this.cursor.hasNext()) {
                if(LOG.isInfoEnabled()) LOG.info("Fetching a new item from MongoDB cursor");
                // Fetch the next object and push it on the queue
                this.queue.add(this.cursor.next());
            } else {
                // Sleep for 50 ms and then wake up
                Thread.sleep(50);
            }
        }

        // Dummy return
        return true;
    }

    private String locateValidOpCollection(String[] collectionNames) {
        // Find a valid collection (used for oplogs etc)
        String collectionName = null;
        for (int i = 0; i < collectionNames.length; i++) {
            String name = collectionNames[i];
            // Attempt to read from the collection
            DBCollection collection = this.db.getCollection(name);
            // Attempt to find the last item in the collection
            DBCursor lastCursor = collection.find().sort( new BasicDBObject( "$natural" , -1 ) ).limit(1);
            if (lastCursor.hasNext()){
                collectionName = name;
                break;
            }
        }
        // return the collection name
        return collectionName;
    }

    @Override
    public void run() {
        try {
            call();
        } catch (Exception e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }
}