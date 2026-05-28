package com.heartwith.mihealth.lsp;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class MiHealthHookModule extends XposedModule {
    private static final String TAG = "HeartwithMiHealth";
    private static final String TARGET_PACKAGE = "com.mi.health";
    private static final String RUNTIME_PREFS = "heartwith_mihealth_runtime";
    private static final String KEY_ACTIVE_SOURCE = "active_source";
    private static final String KEY_ACTIVE_SOURCE_SEEN_MS = "active_source_seen_ms";
    private static final long DUPLICATE_WINDOW_MS = 900L;
    private static final long ACCEPTED_LOG_INTERVAL_MS = 60_000L;
    private static final long RESTORED_SOURCE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final boolean VERBOSE_LOGS = false;

    private static final String[] START_HELPERS = {
            "com.xiaomi.fitness.sport_eco.extension.EcoDeviceModelExtKt",
            "com.xiaomi.fitness.sport_eco_manager.extension.DeviceModelExtKt",
            "com.xiaomi.fitness.sport.extension.DeviceModelExtKt",
            "com.xiaomi.fitness.sport_manager.extension.DeviceModelExtKt"
    };

    private static final String[] LAUNCH_MODEL_CLASSES = {
            "com.xiaomi.fitness.sport_eco.model.LaunchSportModel",
            "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel",
            "com.xiaomi.fitness.sport.model.LaunchSportModel",
            "com.xiaomi.fitness.sport_manager.model.LaunchSportModel"
    };

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "heartwith-mihealth");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean configReceiverRegistered = new AtomicBoolean(false);
    private final HeartwithUploader uploader = new HeartwithUploader();
    private final List<Object> launchModels = new ArrayList<>();
    private volatile Context appContext;
    private volatile Object hrCallback;
    private volatile boolean started;
    private volatile long lastStartAt;
    private volatile String lastStartReason = "";
    private volatile int lastHr = -1;
    private volatile long lastHrElapsedMs;
    private volatile long lastAcceptedLogMs;
    private volatile String activeSource;
    private volatile long activeSourceElapsedMs;
    private volatile long lastActiveSourcePersistElapsedMs;
    private volatile boolean activeSourceRestored;
    private volatile String processName = TARGET_PACKAGE;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        logLine("module loaded api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        String currentProcess = getProcessName();
        if (currentProcess != null && !currentProcess.equals(TARGET_PACKAGE) && !currentProcess.startsWith(TARGET_PACKAGE + ":")) {
            return;
        }
        processName = currentProcess == null ? TARGET_PACKAGE : currentProcess;
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        ClassLoader classLoader = param.getClassLoader();
        hookLifecycle(classLoader);
        if (isWorkerProcess()) {
            hookHeartRateSinks(classLoader);
        }
        logLine("hooks installed process=" + processName);
    }

    private void hookLifecycle(final ClassLoader classLoader) {
        hookAfter(Application.class, "attach", new Class<?>[]{Context.class}, new AfterHook() {
            @Override
            public void after(XposedInterface.Chain chain, Object result) {
                Context base = (Context) chain.getArg(0);
                Context applicationContext = base == null ? null : base.getApplicationContext();
                appContext = applicationContext == null ? base : applicationContext;
                diagLine("attach process=" + processName);
                registerConfigReceiver(appContext);
                restoreActiveSource(appContext);
                warmUpUploaderConfig(appContext);
                scheduleStartAfterAttach(classLoader);
            }
        });
    }

    private boolean isWorkerProcess() {
        return (TARGET_PACKAGE + ":device").equals(processName);
    }

    private void scheduleStartAfterAttach(final ClassLoader classLoader) {
        final Context context = appContext;
        if (context == null || !isWorkerProcess()) {
            return;
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    ensureRealtimeHrStarted(classLoader, "application:attach");
                }
            }, 2_000L);
        } catch (Throwable ignored) {
        }
    }

    private void warmUpUploaderConfig(final Context context) {
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    uploader.warmUp(context);
                } catch (Throwable throwable) {
                    diagLine("warmup crashed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                }
            }
        });
    }

    private void registerConfigReceiver(final Context context) {
        if (context == null || !configReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !HeartwithSettings.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    final Context runtimeContext = context;
                    final boolean enabled = intent.getBooleanExtra(HeartwithSettings.EXTRA_ENABLED, true);
                    final String serverUrl = intent.getStringExtra(HeartwithSettings.EXTRA_SERVER_URL);
                    final String displayName = intent.getStringExtra(HeartwithSettings.EXTRA_DISPLAY_NAME);
                    WORKER.execute(new Runnable() {
                        @Override
                        public void run() {
                            uploader.applySettings(
                                    runtimeContext,
                                    new HeartwithSettings(enabled, serverUrl, displayName),
                                    "settings broadcast synced");
                        }
                    });
                }
            };
            IntentFilter filter = new IntentFilter(HeartwithSettings.ACTION_CONFIG_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            diagLine("config receiver registered process=" + processName);
        } catch (Throwable throwable) {
            diagLine("config receiver failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private void hookHeartRateSinks(final ClassLoader classLoader) {
        hookWearRawHandler(classLoader);
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoRawHandler(classLoader);
        hookEcoRemoteDataHandler(classLoader);
        hookLegacyHuamiHeartRateProfile(classLoader);
        hookLaunchSportModel(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel");
        hookLaunchSportModel(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel");
        hookLaunchViewBean(classLoader, "com.xiaomi.fitness.sport_eco.bean.LaunchViewBean");
        hookLaunchViewBean(classLoader, "com.xiaomi.fitness.sport.bean.LaunchViewBean");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.state.data.HuamiDataReceive");
        hookHuamiCallback(classLoader, "com.xiaomi.fit.device.huami.HuaMiApiCallerImpl$startRealtimeMeasureHr$1");
        hookHuamiCallback(classLoader, "com.xiaomi.wearable.HuamiApiImpl$startRealtimeMeasureHr$1");
    }

    private void hookWearRawHandler(final ClassLoader classLoader) {
        try {
            Class<?> adapterClass = findClass("com.xiaomi.fitness.device.contact.DeviceDataHandlerAdapter", classLoader);
            Method method = adapterClass.getDeclaredMethod("handlePacketInternal", String.class, int.class, byte[].class);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final String source = "wear-raw";
                    if (shouldIgnoreSource(source)) {
                        return chain.proceed();
                    }
                    int type = ((Number) chain.getArg(1)).intValue();
                    Object raw = chain.getArg(2);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromWearRaw(classLoader, (byte[]) raw);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookEcoPacketHandler(final ClassLoader classLoader, final String className) {
        try {
            Class<?> handlerClass = findClass(className, classLoader);
            Class<?> packetClass = findClass("kxs", classLoader);
            Method method = handlerClass.getDeclaredMethod("handleEcoPacket", String.class, int.class, packetClass);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "eco-packet";
                    if (shouldIgnoreSource(source)) {
                        return result;
                    }
                    int type = ((Number) chain.getArg(1)).intValue();
                    if (type == 8) {
                        Integer hr = extractHrFromKxs(chain.getArg(2));
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return result;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookEcoRawHandler(final ClassLoader classLoader) {
        try {
            Class<?> wrapperClass = findClass(
                    "com.xiaomi.fitness.eco.device.contact.export.EcoDataHandlerWrapper", classLoader);
            Method method = wrapperClass.getDeclaredMethod("handleDataInternal", String.class, int.class, byte[].class);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "eco-raw";
                    if (shouldIgnoreSource(source)) {
                        return result;
                    }
                    int type = ((Number) chain.getArg(1)).intValue();
                    Object raw = chain.getArg(2);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromRaw(classLoader, (byte[]) raw);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return result;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookEcoRemoteDataHandler(final ClassLoader classLoader) {
        try {
            final Class<?> packetClass = findClass("kxs", classLoader);
            Class<?> handlerClass = findClass(
                    "com.xiaomi.fitness.eco.device.contact.remote.EcoDeviceDataHandler", classLoader);

            Method handleData = handlerClass.getDeclaredMethod("handleData", int.class, byte[].class);
            handleData.setAccessible(true);
            hook(handleData).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final String source = "eco-remote-raw";
                    if (shouldIgnoreSource(source)) {
                        return chain.proceed();
                    }
                    int type = ((Number) chain.getArg(0)).intValue();
                    Object raw = chain.getArg(1);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromRaw(classLoader, (byte[]) raw);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return chain.proceed();
                }
            });

            Method handlePacket = handlerClass.getDeclaredMethod("handlePacket", int.class, packetClass);
            handlePacket.setAccessible(true);
            hook(handlePacket).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final String source = "eco-remote-packet";
                    if (shouldIgnoreSource(source)) {
                        return chain.proceed();
                    }
                    int type = ((Number) chain.getArg(0)).intValue();
                    if (type == 8) {
                        Integer hr = extractHrFromKxs(chain.getArg(1));
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookLegacyHuamiHeartRateProfile(final ClassLoader classLoader) {
        try {
            Class<?> profileClass = findClass("twu", classLoader);
            hookLegacyHuamiRawMethod(profileClass, "e");
            hookLegacyHuamiRawMethod(profileClass, "i");
        } catch (Throwable ignored) {
        }
    }

    private void hookLegacyHuamiRawMethod(Class<?> profileClass, final String methodName) throws Exception {
        Method method = profileClass.getDeclaredMethod(methodName, byte[].class);
        method.setAccessible(true);
        hook(method).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final String source = "legacy-huami";
                if (shouldIgnoreSource(source)) {
                    return chain.proceed();
                }
                Object raw = chain.getArg(0);
                if (raw instanceof byte[]) {
                    byte[] data = (byte[]) raw;
                    if (data.length > 1) {
                        onHeartRate(data[1] & 0xff, source);
                    }
                }
                return chain.proceed();
            }
        });
    }

    private void hookLaunchSportModel(ClassLoader classLoader, final String className) {
        try {
            Class<?> modelClass = findClass(className, classLoader);
            Method setHeartHr = modelClass.getDeclaredMethod("setHeartHr", int.class);
            setHeartHr.setAccessible(true);
            hook(setHeartHr).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final String source = "launch-setHeartHr";
                    int hr = ((Number) chain.getArg(0)).intValue();
                    Object result = chain.proceed();
                    if (!shouldIgnoreSource(source)) {
                        onHeartRate(hr, source);
                    }
                    return result;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookLaunchViewBean(ClassLoader classLoader, final String className) {
        try {
            Class<?> beanClass = findClass(className, classLoader);
            Method method = beanClass.getDeclaredMethod("setHeartRate", String.class);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "launch-bean";
                    if (!shouldIgnoreSource(source)) {
                        Integer hr = parseHeartRate(String.valueOf(chain.getArg(0)));
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return result;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookHuamiCallback(ClassLoader classLoader, final String className) {
        try {
            Class<?> callbackClass = findClass(className, classLoader);
            Method method = callbackClass.getDeclaredMethod("onHeartRateChanged", int.class);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "huami";
                    if (!shouldIgnoreSource(source)) {
                        onHeartRate(((Number) chain.getArg(0)).intValue(), source);
                    }
                    return result;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void ensureRealtimeHrStarted(final ClassLoader classLoader, final String reason) {
        if (!shouldStartNow(reason) || !starting.compareAndSet(false, true)) {
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    lastStartAt = SystemClock.elapsedRealtime();
                    lastStartReason = reason;
                    boolean registered = ensureLaunchModels(classLoader);
                    boolean deviceStarted = startDeviceRealtimeHr(classLoader);
                    started = registered || deviceStarted;
                    scheduleRetryIfNeeded(classLoader);
                } catch (Throwable throwable) {
                    logLine("start failed: " + throwable.getClass().getSimpleName());
                } finally {
                    starting.set(false);
                }
            }
        });
    }

    private boolean shouldStartNow(String reason) {
        if (!started) {
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastHr > 0 && now - lastHrElapsedMs < 60_000L) {
            return false;
        }
        if (lastHr > 0 && now - lastHrElapsedMs >= 120_000L) {
            activeSource = null;
        }
        boolean movedPastSplash = lastStartReason.contains("SplashActivity") && !reason.contains("SplashActivity");
        long elapsed = now - lastStartAt;
        return movedPastSplash || elapsed >= 8_000L;
    }

    private void scheduleRetryIfNeeded(final ClassLoader classLoader) {
        final Context context = appContext;
        if (context == null || lastHr > 0) {
            return;
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (lastHr <= 0) {
                        ensureRealtimeHrStarted(classLoader, "timer:no-heart-rate");
                    }
                }
            }, 20_000L);
        } catch (Throwable ignored) {
        }
    }

    private boolean ensureLaunchModels(ClassLoader classLoader) {
        if (!launchModels.isEmpty()) {
            return true;
        }
        boolean ok = false;
        for (String className : LAUNCH_MODEL_CLASSES) {
            try {
                Class<?> modelClass = findClass(className, classLoader);
                Object model = newInstance(modelClass);
                launchModels.add(model);
                try {
                    callMethod(model, "init");
                } catch (Throwable ignored) {
                }
                try {
                    callMethod(model, "registerDeviceHr");
                } catch (Throwable ignored) {
                }
                ok = true;
            } catch (Throwable ignored) {
            }
        }
        return ok;
    }

    private boolean startDeviceRealtimeHr(ClassLoader classLoader) {
        Object device = getCurrentDeviceModel(classLoader);
        if (device == null) {
            return false;
        }
        Object callback = null;
        try {
            callback = getOrCreateHrCallback(classLoader);
        } catch (Throwable ignored) {
        }
        boolean startedAny = false;
        for (String helperClassName : START_HELPERS) {
            try {
                Class<?> helperClass = findClass(helperClassName, classLoader);
                callStaticMethod(helperClass, "startDeviceHr", device, callback);
                startedAny = true;
            } catch (Throwable ignored) {
            }
        }
        return startedAny;
    }

    private Object getCurrentDeviceModel(ClassLoader classLoader) {
        try {
            Class<?> managerClass = findClass("com.xiaomi.fitness.device.manager.export.WearableDeviceManager", classLoader);
            Object companion = getStaticObjectField(managerClass, "Companion");
            Class<?> extClass = findClass("com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt", classLoader);
            Object manager = callStaticMethod(extClass, "getInstance", companion);
            return callMethod(manager, "getCurrentDeviceModel");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getOrCreateHrCallback(ClassLoader classLoader) throws Exception {
        Object callback = hrCallback;
        if (callback != null) {
            return callback;
        }
        Class<?> callbackClass = findClass("com.xiaomi.hm.health.bt.sdk.ISportHrCallback", classLoader);
        callback = Proxy.newProxyInstance(classLoader, new Class<?>[]{callbackClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "HeartwithMiHealthCallback";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                if ("onHeartRateChanged".equals(name) && args != null && args.length > 0) {
                    final String source = "huami-proxy";
                    if (!shouldIgnoreSource(source)) {
                        onHeartRate(((Number) args[0]).intValue(), source);
                    }
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        });
        hrCallback = callback;
        return callback;
    }

    private Integer extractHrFromWearRaw(ClassLoader classLoader, byte[] data) {
        try {
            Class<?> packetClass = findClass("ixs", classLoader);
            Object packet = callStaticMethod(packetClass, "L", data);
            return extractHrFromIxs(packet);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromRaw(ClassLoader classLoader, byte[] data) {
        try {
            Class<?> packetClass = findClass("kxs", classLoader);
            Object packet = callStaticMethod(packetClass, "y", data);
            return extractHrFromKxs(packet);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromKxs(Object packet) {
        if (packet == null) {
            return null;
        }
        try {
            Object paa = callMethod(packet, "r");
            Object sportRealtime = paa == null ? null : callMethod(paa, "o");
            if (sportRealtime == null) {
                return null;
            }
            int hr = ((Number) getFieldValue(sportRealtime, "f")).intValue();
            return hr >= 30 && hr <= 240 ? hr : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromIxs(Object packet) {
        if (packet == null) {
            return null;
        }
        try {
            Object fitness = callMethod(packet, "u");
            Object sportRealtime = fitness == null ? null : callMethod(fitness, "p");
            if (sportRealtime == null) {
                return null;
            }
            int hr = ((Number) getFieldValue(sportRealtime, "f")).intValue();
            return hr >= 30 && hr <= 240 ? hr : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void onHeartRate(final int hr, final String source) {
        if (hr < 30 || hr > 240) {
            return;
        }
        if (!lockOrAcceptSource(source)) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (hr == lastHr && elapsed - lastHrElapsedMs < DUPLICATE_WINDOW_MS) {
            return;
        }
        lastHr = hr;
        lastHrElapsedMs = elapsed;
        if (elapsed - lastAcceptedLogMs >= ACCEPTED_LOG_INTERVAL_MS) {
            lastAcceptedLogMs = elapsed;
            logLine("heart_rate=" + hr + ", source=" + source);
        }
        final Context context = appContext;
        if (context == null) {
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                long seenMs = System.currentTimeMillis();
                try {
                    HeartwithStatus.showHookProcessNotification(context, hr, source, seenMs);
                } catch (Throwable throwable) {
                    diagLine("notification failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                }
                try {
                    HeartwithStatus.reportFromHook(context, hr, source, processName, seenMs);
                } catch (Throwable throwable) {
                    diagLine("status failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                }
                try {
                    uploader.onHeartRate(context, hr, source);
                } catch (Throwable throwable) {
                    diagLine("uploader crashed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                }
            }
        });
    }

    private boolean shouldIgnoreSource(String source) {
        String selected = activeSource;
        if (selected == null || selected.equals(source)) {
            return false;
        }
        if (activeSourceRestored && lastHr <= 0) {
            return false;
        }
        return true;
    }

    private boolean lockOrAcceptSource(String source) {
        String selected = activeSource;
        if (selected != null) {
            if (selected.equals(source)) {
                activeSourceRestored = false;
                maybePersistActiveSource(source);
                return true;
            }
            if (activeSourceRestored && lastHr <= 0) {
                synchronized (this) {
                    if (activeSourceRestored && lastHr <= 0) {
                        activeSource = null;
                        activeSourceRestored = false;
                    }
                }
            } else {
                return false;
            }
        }
        synchronized (this) {
            if (activeSource == null) {
                activeSource = source;
                activeSourceElapsedMs = SystemClock.elapsedRealtime();
                activeSourceRestored = false;
                maybePersistActiveSource(source);
                logLine("heart-rate source locked: " + source);
                return true;
            }
            return activeSource.equals(source);
        }
    }

    private void restoreActiveSource(Context context) {
        if (context == null || activeSource != null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
            String source = prefs.getString(KEY_ACTIVE_SOURCE, null);
            long seenMs = prefs.getLong(KEY_ACTIVE_SOURCE_SEEN_MS, 0L);
            if (source != null && !source.isEmpty() &&
                    System.currentTimeMillis() - seenMs < RESTORED_SOURCE_TTL_MS) {
                activeSource = source;
                activeSourceElapsedMs = SystemClock.elapsedRealtime();
                activeSourceRestored = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private void persistActiveSource(String source) {
        Context context = appContext;
        if (context == null || source == null || source.isEmpty()) {
            return;
        }
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ACTIVE_SOURCE, source)
                    .putLong(KEY_ACTIVE_SOURCE_SEEN_MS, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void maybePersistActiveSource(String source) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastActiveSourcePersistElapsedMs > 0L && elapsed - lastActiveSourcePersistElapsedMs < 600_000L) {
            return;
        }
        lastActiveSourcePersistElapsedMs = elapsed;
        persistActiveSource(source);
    }

    private Integer parseHeartRate(String text) {
        if (text == null) {
            return null;
        }
        int current = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (current < 0) {
                    current = 0;
                }
                current = current * 10 + (c - '0');
            } else if (current >= 0) {
                if (current >= 30 && current <= 240) {
                    return current;
                }
                current = -1;
            }
        }
        return current >= 30 && current <= 240 ? current : null;
    }

    private void hookAfter(Class<?> target, String methodName, Class<?>[] parameterTypes, final AfterHook afterHook) {
        try {
            Method method = target.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    afterHook.after(chain, result);
                    return result;
                }
            });
        } catch (Throwable throwable) {
            logLine("hook failed " + target.getName() + "." + methodName + ": " + throwable.getClass().getSimpleName());
        }
    }

    private Class<?> findClass(String name, ClassLoader classLoader) throws ClassNotFoundException {
        return Class.forName(name, false, classLoader);
    }

    private Object newInstance(Class<?> clazz) throws Exception {
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private Object getStaticObjectField(Class<?> clazz, String name) throws Exception {
        Field field = findField(clazz, name);
        return field.get(null);
    }

    private Object getFieldValue(Object instance, String name) throws Exception {
        Field field = findField(instance.getClass(), name);
        return field.get(instance);
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Object callMethod(Object instance, String name, Object... args) throws Exception {
        Method method = findCompatibleMethod(instance.getClass(), name, false, args);
        return method.invoke(instance, args);
    }

    private Object callStaticMethod(Class<?> clazz, String name, Object... args) throws Exception {
        Method method = findCompatibleMethod(clazz, name, true, args);
        return method.invoke(null, args);
    }

    private Method findCompatibleMethod(Class<?> clazz, String name, boolean requireStatic, Object[] args)
            throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            Method[] methods = current.getDeclaredMethods();
            for (Method method : methods) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers()) != requireStatic) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == args.length && parametersMatch(parameterTypes, args)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }

    private boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!wrapPrimitive(parameterTypes[i]).isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Character.TYPE) return Character.class;
        return Void.class;
    }

    private Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        if (type == Character.TYPE) return (char) 0;
        return null;
    }

    private String getProcessName() {
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream("/proc/self/cmdline");
            byte[] buffer = new byte[128];
            int length = inputStream.read(buffer);
            if (length <= 0) {
                return null;
            }
            int end = 0;
            while (end < length && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void logLine(String message) {
        if (!VERBOSE_LOGS) {
            return;
        }
        diagLine(message);
    }

    private void diagLine(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    private interface AfterHook {
        void after(XposedInterface.Chain chain, Object result) throws Throwable;
    }
}
