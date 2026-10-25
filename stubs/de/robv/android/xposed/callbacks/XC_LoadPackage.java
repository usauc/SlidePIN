package de.robv.android.xposed.callbacks;

/** 编译期占位。 */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public String processName;
        public boolean isFirstApplication;
    }
}
