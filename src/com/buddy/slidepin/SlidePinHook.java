package com.buddy.slidepin;

import android.content.res.Resources;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 锁屏数字密码「滑动输入」。
 *
 * 设计要点（部分思路参考 SwipePin 的 Trail/margin 方案）：
 * 1. Hook 点：KeyguardPINView 未声明 dispatchTouchEvent，故挂 ViewGroup.dispatchTouchEvent 再按实例过滤。
 * 2. DOWN 不接管，仅记录起点；普通点按始终走原生逻辑。
 * 3. 移动超过 SLOP 才判定为滑动，此时给首键补发 CANCEL，避免按压态残留。
 * 4. 命中判定使用按键矩形缓存 + 滞回边距，防止手指压在键边缘时反复触发相邻键。
 * 5. 多指触摸直接放弃接管，避免乱序输入。
 */
public class SlidePinHook implements IXposedHookLoadPackage {

    private static final String TAG = "SlidePin";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String PIN_VIEW = "com.android.keyguard.KeyguardPINView";

    /** 移动超过该距离（像素）才判定为滑动。 */
    private static final float SLOP_DP = 8f;
    /** 切换按键时的滞回边距：新键矩形向内收缩该值才认，避免边缘抖动。 */
    private static final float HYSTERESIS_DP = 6f;

    private static Class<?> sPinViewClass;
    private static boolean sDumped;
    private static float sDensity = -1f;

    private static final WeakHashMap<View, Gesture> STATES = new WeakHashMap<>();
    private static final WeakHashMap<View, List<Hit>> KEYS = new WeakHashMap<>();

    private static final class Gesture {
        boolean captured;
        boolean multi;
        View last;
        Hit first;
        float startX;
        float startY;
    }

    private static final class Hit {
        final View view;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Hit(View v, int[] loc) {
            this.view = v;
            this.left = loc[0];
            this.top = loc[1];
            this.right = loc[0] + v.getWidth();
            this.bottom = loc[1] + v.getHeight();
        }

        boolean contains(float x, float y, float inset) {
            return x >= left + inset && x <= right - inset
                    && y >= top + inset && y <= bottom - inset;
        }
    }

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
                g.captured = false;
                g.multi = ev.getPointerCount() > 1;
                g.last = null;
                g.startX = ev.getRawX();
                g.startY = ev.getRawY();
                List<Hit> keys = buildKeys(root);
                KEYS.put(root, keys);
                g.first = pick(keys, g.startX, g.startY, 0f);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 第二根手指落下：放弃接管，本次手势交回系统
                g.multi = true;
                g.captured = false;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (g.multi) {
                    break;
                }
                List<Hit> keys = KEYS.get(root);
                if (keys == null) {
                    break;
                }
                if (!g.captured) {
                    float dx = ev.getRawX() - g.startX;
                    float dy = ev.getRawY() - g.startY;
                    if (dx * dx + dy * dy < slop() * slop()) {
                        break;
                    }
                    g.captured = true;
                    if (g.first != null) {
                        // 首键已收到 DOWN 却不会再收到 UP，补发 CANCEL 清掉按压态
                        cancelPress(g.first.view, ev);
                        click(g.first, g);
                    }
                }
                Hit hit = pick(keys, ev.getRawX(), ev.getRawY(), hysteresis());
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
                g.multi = false;
                g.last = null;
                g.first = null;
                KEYS.remove(root);
                break;
            }
            default:
                break;
        }
    }

    private static void click(Hit hit, Gesture g) {
        if (hit.view == g.last) {
            return;
        }
        g.last = hit.view;
        log("key " + describe(hit.view));
        hit.view.performClick();
    }

    /** 遍历 PIN 视图，收集所有数字键的可点击容器及其屏幕矩形。 */
    private static List<Hit> buildKeys(ViewGroup root) {
        List<Hit> out = new ArrayList<>(12);
        collect(root, out, 0);
        return out;
    }

    private static void collect(View v, List<Hit> out, int depth) {
        if (depth > 6 || out.size() >= 16) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out, depth + 1);
            }
            return;
        }
        if (v.getVisibility() != View.VISIBLE || !isDigitKey(v)) {
            return;
        }
        View target = clickableAncestor(v);
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            return;
        }
        out.add(new Hit(target, loc));
    }

    /**
     * 数字文本本身不可点击，真正处理点击的是它的容器（本 ROM 为 NumPadKey），
     * 因此向上找到第一个 clickable 的祖先。
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

    private static Hit pick(List<Hit> keys, float x, float y, float inset) {
        if (keys == null) {
            return null;
        }
        for (int i = 0; i < keys.size(); i++) {
            Hit h = keys.get(i);
            if (h.contains(x, y, inset)) {
                return h;
            }
        }
        return null;
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

    private static float density() {
        if (sDensity <= 0f) {
            sDensity = Resources.getSystem().getDisplayMetrics().density;
            if (sDensity <= 0f) {
                sDensity = 2f;
            }
        }
        return sDensity;
    }

    private static float slop() {
        return SLOP_DP * density();
    }

    private static float hysteresis() {
        return HYSTERESIS_DP * density();
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

    /** 首次命中 PIN 视图时输出一次控件树，便于适配其他 ROM。 */
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

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
