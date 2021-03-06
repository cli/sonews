/*
 *   SONEWS News Server
 *   Copyright (C) 2009-2015  Christian Lins <christian@lins.me>
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

package org.sonews.daemon.sync;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;

import org.sonews.daemon.Connections;
import org.sonews.daemon.DaemonRunner;
import org.sonews.daemon.NNTPConnection;
import org.sonews.daemon.SocketChannelWrapperFactory;
import org.sonews.util.Log;

/**
 * A Thread task listening for OP_READ events from SocketChannels.
 *
 * @author Christian Lins
 * @since sonews/0.5.0
 */
class ChannelReader extends DaemonRunner {

    private static final ChannelReader instance = new ChannelReader();

    /**
     * @return Active ChannelReader instance.
     */
    public static ChannelReader getInstance() {
        return instance;
    }

    private Selector selector = null;

    protected ChannelReader() {
    }

    /**
     * Sets the selector which is used by this reader to determine the channel
     * to read from.
     *
     * @param selector
     */
    public void setSelector(final Selector selector) {
        this.selector = selector;
    }

    /**
     * Run loop. Blocks until some data is available in a channel.
     */
    @Override
    public void run() {
        assert selector != null;

        while (daemon.isRunning()) {
            try {
                // select() blocks until some SelectableChannels are ready for
                // processing. There is no need to lock the selector as we have
                // only
                // one thread per selector.
                selector.select();

                // Get list of selection keys with pending events.
                // Note: the selected key set is not thread-safe
                SocketChannel channel = null;
                NNTPConnection conn = null;
                final Set<SelectionKey> selKeys = selector.selectedKeys();
                SelectionKey selKey = null;

                synchronized (selKeys) {
                    Iterator<SelectionKey> it = selKeys.iterator();

                    // Process the first pending event
                    while (it.hasNext()) {
                        selKey = it.next();
                        channel = (SocketChannel) selKey.channel();
                        conn = Connections.getInstance().get(
                                new SocketChannelWrapperFactory(channel).create());

                        // Because we cannot lock the selKey as that would cause
                        // a deadlock
                        // we lock the connection. To preserve the order of the
                        // received
                        // byte blocks a selection key for a connection that has
                        // pending
                        // read events is skipped.
                        if (conn == null || conn.tryReadLock()) {
                            // Remove from set to indicate that it's being
                            // processed
                            it.remove();
                            if (conn != null) {
                                break; // End while loop
                            }
                        } else {
                            selKey = null;
                            channel = null;
                            conn = null;
                        }
                    }
                }

                // Do not lock the selKeys while processing because this causes
                // a deadlock in sun.nio.ch.SelectorImpl.lockAndDoSelect()
                if (selKey != null && channel != null && conn != null) {
                    processSelectionKey(conn, channel, selKey);
                    conn.unlockReadLock();
                }

            } catch (CancelledKeyException ex) {
                Log.get().log(Level.WARNING, "ChannelReader.run(): {0}", ex);
                Log.get().log(Level.INFO, "", ex);
            } catch (IOException | InterruptedException ex) {
                Log.get().log(Level.WARNING, ex.getLocalizedMessage(), ex);
            }

            // Eventually wait for a register operation
            synchronized (SynchronousNNTPDaemon.RegisterGate) {
                // Do nothing; FindBugs may warn about an empty synchronized
                // statement, but we cannot use a wait()/notify() mechanism
                // here.
                // If we used something like RegisterGate.wait() we block here
                // until the NNTPDaemon calls notify(). But the daemon only
                // calls notify() if itself is NOT blocked in the listening
                // socket.
            }
        } // while(isRunning())
    }

    private void processSelectionKey(final NNTPConnection connection,
            final SocketChannel socketChannel, final SelectionKey selKey)
            throws InterruptedException, IOException {
        assert selKey != null;
        assert selKey.isReadable();

        // Some bytes are available for reading
        if (selKey.isValid()) {
            // Lock the channel
            // synchronized(socketChannel)
            {
                // Read the data into the appropriate buffer
                ByteBuffer buf = connection.getInputBuffer();
                int read = -1;
                try {
                    read = socketChannel.read(buf);
                } catch (IOException ex) {
                    // The connection was probably closed by the remote host
                    // in a non-clean fashion
                    Log.get().log(Level.INFO,
                            "ChannelReader.processSelectionKey(): {0}", ex);
                } catch (Exception ex) {
                    Log.get().log(Level.WARNING,
                            "ChannelReader.processSelectionKey(): {0}", ex);
                }

                if (read == -1) { // End of stream
                    selKey.cancel();
                } else if (read > 0) { // If some data was read
                    ConnectionWorker.addChannel(socketChannel);
                }
            }
        } else {
            // Should not happen
            Log.get().log(Level.SEVERE, "Should not happen: {0}", selKey.toString());
        }
    }
}
