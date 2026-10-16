package android.system;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.util.IdentityHashMap;
public final class Os {
    private static final IdentityHashMap<FileDescriptor,Boolean> open = new IdentityHashMap<>();
    private static FileDescriptor bound;
    public static int socketError, bindError, closeError;
    public static boolean denyBind;
    public static FileDescriptor socket(int domain, int type, int protocol) throws ErrnoException {
        if (socketError != 0) { int e=socketError; socketError=0; throw new ErrnoException("socket",e); }
        FileDescriptor fd = new FileDescriptor(); open.put(fd,true); return fd;
    }
    public static void bind(FileDescriptor fd, InetAddress address, int port) throws ErrnoException {
        if (!address.isLoopbackAddress()) throw new AssertionError("Control lock must bind loopback only");
        if (denyBind) { denyBind=false; throw new SecurityException("denied"); }
        if (bindError != 0) { int e=bindError; bindError=0; throw new ErrnoException("bind",e); }
        if (bound != null) throw new ErrnoException("bind",OsConstants.EADDRINUSE);
        bound=fd;
    }
    public static void close(FileDescriptor fd) throws ErrnoException {
        if (closeError != 0) { int e=closeError; closeError=0; throw new ErrnoException("close",e); }
        open.remove(fd); if (bound==fd) bound=null;
    }
    public static int openCount() { return open.size(); }
}
