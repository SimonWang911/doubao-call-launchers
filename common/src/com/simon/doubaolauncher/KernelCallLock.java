package com.simon.doubaolauncher;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;

/** Holds an OS-exclusive binding only; never listens, connects or transfers data. */
final class KernelCallLock implements Closeable {
    private static final int COORDINATION_PORT = 39471;
    private FileDescriptor descriptor;
    private KernelCallLock(FileDescriptor descriptor) { this.descriptor = descriptor; }
    static KernelCallLock acquire() throws ErrnoException, IOException {
        FileDescriptor candidate = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);
        try {
            Os.bind(candidate, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), COORDINATION_PORT);
            return new KernelCallLock(candidate);
        } catch (ErrnoException | IOException | RuntimeException error) {
            try { Os.close(candidate); } catch (ErrnoException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
    @Override public void close() throws IOException {
        if (descriptor == null) return;
        try { Os.close(descriptor); descriptor = null; }
        catch (ErrnoException error) { throw new IOException("Could not release call control", error); }
    }
}
