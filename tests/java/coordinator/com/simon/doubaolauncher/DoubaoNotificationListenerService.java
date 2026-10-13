package com.simon.doubaolauncher;
import java.util.ArrayList;
import java.util.List;
final class DoubaoNotificationListenerService {
    final List<CallEndCoordinator.EndCallback> attempts = new ArrayList<CallEndCoordinator.EndCallback>();
    int cancellations;
    void requestHangup(String token, String key, CallEndCoordinator.EndCallback callback) {
        if (!"call".equals(key)) throw new AssertionError("Wrong notification identity");
        attempts.add(callback);
    }
    void cancelHangup(String token) { cancellations++; }
    static final class CallSnapshot {
        static CallSnapshot unknown() { return new CallSnapshot(); }
    }
    CallSnapshot snapshot(String key) { return CallSnapshot.unknown(); }
}
