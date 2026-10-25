package de.robv.android.xposed;

/** 编译期占位。 */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        public void setResult(Object result) {
        }

        public Object getResult() {
            return null;
        }
    }

    public class Unhook {
        public void unhook() {
        }
    }
}
