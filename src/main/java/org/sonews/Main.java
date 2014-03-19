/*
 *   SONEWS News Server
 *   see AUTHORS for the list of contributors
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sonews;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Date;
import java.util.Enumeration;
import java.util.logging.Level;

import org.sonews.config.Config;
import org.sonews.daemon.ChannelLineBuffers;
import org.sonews.daemon.CommandSelector;
import org.sonews.daemon.Connections;
import org.sonews.daemon.NNTPDaemon;
import org.sonews.feed.FeedManager;
import org.sonews.storage.StorageManager;
import org.sonews.storage.StorageProvider;
import org.sonews.util.Log;
import org.sonews.util.Purger;
import org.sonews.util.io.Resource;

/**
 * Startup class of the daemon.
 * 
 * @author Christian Lins
 * @since sonews/0.5.0
 */
public final class Main {

    /** Version information of the sonews daemon */
    public static final String VERSION = "sonews/2.0.0";

    /** The server's startup date */
    public static final Date STARTDATE = new Date();

    /**
     * The main entrypoint.
     * 
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        System.out.println(VERSION);
        Thread.currentThread().setName("Mainthread");

        // Command line arguments
        boolean feed = false; // Enable feeding?
        boolean purger = false; // Enable message purging?
        int port = -1;

        for (int n = 0; n < args.length; n++) {
            switch (args[n]) {
                case "-c":
                case "-config": {
                    Config.inst().set(Config.LEVEL_CLI, Config.CONFIGFILE,
                            args[++n]);
                    System.out.println("Using config file " + args[n]);
                    break;
                }
                case "-dumpjdbcdriver": {
                    System.out.println("Available JDBC drivers:");
                    Enumeration<Driver> drvs = DriverManager.getDrivers();
                    while (drvs.hasMoreElements()) {
                        System.out.println(drvs.nextElement());
                    }
                    return;
                }
                case "-feed": {
                    feed = true;
                    break;
                }
                case "-h":
                case "-help": {
                    printArguments();
                    return;
                }
                case "-p": {
                    port = Integer.parseInt(args[++n]);
                    break;
                }
                case "-plugin-storage": {
                    System.out
                            .println("Warning: -plugin-storage is not implemented!");
                    break;
                }
                case "-plugin-command": {
                    try {
                        CommandSelector.addCommandHandler(args[++n]);
                    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
                        StringBuilder strBuf = new StringBuilder();
                        strBuf.append("Could not load command plugin: ");
                        strBuf.append(args[n]);
                        Log.get().warning(strBuf.toString());
                        Log.get().log(Level.INFO, "Main.java", ex);
                    }   
                    break;
                }
                case "-purger": {
                    purger = true;
                    break;
                }
                case "-v":
                case "-version":
                    // Simply return as the version info is already printed above
                    return;
            }
        }

        // Load the storage backend
        String provName = Config.inst().get(Config.LEVEL_FILE,
                    Config.STORAGE_PROVIDER,
                    "org.sonews.storage.impl.CouchDBStorageProvider");
        StorageProvider sprov = StorageManager.loadProvider(provName);
        StorageManager.enableProvider(sprov);

        ChannelLineBuffers.allocateDirect();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new ShutdownHook());

        // Start the listening daemon
        if (port <= 0) {
            port = Config.inst().get(Config.PORT, 119);
        }
        final NNTPDaemon daemon = NNTPDaemon.createInstance(port);
        daemon.start();

        // Start Connections purger thread...
        Connections.getInstance().start();

        // Start feeds
        if (feed) {
            FeedManager.startFeeding();
        }

        if (purger) {
            Purger purgerDaemon = new Purger();
            purgerDaemon.start();
        }

        // Wait for main thread to exit (setDaemon(false))
        daemon.join();
    }

    private static void printArguments() {
        String usage = Resource.getAsString("helpers/usage", true);
        System.out.println(usage);
    }

    private Main() {
    }
}
