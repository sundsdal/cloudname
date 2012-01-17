package org.cloudname.zk;

import org.cloudname.*;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.KeeperException;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import java.util.concurrent.CountDownLatch;

import java.io.IOException;


/**
 * An implementation of Cloudname using ZooKeeper.
 *
 * This implementation assumes that the path prefix defined by
 * CN_PATH_PREFIX is only used by Cloudname.  The structure and
 * semantics of things under this prefix are defined by this library
 * and will be subject to change.
 *
 * TODO(borud):
 *
 *  - We need a recovery mechanism for when the ZK server we are
 *    connected to goes down.
 *
 *  - when the ZkCloudname instance is releaseClaim()d the handles should
 *    perhaps be invalidated.
 *
 *  - The exception handling in this class is just atrocious.
 *
 * @author borud
 */
public class ZkCloudname implements Cloudname, Watcher {

    private static final int SESSION_TIMEOUT = 5000;

    private static final Logger log = Logger.getLogger(ZkCloudname.class.getName());

    // Instance variables
    private ZooKeeper zk;
    private final String connectString;

    // Latches that count down when ZooKeeper is connected
    private final CountDownLatch connectedSignal = new CountDownLatch(1);


    private ZkCloudname(Builder builder) {
        connectString = builder.getConnectString();
    }

    public class ZooKeeperKeeper {
        private ZooKeeper localZooKeeper;
        
        public ZooKeeperKeeper(ZooKeeper zooKeeper) {
            this.localZooKeeper = zooKeeper;
        }

        public synchronized ZooKeeper getZooKeeper() {
            return localZooKeeper;
        }

        // TODO To coursed grain lock
        public synchronized boolean reconnect() {

            if (localZooKeeper.getState() == ZooKeeper.States.CONNECTED) {
                log.info("Asked to reconnect, don't bother, I am already connected.");
                return true;
            } else {
                // Grab the latest zookeeper instance.
                localZooKeeper = zk;
            }
            if (localZooKeeper.getState() == ZooKeeper.States.CONNECTED) {
                // Already recreated connection by some other process, just using that.
                return true;
            }
            connect();
            localZooKeeper = zk;
            if (zk.getState() == ZooKeeper.States.CONNECTED) {
                log.info("Managed to reconnect to ZooKeeper.");
                return true;
            }
            log.info("Could not reconnect");
            return false;
        }
    }
    
    /**
     * Connect to ZooKeeper instance with time-out value.
     * @param waitTime time-out value for establishing connection.
     * @param waitUnit time unit for time-out when establishing connection.
     * @throws org.cloudname.CloudnameException.CouldNotConnectToStorage if connection can not be established
     * @return
     */
    public ZkCloudname connectWithTimeout(long waitTime, TimeUnit waitUnit) {

        try {
            zk = new ZooKeeper(connectString, SESSION_TIMEOUT, this);
            if (! connectedSignal.await(waitTime, waitUnit)) {
                throw new CloudnameException.CouldNotConnectToStorage(
                        "Connecting to ZooKeeper timed out.");
            }
            log.info("Connected to ZooKeeper " + connectString);
        } catch (IOException e) {
            throw new CloudnameException.CouldNotConnectToStorage(e);
        } catch (InterruptedException e) {
            throw new CloudnameException.CouldNotConnectToStorage(e);
        }

        return this;
    }

    /**
     * Connect to ZooKeeper instance with long time-out, however, it might fail fast.
     * @return
     */
    public ZkCloudname connect() {
        // We wait up to 100 years.
        return connectWithTimeout(365 * 100, TimeUnit.DAYS);
    }

    /**
     * When calling this function, the zookeeper state should be either connected or closed.
     * @return
     */
    public synchronized boolean resolveConnectionProblems() {
        switch (zk.getState()) {

            case CONNECTING:
            case ASSOCIATING:
            case AUTH_FAILED:
                throw new RuntimeException("ZooKeeper in wrong state, giving up." + zk.toString());
            case CONNECTED:
                return true;
            case CLOSED:
                break;
            default:
                throw new RuntimeException("ZooKeeper in unknown unexpected state, giving up." + zk.toString());
        }
        // TODO(borud, dybdahl): Make this timeout configurable.
        try {
            connectWithTimeout(10, TimeUnit.MINUTES);
        } catch (CloudnameException.CouldNotConnectToStorage e)  {
            return false;
        }
        return true;
    }

    public ZooKeeper getZooKeeper() {
        return zk;
    }

    @Override
    public void process(WatchedEvent event) {
        log.fine("Got event " + event.toString());

        // Initial connection to ZooKeeper is completed.
        if (event.getState() == Event.KeeperState.SyncConnected) {
            if (connectedSignal.getCount() == 0) {
                // I am not sure if this can ever occur, but until I
                // know I'll just leave this log message in here.
                log.info("The connectedSignal count was already zero.  Duplicate Event.KeeperState.SyncConnected");
            }
            connectedSignal.countDown();
        }
    }

    /**
     * Create a given coordinate in the ZooKeeper node tree.
     *
     * Just blindly creates the entire path.  Elements of the path may
     * exist already, but it seems wasteful to
     */
    @Override
    public void createCoordinate(Coordinate coordinate) {
        // Create the root path for the coordinate.  We do this
        // blindly, meaning that if the path already exists, then
        // that's ok -- so a more correct name for this method would
        // be ensureCoordinate(), but that might confuse developers.
        String root = ZkCoordinatePath.getRoot(coordinate);
        try {
            Util.mkdir(zk, root, Ids.OPEN_ACL_UNSAFE);
        } catch (KeeperException e) {
            throw new RuntimeException(e);
        }

        // Create the nodes that represent subdirectories.
        String configPath = ZkCoordinatePath.getConfigPath(coordinate, null);
        try {
            log.info("Creating config node " + configPath);
            zk.create(configPath, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            throw new CloudnameException(e);
        } catch (InterruptedException e) {
            throw new CloudnameException(e);
        }
    }

    /**
     * Claim a coordinate.
     *
     * In this implementation a coordinate is claimed by creating an
     * ephemeral with the name defined in CN_STATUS_NAME.  If the node
     * already exists the coordinate has already been claimed.
     */
    @Override
    public ServiceHandle claim(Coordinate coordinate) {
        String statusPath = ZkCoordinatePath.getStatusPath(coordinate);
        log.info("Claiming " + coordinate.asString() + " (" + statusPath + ")");

        ZkStatusAndEndpoints statusAndEndpoints = new ZkStatusAndEndpoints.Builder(new ZooKeeperKeeper(zk), statusPath).build().claim();
        // If we have come thus far we have succeeded in creating the
        // CN_STATUS_NAME node within the service coordinate directory
        // in ZooKeeper and we can give the client a ServiceHandle.

        return new ZkServiceHandle(coordinate, statusAndEndpoints);
    }
    
    @Override
    public Resolver getResolver() {
        return new ZkResolver.Builder(new ZooKeeperKeeper(zk)).addStrategy(new StrategyAll()).addStrategy(new StrategyAny()).build();
    }

    @Override
    public ServiceStatus getStatus(Coordinate coordinate) {
        String statusPath = ZkCoordinatePath.getStatusPath(coordinate);
        ZkStatusAndEndpoints statusAndEndpoints = new ZkStatusAndEndpoints.Builder(new ZooKeeperKeeper(zk), statusPath).build().load();
        return statusAndEndpoints.getServiceStatus();
    }

    /**
     * Close the connection to ZooKeeper.
     */
    public void close() {
        if (null == zk) {
            throw new IllegalStateException("Cannot releaseClaim(): Not connected to ZooKeeper");
        }

        try {
            zk.close();
            log.info("ZooKeeper session closed for " + connectString);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     *  This class builds parameters for ZkCloudname.
     */
    static class Builder {
        private String connectString;

        public Builder setConnectString(String connectString) {
            this.connectString = connectString;
            return this;
        }

        // TODO(borud, dybdahl): Make this smarter, some ideas:
        //                       Connect to one node and read from a magic path
        //                       how many zookeepers that are running and build
        //                       the path based on this information.
        public Builder autoConnect() {
            this.connectString = "z1:2181,z2:2181,z3:2181";
            return this;
        }

        public String getConnectString() {
            return connectString;
        }

        public ZkCloudname build() {
            if (connectString.isEmpty()) {
                throw new RuntimeException("You need to specify connection string before you can build.");
            }
            return new ZkCloudname(this);
        }
    }
}