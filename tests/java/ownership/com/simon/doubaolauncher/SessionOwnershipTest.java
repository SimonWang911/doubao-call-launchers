package com.simon.doubaolauncher;
import android.system.Os;
import android.system.OsConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public final class SessionOwnershipTest {
    private static int checks;
    private static void check(boolean value,String reason) { checks++; if(!value)throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        check(SessionOwnership.acquire(null)==SessionOwnership.Acquisition.UNAVAILABLE,"null token rejected");
        check(SessionOwnership.acquire("")==SessionOwnership.Acquisition.UNAVAILABLE,"empty token rejected");
        check(SessionOwnership.acquire("one")==SessionOwnership.Acquisition.ACQUIRED,"standalone acquisition");
        check(SessionOwnership.isCurrent("one"),"current identity");
        check(!SessionOwnership.isCurrent("old"),"old identity rejected");
        check(SessionOwnership.acquire("two")==SessionOwnership.Acquisition.BUSY,"same-process exclusion");
        check(SessionOwnership.releaseUnclaimed("one"),"unadopted preparation can cancel");
        check(Os.openCount()==0,"unadopted cancellation closes resource");
        check(SessionOwnership.acquire("two")==SessionOwnership.Acquisition.ACQUIRED,"acquire after cancellation");
        check(!SessionOwnership.adopt("one"),"late prepare rejected");
        check(SessionOwnership.adopt("two"),"service adoption");
        check(!SessionOwnership.releaseUnclaimed("two"),"activity cannot release service-owned preparation");
        check(SessionOwnership.isCurrent("two"),"adopted owner survives activity teardown");
        check(!SessionOwnership.release("one"),"stale service cannot release new session");
        check(SessionOwnership.release("two"),"service completion releases");
        check(!SessionOwnership.release("two"),"duplicate release harmless");
        check(!SessionOwnership.adopt("two"),"late service cannot adopt released session");
        try (KernelCallLock otherProcess = KernelCallLock.acquire()) {
            check(SessionOwnership.acquire("busy")==SessionOwnership.Acquisition.BUSY,"kernel exclusion classified busy");
            check(Os.openCount()==1,"failed candidate closed, other owner retained");
        }
        check(SessionOwnership.acquire("after")==SessionOwnership.Acquisition.ACQUIRED,"kernel release permits next owner");
        check(SessionOwnership.release("after"),"release after contention");
        Os.bindError=OsConstants.EACCES;
        check(SessionOwnership.acquire("denied")==SessionOwnership.Acquisition.UNAVAILABLE,"permission error never reported busy");
        check(Os.openCount()==0,"failed bind closes candidate");
        Os.socketError=OsConstants.EIO;
        check(SessionOwnership.acquire("io")==SessionOwnership.Acquisition.UNAVAILABLE,"socket error terminal");
        check(!SessionOwnership.isCurrent("io"),"failed acquisition creates no owner");
        Os.denyBind=true;
        check(SessionOwnership.acquire("security")==SessionOwnership.Acquisition.UNAVAILABLE,"runtime platform denial terminal");
        check(Os.openCount()==0,"runtime denial closes candidate");
        check(SessionOwnership.acquire("close")==SessionOwnership.Acquisition.ACQUIRED,"close-error setup");
        Os.closeError=OsConstants.EIO;
        check(!SessionOwnership.release("close"),"close failure is not reported success");
        check(!SessionOwnership.isCurrent("close"),"released identity revoked even when close fails");
        Os.closeError=OsConstants.EIO;
        check(SessionOwnership.acquire("next")==SessionOwnership.Acquisition.UNAVAILABLE,"cleanup error is unavailable, never active busy");
        check(SessionOwnership.acquire("next")==SessionOwnership.Acquisition.ACQUIRED,"next request cleans ended resource before acquisition");
        check(!SessionOwnership.release("close"),"late cleanup cannot close replacement owner");
        check(SessionOwnership.isCurrent("next"),"replacement survives late release");
        check(SessionOwnership.release("next"),"replacement cleanup");
        List<String> winners=Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads=new ArrayList<>();
        for(int i=0;i<16;i++) {
            final String token="parallel-"+i;
            Thread thread=new Thread(() -> { if(SessionOwnership.acquire(token)==SessionOwnership.Acquisition.ACQUIRED)winners.add(token); });
            threads.add(thread); thread.start();
        }
        for(Thread thread:threads)thread.join();
        check(winners.size()==1,"parallel acquisition has exactly one winner");
        check(Os.openCount()==1,"parallel acquisition holds one descriptor");
        check(SessionOwnership.release(winners.get(0)),"parallel winner can clean up");
        check(Os.openCount()==0,"no resource leak");
        System.out.println("Real ownership and kernel adapter tests passed ("+checks+" checks).");
    }
}
