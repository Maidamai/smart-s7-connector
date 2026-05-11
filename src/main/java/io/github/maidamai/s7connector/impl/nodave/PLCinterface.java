

/*
 Part of Libnodave, a free communication libray for Siemens S7

 (C) Thomas Hergenhahn (thomas.hergenhahn@web.de) 2005.

 Libnodave is free software; you can redistribute it and/or modify
 it under the terms of the GNU Library General Public License as published by
 the Free Software Foundation; either version 2, or (at your option)
 any later version.

 Libnodave is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU Library General Public License
 along with this; see the file COPYING.  If not, write to
 the Free Software Foundation, 675 Mass Ave, Cambridge, MA 02139, USA.
*/
package io.github.maidamai.s7connector.impl.nodave;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Thomas Rudin
 */
public final class PLCinterface {
    private static final Logger log = LoggerFactory.getLogger(PLCinterface.class);

    InputStream in;
    int localMPI; // the adapter's MPI address
    String name;

    OutputStream out;
    int protocol; // The kind of transport used on this interface.
    int wp, rp;

    public PLCinterface(final int protocol) {
        this.protocol = protocol;
    }

    public PLCinterface(final OutputStream out, final InputStream in, final String name, final int localMPI,
                        final int protocol) {
        this.init(out, in, name, localMPI, protocol);
    }

    public void init(final OutputStream oStream, final InputStream iStream, final String name, final int localMPI,
                     final int protocol) {
        this.out = oStream;
        this.in = iStream;
        this.name = name;
        this.localMPI = localMPI;
        this.protocol = protocol;
    }

    public int read(final byte[] b, int start, int len) {
        int res;
        try {
            int retry = 0;
            while ((this.in.available() <= 0) && (retry < 800)) {
                try {
                    if (retry > 0) {
                        Thread.sleep(1);
                    }
                    retry++;
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for PLC interface input name={} protocol={} start={} length={}",
                            this.name, this.protocol, start, len, e);
                    return 0;
                }
            }
            res = 0;
            while ((this.in.available() > 0) && (len > 0)) {
                res = this.in.read(b, start, len);
                start += res;
                len -= res;
            }
            return res;
        } catch (final IOException e) {
            log.warn("Failed to read PLC interface input name={} protocol={} start={} length={}",
                    this.name, this.protocol, start, len, e);
            return 0;
        }
    }

    public void write(final byte[] b, final int start, final int len) throws IOException {
        try {
            this.out.write(b, start, len);
        } catch (final IOException e) {
            throw e;
        }
    }

}
