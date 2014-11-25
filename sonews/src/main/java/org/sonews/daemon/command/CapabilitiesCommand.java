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

package org.sonews.daemon.command;

import java.io.IOException;
import org.sonews.daemon.NNTPConnection;

/**
 * <pre>
 *  The CAPABILITIES command allows a client to determine the
 *  capabilities of the server at any given time.
 *
 *  This command MAY be issued at any time; the server MUST NOT require
 *  it to be issued in order to make use of any capability. The response
 *  generated by this command MAY change during a session because of
 *  other state information (which, in turn, may be changed by the
 *  effects of other commands or by external events).  An NNTP client is
 *  only able to get the current and correct information concerning
 *  available capabilities at any point during a session by issuing a
 *  CAPABILITIES command at that point of that session and processing the
 *  response.
 * </pre>
 *
 * @author Christian Lins
 * @since sonews/0.5.0
 */
public class CapabilitiesCommand implements Command {

    static final String[] CAPABILITIES = new String[] { "VERSION 2", // MUST be
                                                                     // the
                                                                     // first
                                                                     // one;
                                                                     // VERSION
                                                                     // 2 refers
                                                                     // to
                                                                     // RFC3977
            "READER", // Server implements commands for reading
            "POST", // Server implements POST command
            "OVER" // Server implements OVER command
    };

    @Override
    public String[] getSupportedCommandStrings() {
        return new String[] { "CAPABILITIES" };
    }

    /**
     * First called after one call to processLine().
     *
     * @return
     */
    @Override
    public boolean hasFinished() {
        return true;
    }

    @Override
    public String impliedCapability() {
        return null;
    }

    @Override
    public boolean isStateful() {
        return false;
    }

    @Override
    public void processLine(NNTPConnection conn, final String line, byte[] raw)
            throws IOException {
        conn.println("101 Capabilities list:");
        for (String cap : CAPABILITIES) {
            conn.println(cap);
        }
        conn.println(".");
    }
}