package de.robv.android.xposed;

public abstract class XC_MethodHook {
    protected XC_MethodHook(int priority) { }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }

    public static class MethodHookParam {
        public Object[] args;
        public void setResult(Object result) { throw new UnsupportedOperationException(); }
    }

    public class Unhook { }
}
