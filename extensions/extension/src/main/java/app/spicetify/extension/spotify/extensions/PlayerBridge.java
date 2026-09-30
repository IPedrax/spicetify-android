package app.spicetify.extension.spotify.extensions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import app.spicetify.extension.spotify.settings.InstalledPatches;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The extensions' line to Spotify's native core (report sections 0 and 1.1): single esperanto
 * calls, and two streams, each open while anything listens to it: {@code ContextPlayer/GetState},
 * the player's state, and {@code ContextPlayer/GetError}, what it couldn't play (unavailable songs
 * report, section 3.2). It's process-wide, and connects when Spotify builds its
 * {@code SharedCosmosRouterService} (hook H1).
 * <p>
 * Threading: the router answers on Spotify's core thread. There a callback only copies the body
 * and posts it to the one bridge thread, so it never blocks the core, and every {@link Result},
 * {@link StateListener} and {@link ErrorListener} runs on the bridge thread. Nothing may block
 * that thread either: a wait is a {@link #postDelayed}, and a network call runs on a thread of its
 * own. Spotify may hold callbacks weakly, so the bridge keeps each live one in {@link #LIVE} until
 * it answers or is cancelled.
 * <p>
 * When Spotify replaces its router, calls still waiting on the old one fail with "bridge not
 * connected", so a {@link Result} can arrive as a failure on router loss. A stream that ends,
 * answers an error status, or that Spotify won't open, opens again after a backoff while anything
 * listens to it. Each stream keeps a backoff of its own.
 */
public final class PlayerBridge {
    static final String NOT_CONNECTED = "bridge not connected";
    private static final String STATUS_ID = "player_bridge";
    private static final long FIRST_REOPEN_MILLIS = 1000;
    private static final long LAST_REOPEN_MILLIS = 60_000;

    /**
     * One daemon thread, parked while idle. {@link #post} is strictly first in, first out: on this
     * one-thread ScheduledThreadPoolExecutor, execute() schedules with zero delay, so each task is
     * due the moment it's posted, and tasks due at the same moment run in the order they were posted.
     */
    private static final ScheduledExecutorService THREAD = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Spicetify player bridge");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<CosmosRouter.Callback> LIVE = ConcurrentHashMap.newKeySet();
    /**
     * Guards the router and the streams. Router callbacks never take it. With it held, the bridge
     * may take Spotify's router monitor to send, but never the other way around.
     */
    private static final Object LOCK = new Object();
    private static volatile CosmosRouter router;
    /** The latest thing that went wrong, shown on the bridge's settings line until it's resolved. */
    private static volatile String problem;

    /** The player's state, for Trash Bin and Shuffle+; its latest is {@link #lastState()}. */
    private static final Subscription<Esperanto.PlayerState, StateListener> STATES =
            new Subscription<Esperanto.PlayerState, StateListener>("GetState", "player state") {
                @Override
                byte[] request() {
                    return Esperanto.getState();
                }

                @Override
                Esperanto.PlayerState parse(byte[] body) throws IOException {
                    return Esperanto.parseState(body);
                }

                @Override
                void tell(StateListener listener, Esperanto.PlayerState state) {
                    listener.onState(state);
                }
            };

    /**
     * What the player couldn't play. Each error goes to the extensions log as Unavailable songs'
     * latest status before its listeners hear it: the phone checks read its code, its exact reasons
     * and its track there (unavailable songs report, section 8).
     */
    private static final Subscription<Esperanto.PlayerError, ErrorListener> ERRORS =
            new Subscription<Esperanto.PlayerError, ErrorListener>("GetError", "player error") {
                @Override
                byte[] request() {
                    return Esperanto.getError();
                }

                @Override
                Esperanto.PlayerError parse(byte[] body) throws IOException {
                    return Esperanto.parseError(body);
                }

                @Override
                void heard(Esperanto.PlayerError error) {
                    Extensions.status(Extensions.appContext(), Extensions.UNAVAILABLE_SONGS, "GetError " + error.code
                            + ": reasons=" + error.reasons + " track=" + error.trackUri + " context=" + error.contextUri
                            + (error.message.isEmpty() ? "" : " message=" + error.message));
                }

                @Override
                void tell(ErrorListener listener, Esperanto.PlayerError error) {
                    listener.onError(error);
                }
            };

    interface Result {
        void done(byte[] body);

        void failed(String reason);
    }

    /** Hears each player state on the bridge thread. It may hear one more just after it's removed. */
    interface StateListener {
        void onState(Esperanto.PlayerState state);
    }

    /** Hears each player error on the bridge thread. It may hear one more just after it's removed. */
    interface ErrorListener {
        void onError(Esperanto.PlayerError error);
    }

    private PlayerBridge() {}

    /**
     * Hook H1, at the end of {@code SharedCosmosRouterService.<init>}: attaches Spotify's router,
     * then, with the extensions patch installed, starts the extensions that are on. Never throws
     * into Spotify.
     */
    public static void onCosmos(Object service) {
        onCosmos(service, InstalledPatches.extensions());
    }

    /**
     * Without the extensions patch, H1 came with Home pins alone, whose picker reads Your Library
     * through the bridge. Then no extension starts: one left on from an earlier install couldn't be
     * turned off, since the Marketplace disables its switch without the patch.
     */
    static void onCosmos(Object service, boolean extensionsInstalled) {
        try {
            Context application = application(service);
            if (application != null) Extensions.setAppContext(application);
            attach(CosmosRouter.reflective(service));
            if (!extensionsInstalled) return;
            Context context = Extensions.appContext();
            if (context == null) {
                Log.w("Spicetify", "No application context to start the extensions with");
            } else {
                post(() -> Extensions.startEnabled(context));
            }
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't connect the player bridge", e);
            report("Couldn't connect: " + e);
        }
    }

    /** Spotify's {@code Application}, from {@code ActivityThread} through the router's class loader, or null. */
    @SuppressLint("PrivateApi") // greylisted, contained here, and PatchSettings' context covers a denial
    private static Context application(Object service) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false,
                    service.getClass().getClassLoader());
            return (Context) activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't find Spotify's application; using the one from its startup", e);
            return null;
        }
    }

    /**
     * Sends everything through {@code cosmos} from now on. Calls still waiting on the old router
     * fail, and each stream moves to the new one if anything listens to it. The reopen backoffs
     * start over, and a reopen that was due on the old router does nothing.
     */
    static void attach(CosmosRouter cosmos) {
        synchronized (LOCK) {
            CosmosRouter old = router;
            STATES.close();
            ERRORS.close();
            router = cosmos;
            problem = null;
            if (old != null && old != cosmos) failPending(old);
            STATES.restart();
            ERRORS.restart();
        }
    }

    /** Called with {@link #LOCK} held. Failing a call also releases its request. */
    private static void failPending(CosmosRouter old) {
        for (CosmosRouter.Callback live : LIVE) {
            if (live instanceof Call && ((Call) live).sentThrough == old) ((Call) live).fail(NOT_CONNECTED);
        }
    }

    /** True while a router is attached and Spotify hasn't destroyed it. */
    static boolean connected() {
        CosmosRouter current = router;
        return current != null && !destroyed(current);
    }

    /** POSTs {@code body} to {@code sp://esperanto/<service>/<method>}. */
    static void call(String service, String method, byte[] body, Result result) {
        send("POST", "sp://esperanto/" + service + "/" + method, body, result);
    }

    /** A plain cosmos GET, such as {@code sp://auth/v2/token?renew=0}. */
    static void get(String uri, Result result) {
        send("GET", uri, new byte[0], result);
    }

    /** Status 200 gives {@code done}, anything else {@code failed}; either runs on the bridge thread. */
    private static void send(String action, String uri, byte[] body, Result result) {
        CosmosRouter current = router;
        if (current == null || destroyed(current)) {
            post(() -> result.failed(NOT_CONNECTED));
            return;
        }
        Call call = new Call(current, result);
        LIVE.add(call);
        try {
            call.started(current.resolve(action, uri, body, call));
        } catch (Throwable e) {
            // A router destroyed since the check above refuses to send.
            call.fail(destroyed(current) ? NOT_CONNECTED : String.valueOf(e));
        }
    }

    /** Adds {@code listener}. The first one opens the {@code GetState} stream, as does the next one after it failed. */
    static void addStateListener(StateListener listener) {
        STATES.add(listener);
    }

    /** Removes {@code listener}. Removing the last one cancels the stream. */
    static void removeStateListener(StateListener listener) {
        STATES.remove(listener);
    }

    /** Adds {@code listener}. The first one opens the {@code GetError} stream, as does the next one after it failed. */
    static void addErrorListener(ErrorListener listener) {
        ERRORS.add(listener);
    }

    /** Removes {@code listener}. Removing the last one cancels the stream. */
    static void removeErrorListener(ErrorListener listener) {
        ERRORS.remove(listener);
    }

    /** The latest state of the open stream, or null when no stream is open or none has arrived. */
    static Esperanto.PlayerState lastState() {
        return STATES.last;
    }

    /** The bridge's full line, connected or not, with its latest problem in parentheses. */
    static String statusLine() {
        String line = problemLine();
        if (line != null) return line;
        Esperanto.PlayerState state = STATES.last;
        if (state == null || state.trackUri == null) return "Player bridge: connected" + note();
        return "Player bridge: connected, " + state.trackUri + note();
    }

    /**
     * The bridge's line while it waits for Spotify or its stream failed, which Spicetify settings
     * shows; null while it works.
     */
    public static String problemLine() {
        if (!connected()) return "Player bridge: waiting for Spotify" + note();
        synchronized (LOCK) {
            if (STATES.waiting() || ERRORS.waiting()) return "Player bridge: stream error, retrying" + note();
        }
        return null;
    }

    /** The latest problem in parentheses, or nothing once it's resolved. */
    private static String note() {
        String latest = problem;
        return latest == null ? "" : " (" + latest + ")";
    }

    /** Shows {@code line} on the bridge's settings line and appends it to the extensions log. */
    private static void report(String line) {
        problem = line;
        Extensions.status(Extensions.appContext(), STATUS_ID, line);
    }

    private static boolean destroyed(CosmosRouter cosmos) {
        try {
            return cosmos.destroyed();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't ask Spotify's router whether it's destroyed", e);
            return true;
        }
    }

    private static void release(CosmosRouter.Cancel cancel) {
        try {
            cancel.cancel();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't release a cosmos request", e);
        }
    }

    /** Spotify owns the array it answered with, so the bridge thread gets a copy. */
    private static byte[] copy(byte[] body) {
        return body == null ? new byte[0] : body.clone();
    }

    /** Runs {@code task} on the bridge thread, after everything posted before it. A task that throws is logged. */
    static void post(Runnable task) {
        THREAD.execute(logged(task));
    }

    /** Runs {@code task} on the bridge thread once {@code delayMillis} have passed; the thread stays free meanwhile. */
    static void postDelayed(Runnable task, long delayMillis) {
        THREAD.schedule(logged(task), delayMillis, TimeUnit.MILLISECONDS);
    }

    private static Runnable logged(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable e) {
                Log.w("Spicetify", "A player bridge task failed", e);
            }
        };
    }

    /** One call. Only its first answer counts, and that answer releases the request, as Spotify's own transport does. */
    private static final class Call implements CosmosRouter.Callback {
        private static final Object ANSWERED = new Object();
        final CosmosRouter sentThrough;
        private final Result result;
        /** Null while resolving, then the request's Cancel, then {@link #ANSWERED}. */
        private final AtomicReference<Object> state = new AtomicReference<>();

        Call(CosmosRouter sentThrough, Result result) {
            this.sentThrough = sentThrough;
            this.result = result;
        }

        /** Keeps {@code cancel}, or releases it now if the answer came before resolve returned. */
        void started(CosmosRouter.Cancel cancel) {
            if (!state.compareAndSet(null, cancel)) release(cancel);
        }

        @Override
        public void onResponse(int status, byte[] body) {
            try {
                if (!answered()) return;
                byte[] copy = copy(body);
                post(() -> {
                    if (status == 200) result.done(copy);
                    else result.failed("status " + status);
                });
            } catch (Throwable e) {
                Log.w("Spicetify", "A cosmos answer failed", e);
            }
        }

        @Override
        public void onError(Throwable error) {
            fail(String.valueOf(error));
        }

        /** Fails the call with {@code reason} unless it already answered, and releases its request. */
        void fail(String reason) {
            try {
                if (!answered()) return;
                post(() -> result.failed(reason));
            } catch (Throwable e) {
                Log.w("Spicetify", "A cosmos error failed", e);
            }
        }

        private boolean answered() {
            Object previous = state.getAndSet(ANSWERED);
            if (previous == ANSWERED) return false;
            LIVE.remove(this);
            if (previous != null) release((CosmosRouter.Cancel) previous);
            return true;
        }
    }

    /**
     * A SUB the bridge keeps open while anything listens to it, GetState or GetError, by one set of
     * rules: an error, or an answer other than 200, ends it, and it opens again after a backoff that
     * doubles up to a minute. A good answer or a new router sets that backoff back to a second, and
     * a good state sets an open error stream's back too.
     */
    private abstract static class Subscription<T, L> {
        final String uri;
        /** What its lines call it, such as "player state". */
        final String name;
        final CopyOnWriteArrayList<L> listeners = new CopyOnWriteArrayList<>();
        /** The open stream, or null; it changes only with {@link #LOCK} held. */
        volatile Stream stream;
        /** The open stream's latest answer, or null when none is open or none has arrived. */
        volatile T last;
        /** The wait before the next reopen. */
        long reopenMillis = FIRST_REOPEN_MILLIS; // guarded by LOCK
        /** A reopen is due on the current router, so another failure meanwhile schedules no second one. */
        boolean reopenDue; // guarded by LOCK

        Subscription(String method, String name) {
            this.uri = "sp://esperanto/" + Esperanto.CONTEXT_PLAYER + "/" + method;
            this.name = name;
        }

        abstract byte[] request();

        abstract T parse(byte[] body) throws IOException;

        /** On the bridge thread, before the listeners hear {@code value}. */
        void heard(T value) {}

        abstract void tell(L listener, T value);

        void add(L listener) {
            synchronized (LOCK) {
                listeners.addIfAbsent(listener);
                if (stream == null) open();
            }
        }

        void remove(L listener) {
            synchronized (LOCK) {
                listeners.remove(listener);
                if (listeners.isEmpty()) close();
            }
        }

        /**
         * Called with {@link #LOCK} held. Something listens, yet no stream is open: it ended, or it
         * couldn't open, and a reopen is due.
         */
        boolean waiting() {
            return stream == null && !listeners.isEmpty();
        }

        /**
         * Called with {@link #LOCK} held. Without a live router, the next {@link #attach} opens it; a
         * live router that won't open it gets a reopen.
         */
        void open() {
            CosmosRouter current = router;
            if (current == null || destroyed(current)) return;
            Stream opened = new Stream(this);
            stream = opened; // before resolve, because Spotify may answer before resolve returns
            LIVE.add(opened);
            try {
                opened.cancel = current.resolve("SUB", uri, request(), opened);
            } catch (Throwable e) {
                stream = null;
                LIVE.remove(opened);
                Log.w("Spicetify", "Couldn't open the " + name + " stream", e);
                report("Couldn't open the " + name + " stream: " + e);
                reopenLater();
            }
        }

        /**
         * Called with {@link #LOCK} held: one reopen at a time, after the backoff, which then doubles
         * up to a minute.
         */
        private void reopenLater() {
            if (reopenDue) return;
            reopenDue = true;
            CosmosRouter dueOn = router;
            postDelayed(() -> reopen(dueOn), reopenMillis);
            reopenMillis = Math.min(reopenMillis * 2, LAST_REOPEN_MILLIS);
        }

        /**
         * On the bridge thread: opens a stream when something listens and none is open. A destroyed
         * router opens nothing and schedules nothing more; the next {@link #attach} opens the stream.
         */
        private void reopen(CosmosRouter dueOn) {
            synchronized (LOCK) {
                if (router != dueOn) return; // attach started over
                reopenDue = false;
                if (waiting()) open();
            }
        }

        /** Called with {@link #LOCK} held. It forgets the last answer too, since the track can now change unseen. */
        void close() {
            last = null;
            Stream closing = stream;
            if (closing == null) return;
            stream = null;
            LIVE.remove(closing);
            if (closing.cancel != null) release(closing.cancel);
        }

        /**
         * Called with {@link #LOCK} held, on a new router: the backoff starts over, and the stream
         * opens if anything listens.
         */
        void restart() {
            reopenDue = false;
            reopenMillis = FIRST_REOPEN_MILLIS;
            if (!listeners.isEmpty()) open();
        }

        /**
         * On the bridge thread. An answer other than 200 ends the stream, as a stream error does:
         * Spotify's core ends the subscription after one, such as the 404 it answers before its
         * player is ready. An answer that can't be read is reported once per stream, which stays
         * open, since it's alive.
         */
        void onAnswer(Stream from, int status, byte[] body) {
            if (from != stream) return; // the old stream had it in flight
            if (status != 200) {
                synchronized (LOCK) {
                    if (from != stream) return;
                    close();
                    if (!listeners.isEmpty()) reopenLater();
                }
                Log.w("Spicetify", "The " + name + " stream answered status " + status);
                report("Couldn't read the " + name + ": status " + status);
                return;
            }
            T value;
            try {
                value = parse(body);
            } catch (IOException e) {
                Log.w("Spicetify", "Couldn't read the " + name, e);
                if (!from.reported) {
                    from.reported = true;
                    report("Couldn't read the " + name + ": " + e.getMessage());
                }
                return;
            }
            synchronized (LOCK) {
                if (from != stream) return; // closed while this one was read
                last = value;
                problem = null;
                reopenMillis = FIRST_REOPEN_MILLIS;
                // A state means the player is ready, so an error stream that's open, silent because
                // nothing failed, starts its backoff over too: GetError answers only when something
                // fails, so its own answers seldom reset it. One that's failing keeps doubling.
                if (this == STATES && ERRORS.stream != null) ERRORS.reopenMillis = FIRST_REOPEN_MILLIS;
            }
            heard(value);
            for (L listener : listeners) {
                try {
                    tell(listener, value);
                } catch (Throwable e) {
                    Log.w("Spicetify", "A " + name + " listener failed", e);
                }
            }
        }

        /** On the bridge thread. While anything listens, the stream opens again after the backoff. */
        void onEnded(Stream from, Throwable error) {
            synchronized (LOCK) {
                if (from != stream) return;
                close();
                if (!listeners.isEmpty()) reopenLater();
            }
            Log.w("Spicetify", "The " + name + " stream ended", error);
            report("The " + name + " stream ended: " + error);
        }
    }

    /** One opening of a {@link Subscription}: each answer is one state or error, until it's cancelled. */
    private static final class Stream implements CosmosRouter.Callback {
        final Subscription<?, ?> of;
        CosmosRouter.Cancel cancel; // guarded by LOCK
        boolean reported; // only the bridge thread uses it

        Stream(Subscription<?, ?> of) {
            this.of = of;
        }

        @Override
        public void onResponse(int status, byte[] body) {
            try {
                byte[] copy = copy(body);
                post(() -> of.onAnswer(this, status, copy));
            } catch (Throwable e) {
                Log.w("Spicetify", "A " + of.name + " answer failed", e);
            }
        }

        @Override
        public void onError(Throwable error) {
            try {
                post(() -> of.onEnded(this, error));
            } catch (Throwable e) {
                Log.w("Spicetify", "A " + of.name + " error failed", e);
            }
        }
    }
}
