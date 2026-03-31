package com.activepulse.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * InstanceLock — prevents multiple agent instances running simultaneously.
 *
 * Strategy: bind a ServerSocket on a fixed localhost port (47892).
 * If the port is already taken → another instance is running → exit.
 * The socket is held open for the lifetime of the process.
 * On JVM exit the OS automatically releases it.
 *
 * Why not a lock file?
 *   Lock files are not released on hard crash/kill.
 *   A socket is always released by the OS on process death.
 */
public class InstanceLock {

    private static final Logger log = LoggerFactory.getLogger(InstanceLock.class);

    /** Any unused port in 40000-65535 range. Change if conflicts arise. */
    private static final int LOCK_PORT = 47892;

    private static ServerSocket lockSocket;

    private InstanceLock() {}

    /**
     * Tries to acquire the single-instance lock.
     * @return true  — this is the only instance, safe to continue
     *         false — another instance is already running, caller should exit
     */
    public static boolean acquire() {
        try {
            // Bind to loopback only — not accessible from network
            lockSocket = new ServerSocket(LOCK_PORT, 1,
                    InetAddress.getByName("127.0.0.1"));
            lockSocket.setReuseAddress(false);
            log.info("Instance lock acquired on port {}", LOCK_PORT);

            // Release on JVM exit (normal shutdown, Ctrl+C, or kill)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (lockSocket != null && !lockSocket.isClosed())
                        lockSocket.close();
                } catch (IOException ignored) {}
            }, "instance-lock-release"));

            return true;

        } catch (BindException e) {
            // Port already in use — another instance is running
            log.warn("Another ActivePulse instance is already running (port {} busy).", LOCK_PORT);
            return false;

        } catch (IOException e) {
            // Unexpected — allow startup anyway
            log.warn("Could not acquire instance lock: {} — allowing startup.", e.getMessage());
            return true;
        }
    }

    public static void release() {
        try {
            if (lockSocket != null && !lockSocket.isClosed()) {
                lockSocket.close();
                log.info("Instance lock released.");
            }
        } catch (IOException ignored) {}
    }
}