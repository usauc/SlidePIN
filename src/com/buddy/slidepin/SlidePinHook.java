package com.buddy.slidepin;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 锁屏数字密码「滑动输入」。
 *
 * 注意：KeyguardPINView 没有声明 dispatchTouchEvent（继承自 ViewGroup），
 * 所以 Hook 点必须放在 ViewGroup.dispatchTouchEvent 上，再用实例类型过滤。
 */
public class SlidePinHook implements IXposedHookLoadPackage {

    private static final String TAG = "SlidePin";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String PIN_VIEW = "com.android.keyguard.KeyguardPINView";

    private static Class<?> sPinViewClass;
    private static boolean sDumped;

    private static final WeakHashMap<View, Gesture> STATES = new WeakHashMap<>();

    private static final class Gesture {
        boolean captured;
        View last;
        View first;
        float startX;
        float startY;
    }

    /** 移动超过该距离（像素）才判定为滑动，避免误吃普通点按。 */
    private static final float SLOP = 30f;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }
        try {
            sPinViewClass = XposedHelpers.findClass(PIN_VIEW, lpparam.classLoader);
        } catch (Throwable t) {
            log("PIN class not found: " + t);
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof ViewGroup)) {
                                return;
                            }
                            ViewGroup vg = (ViewGroup) param.thisObject;
                            if (!isPinView(vg)) {
                                return;
                            }
                            try {
                                onTouch(vg, (MotionEvent) param.args[0], param);
                            } catch (Throwable t) {
                                log("touch error: " + t);
                            }
                        }
                    });
            log("hook installed");
        } catch (Throwable t) {
            log("hook failed: " + t);
        }
    }

    private static boolean isPinView(View v) {
        Class<?> c = v.getClass();
        while (c != null) {
            if (c == sPinViewClass) {
                return true;
            }
            String name = c.getName();
            if (name != null && name.endsWith("KeyguardPINView")) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static void onTouch(ViewGroup root, MotionEvent ev,
                                XC_MethodHook.MethodHookParam param) {
        Gesture g = STATES.get(root);
        if (g == null) {
            g = new Gesture();
            STATES.put(root, g);
        }
        if (!sDumped) {
            sDumped = true;
            dumpTree(root, 0);
        }
        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                // 不立即接管：先记录起点，等确认是滑动再介入，
                // 这样普通点按完全走原生逻辑，不会因为本模块失效而无法输入密码。
                g.captured = false;
                g.last = null;
                g.startX = ev.getRawX();
                g.startY = ev.getRawY();
                g.first = findDigit(root, g.startX, g.startY);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!g.captured) {
                    float dx = ev.getRawX() - g.startX;
                    float dy = ev.getRawY() - g.startY;
                    if (dx * dx + dy * dy < SLOP * SLOP) {
                        break;
                    }
                    g.captured = true;
                    if (g.first != null) {
                        // 首键已经收到过 DOWN，若不补发 CANCEL，它会一直保持按压/涟漪态
                        cancelPress(g.first, ev);
                        click(g.first, g);
                    }
                }
                View hit = findDigit(root, ev.getRawX(), ev.getRawY());
                if (hit != null) {
                    click(hit, g);
                }
                param.setResult(true);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (g.captured) {
                    param.setResult(true);
                }
                g.captured = false;
                g.last = null;
                g.first = null;
                break;
            }
            default:
                break;
        }
    }

    private static void click(View target, Gesture g) {
        if (target == g.last) {
            return;
        }
        g.last = target;
        log("key " + describe(target));
        target.performClick();
    }

    /** 给已按下但不会再收到 UP 的控件补发 CANCEL，并清掉按压/涟漪状态。 */
    private static void cancelPress(View v, MotionEvent src) {
        try {
            MotionEvent cancel = MotionEvent.obtain(src);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            v.dispatchTouchEvent(cancel);
            cancel.recycle();
        } catch (Throwable ignored) {
            // 个别控件不接受合成事件，走下面的兜底
        }
        clearPressed(v, 0);
    }

    private static void clearPressed(View v, int depth) {
        if (depth > 4) {
            return;
        }
        try {
            v.setPressed(false);
            v.jumpDrawablesToCurrentState();
        } catch (Throwable ignored) {
            // 忽略
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                clearPressed(g.getChildAt(i), depth + 1);
            }
        }
    }

    private static String describe(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof TextView) {
                    CharSequence t = ((TextView) c).getText();
                    if (t != null && t.length() == 1) {
                        return t.toString();
                    }
                }
            }
        }
        return v.getClass().getSimpleName();
    }

    private static View findDigit(View root, float rawX, float rawY) {
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                View nested = findDigit(child, rawX, rawY);
                if (nested != null) {
                    return nested;
                }
            }
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (!isDigitKey(child)) {
                continue;
            }
            if (contains(child, rawX, rawY)) {
                return clickableAncestor(child);
            }
        }
        return null;
    }

    /**
     * 数字文本本身不可点击，真正处理点击的是它的容器（本 ROM 为 NumPadKey），
     * 因此向上找到第一个 clickable 的祖先再派发点击。
     */
    private static View clickableAncestor(View v) {
        View cur = v;
        while (cur != null) {
            if (cur.isClickable()) {
                return cur;
            }
            if (!(cur.getParent() instanceof View)) {
                break;
            }
            cur = (View) cur.getParent();
        }
        return v;
    }

    private static boolean isDigitKey(View v) {
        if (!(v instanceof TextView)) {
            return false;
        }
        CharSequence text = ((TextView) v).getText();
        if (text == null || text.length() != 1) {
            return false;
        }
        char c = text.charAt(0);
        return c >= '0' && c <= '9';
    }

    private static boolean contains(View v, float x, float y) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        return r.contains((int) x, (int) y);
    }

    /** 首次命中 PIN 视图时输出一次控件树，便于确认按键是 TextView 还是自绘控件。 */
    private static void dumpTree(View v, int depth) {
        if (depth > 6) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getName());
        if (v instanceof TextView) {
            sb.append(" text=").append(((TextView) v).getText());
        }
        log(sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpTree(g.getChildAt(i), depth + 1);
            }
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
