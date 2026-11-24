package org.telegram.ui.Plugins;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.telegram.messenger.ApplicationLoader;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

/**
 * Bridges Pine (ART hooking) to the Python plugin runtime.
 *
 * <p>Python calls {@link #hookAllMethods}, {@link #hookAllConstructors} or
 * {@link #hookMethod} to install a hook. Every hooked call is forwarded to
 * {@code dg_hooks.dispatch_before} / {@code dg_hooks.dispatch_after} with a
 * {@link Param} the script can mutate.</p>
 */
public final class HookBridge {

    private static final String TAG = "dg-hooks";

    /** Mutable call information handed to Python. */
    public static final class Param {
        public final Member method;
        public Object thisObject;
        public final Object[] args;
        public Object result;
        public boolean resultSet;

        Param(Member method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }
    }

    private static final Map<Integer, List<MethodHook.Unhook>> UNHOOKS = new ConcurrentHashMap<>();
    private static PyObject pythonModule;
    private static String pineError;

    private HookBridge() {
    }

    private static final class PyHook extends MethodHook {
        private final int hookId;

        PyHook(int hookId) {
            this.hookId = hookId;
        }

        @Override
        public void beforeCall(Pine.CallFrame callFrame) {
            dispatch(false, hookId, callFrame);
        }

        @Override
        public void afterCall(Pine.CallFrame callFrame) {
            dispatch(true, hookId, callFrame);
        }
    }

    private static PyObject python() {
        if (pythonModule != null) {
            return pythonModule;
        }
        synchronized (HookBridge.class) {
            if (pythonModule == null) {
                pythonModule = Python.getInstance().getModule("dg_hooks");
            }
            return pythonModule;
        }
    }

    private static void dispatch(boolean after, int hookId, Pine.CallFrame frame) {
        try {
            final Param param = new Param(frame.method, frame.thisObject, frame.args);
            if (after) {
                param.result = frame.getResult();
            }
            python().callAttr(after ? "dispatch_after" : "dispatch_before", hookId, param);
            frame.thisObject = param.thisObject;
            coerce(param.args, parameterTypes(frame.method));
            if (after && param.resultSet) {
                frame.setResult(param.result);
            }
        } catch (Throwable e) {
            Log.e(TAG, "dispatch failed for hook " + hookId, e);
        }
    }

    /** Pine hands over boxed values; reflection needs the exact boxing. */
    private static Class<?>[] parameterTypes(Member member) {
        if (member instanceof Method) {
            return ((Method) member).getParameterTypes();
        }
        if (member instanceof java.lang.reflect.Constructor) {
            return ((java.lang.reflect.Constructor<?>) member).getParameterTypes();
        }
        return null;
    }

    private static void coerce(Object[] args, Class<?>[] types) {
        if (args == null || types == null) {
            return;
        }
        final int count = Math.min(args.length, types.length);
        for (int i = 0; i < count; i++) {
            final Object value = args[i];
            if (value == null) {
                continue;
            }
            final Class<?> type = types[i];
            if (!(value instanceof Number) && type != boolean.class && type != Boolean.class
                    && type != char.class && type != Character.class) {
                continue;
            }
            if (type == int.class || type == Integer.class) {
                args[i] = ((Number) value).intValue();
            } else if (type == long.class || type == Long.class) {
                args[i] = ((Number) value).longValue();
            } else if (type == float.class || type == Float.class) {
                args[i] = ((Number) value).floatValue();
            } else if (type == double.class || type == Double.class) {
                args[i] = ((Number) value).doubleValue();
            } else if (type == short.class || type == Short.class) {
                args[i] = ((Number) value).shortValue();
            } else if (type == byte.class || type == Byte.class) {
                args[i] = ((Number) value).byteValue();
            } else if (type == char.class || type == Character.class) {
                if (value instanceof Number) {
                    args[i] = (char) ((Number) value).intValue();
                } else if (value instanceof String && ((String) value).length() == 1) {
                    args[i] = ((String) value).charAt(0);
                }
            } else if (type == boolean.class || type == Boolean.class) {
                if (value instanceof Boolean) {
                    args[i] = value;
                } else if (value instanceof Number) {
                    args[i] = ((Number) value).intValue() != 0;
                }
            }
        }
    }

    public static synchronized String ensurePine() {
        if (pineError != null) {
            return pineError;
        }
        try {
            PineConfig.debug = false;
            try {
                final ApplicationInfo info = ApplicationLoader.applicationContext.getApplicationInfo();
                PineConfig.debuggable = (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            } catch (Throwable ignored) {
            }
            Pine.ensureInitialized();
            return "";
        } catch (Throwable e) {
            pineError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "Pine init failed", e);
            return pineError;
        }
    }

    private static void addUnhook(int hookId, MethodHook.Unhook unhook) {
        List<MethodHook.Unhook> list = UNHOOKS.get(hookId);
        if (list == null) {
            list = new ArrayList<>();
            UNHOOKS.put(hookId, list);
        }
        synchronized (list) {
            list.add(unhook);
        }
    }

    private static void hookOne(int hookId, Member member) {
        final String error = ensurePine();
        if (!error.isEmpty()) {
            throw new IllegalStateException(error);
        }
        final MethodHook.Unhook unhook = Pine.hook(member, new PyHook(hookId));
        addUnhook(hookId, unhook);
    }

    /** Hooks every method with this name, including inherited declarations. */
    public static String hookAllMethods(String className, String methodName, int hookId) {
        final StringBuilder errors = new StringBuilder();
        int count = 0;
        try {
            Class<?> cls = Class.forName(className, false, HookBridge.class.getClassLoader());
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (!method.getName().equals(methodName)
                            || Modifier.isAbstract(method.getModifiers())) {
                        continue;
                    }
                    try {
                        hookOne(hookId, method);
                        count++;
                    } catch (Throwable e) {
                        append(errors, method + ": " + e.getMessage());
                    }
                }
            }
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (count > 0) {
            return "";
        }
        return errors.length() > 0 ? errors.toString() : "no method " + methodName + " in " + className;
    }

    public static String hookAllConstructors(String className, int hookId) {
        final StringBuilder errors = new StringBuilder();
        int count = 0;
        try {
            final Class<?> cls = Class.forName(className, false, HookBridge.class.getClassLoader());
            for (java.lang.reflect.Constructor<?> constructor : cls.getDeclaredConstructors()) {
                try {
                    hookOne(hookId, constructor);
                    count++;
                } catch (Throwable e) {
                    append(errors, constructor + ": " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (count > 0) {
            return "";
        }
        return errors.length() > 0 ? errors.toString() : "no constructors in " + className;
    }

    public static String hookMethod(Method method, int hookId) {
        try {
            hookOne(hookId, method);
            return "";
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static void unhook(int hookId) {
        final List<MethodHook.Unhook> list = UNHOOKS.remove(hookId);
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (MethodHook.Unhook unhook : list) {
                try {
                    unhook.unhook();
                } catch (Throwable e) {
                    Log.e(TAG, "unhook failed", e);
                }
            }
        }
    }

    public static void unhookAll() {
        for (Integer id : new ArrayList<>(UNHOOKS.keySet())) {
            unhook(id);
        }
    }

    private static void append(StringBuilder builder, String message) {
        if (builder.length() > 0) {
            builder.append("; ");
        }
        builder.append(message);
    }
}
