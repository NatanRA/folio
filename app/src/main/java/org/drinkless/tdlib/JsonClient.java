package org.drinkless.tdlib;

/** JNI entry points supplied by the official TDLib JSON Java interface (Boost Software License 1.0). */
public final class JsonClient {
    static { System.loadLibrary("tdjsonjava"); }
    private JsonClient() {}
    public static native int createClientId();
    public static native void send(int clientId, String request);
    public static native String receive(double timeout);
    public static native String execute(String request);
    public interface LogMessageHandler { void onLogMessage(int verbosityLevel, String message); }
    public static native void setLogMessageHandler(int maxVerbosityLevel, LogMessageHandler handler);
}
