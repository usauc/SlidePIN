package de.robv.android.xposed;

import java.lang.reflect.Method;

/** 编译期占位。 */
public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException();
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException();
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException();
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException();
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        throw new UnsupportedOperationException();
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        throw new UnsupportedOperationException();
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        throw new UnsupportedOperationException();
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                        Object... parameterTypes) {
        throw new UnsupportedOperationException();
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException();
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName,
                                                                    XC_MethodHook callback) {
        throw new UnsupportedOperationException();
    }
}
