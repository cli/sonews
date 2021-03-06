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
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import org.sonews.acl.User;
import org.sonews.daemon.ChannelLineBuffers;
import org.sonews.daemon.CommandSelector;
import org.sonews.daemon.LineEncoder;
import org.sonews.daemon.NNTPConnection;
import org.sonews.daemon.SocketChannelWrapper;
import org.sonews.daemon.command.Command;
import org.sonews.storage.Article;
import org.sonews.storage.Group;
import org.sonews.storage.StorageBackendException;
import org.sonews.util.Log;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * For every SocketChannel (so TCP/IP connection) there is an instance of this
 * class.
 *
 * @author Christian Lins
 * @since sonews/0.5.0
 */
@Component
public class SynchronousNNTPConnection implements NNTPConnection {

    public static final String NEWLINE = "\r\n"; // RFC defines this as newline
    public static final String MESSAGE_ID_PATTERN = "<[^>]+>";
    private static final Timer cancelTimer = new Timer(true); // Thread-safe?
                                                              // True for run as
                                                              // daemon
    /** SocketChannel is generally thread-safe */
    private SocketChannel channel;
    
    private SocketChannelWrapper channelWrapper;
    private Charset charset = Charset.forName("UTF-8");
    private Command command = null;
    
    @Autowired
    private ApplicationContext context;
    
    private Article currentArticle = null;
    private Group currentGroup = null;
    private volatile long lastActivity = System.currentTimeMillis();
    private final ChannelLineBuffers lineBuffers = new ChannelLineBuffers();
    private int readLock = 0;
    private final Object readLockGate = new Object();
    private SelectionKey writeSelKey = null;
    private User user;

    public SynchronousNNTPConnection() {
    }
    
    public void setChannelWrapper(SocketChannelWrapper channelWrapper)
            throws IOException
    {
        if (channelWrapper == null) {
            throw new IllegalArgumentException("channel is null");
        }

        this.channelWrapper = channelWrapper;
        Object wrapier = channelWrapper.getWrapier();
        if (!(wrapier instanceof SocketChannel)) {
            throw new IllegalArgumentException("channel is not a SocketChannel");
        }
        this.channel = (SocketChannel)channelWrapper.getWrapier();
    }

    /**
     * Tries to get the read lock for this NNTPConnection. This method is
     * Thread-safe and returns true of the read lock was successfully set. If
     * the lock is still hold by another Thread the method returns false.
     * @return true if lock could be aquired
     */
    @Override
    public boolean tryReadLock() {
        // As synchronizing simple types may cause deadlocks,
        // we use a gate object.
        synchronized (readLockGate) {
            if (readLock != 0) {
                return false;
            } else {
                readLock = Thread.currentThread().hashCode();
                return true;
            }
        }
    }

    /**
     * Releases the read lock in a Thread-safe way.
     *
     * @throws IllegalMonitorStateException
     *             if a Thread not holding the lock tries to release it.
     */
    @Override
    public void unlockReadLock() {
        synchronized (readLockGate) {
            if (readLock == Thread.currentThread().hashCode()) {
                readLock = 0;
            } else {
                throw new IllegalMonitorStateException();
            }
        }
    }

    /**
     * @return Current input buffer of this NNTPConnection instance.
     */
    @Override
    public ByteBuffer getInputBuffer() {
        return this.lineBuffers.getInputBuffer();
    }

    /**
     * @return Output buffer of this NNTPConnection which has at least one byte
     *         free storage.
     */
    @Override
    public ByteBuffer getOutputBuffer() {
        return this.lineBuffers.getOutputBuffer();
    }

    /**
     * @return ChannelLineBuffers instance associated with this NNTPConnection.
     */
    @Override
    public ChannelLineBuffers getBuffers() {
        return this.lineBuffers;
    }

    /**
     * @return true if this connection comes from a local remote address.
     */
    public boolean isLocalConnection() {
        return "localhost".equalsIgnoreCase(((InetSocketAddress) this.channel.socket()
                .getRemoteSocketAddress()).getHostName());
    }

    void setWriteSelectionKey(SelectionKey selKey) {
        this.writeSelKey = selKey;
    }

    private void shutdownInput() {
        try {
            // Closes the input line of the channel's socket, so no new data
            // will be received and a timeout can be triggered.
            this.channel.socket().shutdownInput();
        } catch (IOException ex) {
            Log.get().log(Level.WARNING,
                    "Exception in NNTPConnection.shutdownInput(): {0}", ex);
        }
    }

    private void shutdownOutput() {
        cancelTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    // Closes the output line of the channel's socket.
                    channel.socket().shutdownOutput();
                    channel.close();
                } catch (SocketException ex) {
                    // Socket was already disconnected
                    Log.get().log(Level.INFO,
                            "NNTPConnection.shutdownOutput(): {0}", ex);
                } catch (IOException ex) {
                    Log.get().log(Level.WARNING,
                            "NNTPConnection.shutdownOutput(): {0}", ex);
                }
            }
        }, 3000);
    }

    @Override
    public SocketChannelWrapper getSocketChannel() {
        return this.channelWrapper;
    }

    @Override
    public Article getCurrentArticle() {
        return this.currentArticle;
    }

    @Override
    public Charset getCurrentCharset() {
        return this.charset;
    }

    @Override
    public Group getCurrentGroup() {
        return this.currentGroup;
    }

    @Override
    public void setCurrentArticle(final Article article) {
        this.currentArticle = article;
    }

    @Override
    public void setCurrentGroup(final Group group) {
        this.currentGroup = group;
    }

    @Override
    public long getLastActivity() {
        return this.lastActivity;
    }

    /**
     * Due to the readLockGate there is no need to synchronize this method.
     *
     * @param raw
     * @throws IllegalArgumentException
     *             if raw is null.
     * @throws IllegalStateException
     *             if calling thread does not own the readLock.
     */
    @Override
    public void lineReceived(byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw is null");
        }

        if (readLock == 0 || readLock != Thread.currentThread().hashCode()) {
            throw new IllegalStateException("readLock not properly set");
        }

        this.lastActivity = System.currentTimeMillis();

        String line = new String(raw, this.charset);

        // There might be a trailing \r, but trim() is a bad idea
        // as it removes also leading spaces from long header lines.
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
            raw = Arrays.copyOf(raw, raw.length - 1);
        }

        Log.get().log(Level.FINE, "<< {0}", line);

        if (command == null) {
            command = parseCommandLine(line);
            assert command != null;
        }

        try {
            // The command object will process the line we just received
            try {
                command.processLine(this, line, raw);
            } catch (StorageBackendException ex) {
                Log.get()
                        .info("Retry command processing after StorageBackendException");

                // Try it a second time, so that the backend has time to recover
                command.processLine(this, line, raw);
            }
        } catch (ClosedChannelException ex0) {
            try {
                StringBuilder strBuf = new StringBuilder();
                strBuf.append("Connection to ");
                strBuf.append(channel.socket().getRemoteSocketAddress());
                strBuf.append(" closed: ");
                strBuf.append(ex0);
                Log.get().info(strBuf.toString());
            } catch (Exception ex0a) {
                Log.get().log(Level.INFO, ex0a.getLocalizedMessage(), ex0a);
            }
        } catch (IOException ex1) {
            // This will catch a second StorageBackendException
            command = null;
            Log.get().log(Level.WARNING, ex1.getLocalizedMessage(), ex1);
            println("403 Internal server error");

            // Should we end the connection here?
            // RFC says we MUST return 400 before closing the connection
            shutdownInput();
            shutdownOutput();
        }

        if (command == null || command.hasFinished()) {
            command = null;
            charset = Charset.forName("UTF-8"); // Reset to default
        }
    }

    /**
     * This method determines the fitting command processing class.
     *
     * @param line
     * @return
     */
    private Command parseCommandLine(String line) {
        String cmdStr = line.trim().split("\\s+")[0];
        CommandSelector csel = context.getBean(CommandSelector.class);
        return csel.get(cmdStr);
    }

    /**
     * Puts the given line into the output buffer, adds a newline character and
     * returns. The method returns immediately and does not block until the line
     * was sent. If line is longer than 510 octets it is split up in several
     * lines. Each line is terminated by \r\n (NNTPConnection.NEWLINE).
     *
     * @param line
     * @param charset
     * @throws java.io.IOException
     */
    public void println(final CharSequence line, final Charset charset)
            throws IOException {
        writeToChannel(CharBuffer.wrap(line), charset, line);
        writeToChannel(CharBuffer.wrap(NEWLINE), charset, null);
    }

    /**
     * Writes the given raw lines to the output buffers and finishes with a
     * newline character (\r\n).
     *
     * @param rawLines
     * @throws java.io.IOException
     */
    @Override
    public void println(final byte[] rawLines) throws IOException {
        this.lineBuffers.addOutputBuffer(ByteBuffer.wrap(rawLines));
        writeToChannel(CharBuffer.wrap(NEWLINE), charset, null);
    }

    /**
     * Encodes the given CharBuffer using the given Charset to a bunch of
     * ByteBuffers (each 512 bytes large) and enqueues them for writing at the
     * connected SocketChannel.
     *
     * @throws java.io.IOException
     */
    private void writeToChannel(CharBuffer characters, final Charset charset,
            CharSequence debugLine) throws IOException {
        if (!charset.canEncode()) {
            Log.get().log(Level.SEVERE, "FATAL: Charset {0} cannot encode!",
                    charset);
            return;
        }

        // Write characters to output buffers
        LineEncoder lenc = new LineEncoder(characters, charset);
        lenc.encode(lineBuffers);

        enableWriteEvents(debugLine);
    }

    private void enableWriteEvents(CharSequence debugLine) {
        // Enable OP_WRITE events so that the buffers are processed
        try {
            this.writeSelKey.interestOps(SelectionKey.OP_WRITE);
            ChannelWriter.getInstance().getSelector().wakeup();
        } catch (Exception ex) // CancelledKeyException and
                               // ChannelCloseException
        {
            Log.get().log(Level.WARNING,
                    "NNTPConnection.writeToChannel(): {0}", ex);
            return;
        }

        // Update last activity timestamp
        this.lastActivity = System.currentTimeMillis();
        if (debugLine != null) {
            Log.get().log(Level.FINE, ">> {0}", debugLine);
        }
    }

    @Override
    public void println(final CharSequence line) {
        try {
            println(line, charset);
        } catch (IOException ex) {
            Log.get().log(Level.SEVERE, null, ex);
            shutdownInput();
            shutdownOutput();
        }
    }

    public void print(final String line) throws IOException {
        writeToChannel(CharBuffer.wrap(line), charset, line);
    }

    public void setCurrentCharset(final Charset charset) {
        this.charset = charset;
    }

    @Override
    public void setLastActivity(long timestamp) {
        this.lastActivity = timestamp;
    }

    /**
     * @return Currently logged user (but you should check
     *         {@link User#isAuthenticated()}, if user is athenticated, or we
     *         just trust him)
     */
    @Override
    public User getUser() {
        return user;
    }

    /**
     * This method is to be called from AUTHINFO USER Command implementation.
     *
     * @param user username from AUTHINFO USER username.
     */
    @Override
    public void setUser(User user) {
        this.user = user;
    }

    @Override
    public void close() throws IOException {
        shutdownInput();
        shutdownOutput();
    }
}
