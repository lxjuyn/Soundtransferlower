package com.example.soundtransferlower;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

/**
 * MD3 程序化取色/背景工具。
 * 目标平台 minSdk 15：shape/渐变 drawable XML 里的 ?attr 在 API<21 会崩溃，
 * 因此所有需要跟随配色主题的圆角/渐变/状态背景一律在本类中程序化生成，
 * 颜色通过主题属性解析（布局 ?attr 与 values-night 在旧平台均安全）。
 */
public final class Md3Ui {

    private Md3Ui() {}

    /** 解析当前主题中的颜色属性（支持 attr→@color 间接引用与 values-night 暗色） */
    public static int color(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        if (!ctx.getTheme().resolveAttribute(attr, tv, true)) return 0xFF000000;
        if (tv.type == TypedValue.TYPE_ATTRIBUTE) {
            if (!ctx.getTheme().resolveAttribute(tv.data, tv, true)) return 0xFF000000;
        }
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        if (tv.resourceId != 0) return ContextCompat.getColor(ctx, tv.resourceId);
        return 0xFF000000;
    }

    /** View.setBackground 自 API 16 起才有；A4（API 15）必须走旧 API 名 */
    @SuppressWarnings("deprecation")
    public static void setBg(android.view.View v, android.graphics.drawable.Drawable d) {
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            v.setBackground(d);
        } else {
            v.setBackgroundDrawable(d);
        }
    }

    private static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** MD3 分组卡：surface-container 填充，24dp 圆角 */
    public static void applyCard(View v) {
        setBg(v, rounded(v.getContext(), color(v.getContext(), R.attr.md3SurfaceContainer), 24f));
    }

    /** 图标芯片容器：14dp 圆角，容器色由调用方指定（primary/secondary/tertiary container） */
    public static void applyChip(View v, int containerColorAttr) {
        setBg(v, rounded(v.getContext(), color(v.getContext(), containerColorAttr), 14f));
    }

    /** Hero 头卡：start→end 渐变，28dp 圆角 */
    public static void applyHero(View v) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color(c, R.attr.md3HeroStart), color(c, R.attr.md3HeroEnd)});
        g.setCornerRadius(dp(c, 28f));
        setBg(v, g);
    }

    /** 连接状态圆点 */
    public static void applyDot(View v, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color(v.getContext(), on ? R.attr.md3DotOn : R.attr.md3DotOff));
        setBg(v, d);
    }

    /** 顶栏返回键：圆形按压反馈 */
    public static void applyBackBtn(View v) {
        Context c = v.getContext();
        StateListDrawable s = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(color(c, R.attr.md3SurfaceContainerHigh));
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(0x00000000);
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(StateSet.WILD_CARD, normal);
        setBg(v, s);
    }

    /** M3 规格 SwitchCompat：轨道 52x32dp（选中 primary/未选中 highest+outline 描边），滑块 24dp 圆 */
    public static void applySwitch(SwitchCompat sw) {
        Context c = sw.getContext();
        GradientDrawable trackOn = rounded(c, color(c, R.attr.md3Primary), 16f);
        trackOn.setSize((int) dp(c, 52), (int) dp(c, 32));
        GradientDrawable trackOff = rounded(c, color(c, R.attr.md3SurfaceContainerHighest), 16f);
        trackOff.setStroke((int) dp(c, 1), color(c, R.attr.md3Outline));
        trackOff.setSize((int) dp(c, 52), (int) dp(c, 32));
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(StateSet.WILD_CARD, trackOff);
        sw.setTrackDrawable(track);

        GradientDrawable thumbOn = new GradientDrawable();
        thumbOn.setShape(GradientDrawable.OVAL);
        thumbOn.setColor(color(c, R.attr.md3OnPrimary));
        thumbOn.setSize((int) dp(c, 24), (int) dp(c, 24));
        GradientDrawable thumbOff = new GradientDrawable();
        thumbOff.setShape(GradientDrawable.OVAL);
        thumbOff.setColor(color(c, R.attr.md3Outline));
        thumbOff.setSize((int) dp(c, 24), (int) dp(c, 24));
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, thumbOn);
        thumb.addState(StateSet.WILD_CARD, thumbOff);
        int inset = (int) dp(c, 4);
        sw.setThumbDrawable(new InsetDrawable(thumb, inset, inset, inset, inset));
        sw.setShowText(false);
    }

    /** 聊天气泡：18dp 圆角、靠收发方向一侧底角收窄为 4dp */
    public static void applyBubble(View v, boolean sent) {
        Context c = v.getContext();
        GradientDrawable d = new GradientDrawable();
        d.setColor(color(c, sent ? R.attr.md3PrimaryContainer : R.attr.md3SurfaceContainerHigh));
        float r = dp(c, 18), s = dp(c, 4);
        if (sent) {
            d.setCornerRadii(new float[]{r, r, r, r, s, s, r, r});   // 右下收窄
        } else {
            d.setCornerRadii(new float[]{r, r, r, r, r, r, s, s});   // 左下收窄
        }
        setBg(v, d);
    }

    /** 药丸形按钮/徽章背景（999dp 即全圆角） */
    public static void applyFilledPill(View v, int bgColorAttr) {
        setBg(v, rounded(v.getContext(), color(v.getContext(), bgColorAttr), 999f));
    }

    /** 通用圆角背景 */
    public static void applyRounded(View v, int bgColorAttr, float radiusDp) {
        setBg(v, rounded(v.getContext(), color(v.getContext(), bgColorAttr), radiusDp));
    }

    /** 图标着色：让矢量图标跟随主题色 */
    public static void tintIcon(ImageView iv, int colorAttr) {
        if (iv == null) return;
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(color(iv.getContext(), colorAttr)));
    }


    /** 底栏 tab 药丸背景：selected=主题色容器；按压态只是淡色盖层（无 elevation，无方形阴影） */
    public static android.graphics.drawable.Drawable navTabBg(Context c, boolean selected) {
        int base = selected ? color(c, R.attr.md3PrimaryContainer) : 0x00000000;
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable normal = rounded(c, base, 999f);
        android.graphics.drawable.GradientDrawable pressed = rounded(c,
                (color(c, R.attr.md3OnSurface) & 0x00FFFFFF) | 0x1F000000, 999f); // 12% 盖层
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(android.util.StateSet.WILD_CARD, normal);
        return s;
    }

    /** 功能按钮可用/不可用统一视觉：可用=主题色容器椭圆，不可用=中性灰椭圆 */
    public static void applyBtnState(View v, boolean enabled) {
        Context c = v.getContext();
        int bgAttr = enabled ? R.attr.md3PrimaryContainer : R.attr.md3SurfaceContainerHighest;
        int fgAttr = enabled ? R.attr.md3OnPrimaryContainer : R.attr.md3OnSurfaceVariant;
        v.setAlpha(enabled ? 1f : 0.7f);
        setBg(v, rounded(c, color(c, bgAttr), 999f));
        if (v instanceof android.widget.TextView) {
            ((android.widget.TextView) v).setTextColor(color(c, fgAttr));
        }
    }

    // ==================== tag 驱动的批量应用 ====================
    // XML 中给视图打 android:tag，再对根视图调用 applyTree 即可，
    // 免去逐控件写代码；视图原有 android:background 会被覆盖。

    /** 递归遍历视图树，按 android:tag 应用 MD3 样式 */
    public static void applyTree(View root) {
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) applyTree(g.getChildAt(i));
        }
        Object tag = root.getTag();
        if (!(tag instanceof String)) return;
        String t = (String) tag;
        switch (t) {
            case "md3-card": applyCard(root); break;
            case "md3-hero": applyHero(root); break;
            case "md3-chip-primary": applyChip(root, R.attr.md3PrimaryContainer); break;
            case "md3-chip-secondary": applyChip(root, R.attr.md3SecondaryContainer); break;
            case "md3-chip-tertiary": applyChip(root, R.attr.md3TertiaryContainer); break;
            case "md3-back": applyBackBtn(root); break;
            case "md3-switch": if (root instanceof SwitchCompat) applySwitch((SwitchCompat) root); break;
            case "md3-bubble-sent": applyBubble(root, true); break;
            case "md3-bubble-received": applyBubble(root, false); break;
            case "md3-btn-filled": applyBtn(root, R.attr.md3Primary, R.attr.md3OnPrimary); break;
            case "md3-btn-tonal": applyBtn(root, R.attr.md3PrimaryContainer, R.attr.md3OnPrimaryContainer); break;
            case "md3-btn-danger": applyBtn(root, R.attr.md3Error, R.attr.md3OnError); break;
            case "md3-circle-primary": applyRounded(root, R.attr.md3PrimaryContainer, 999f); break;
            case "md3-circle-tertiary": applyRounded(root, R.attr.md3TertiaryContainer, 999f); break;
            case "md3-icon-on-tertiary-container": tintIcon((ImageView) root, R.attr.md3OnTertiaryContainer); break;
            case "md3-input": applyRounded(root, R.attr.md3SurfaceContainerHighest, 999f); break;
            case "md3-nav-bar":
                // 悬浮胶囊底栏：bg_md3_nav_pill 已在 inflate 期确定圆形轮廓（阴影随形状），
                // 这里只改颜色以跟随配色主题；重置 outline provider 防御旧机型的轮廓缓存
                if (root.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
                    ((android.graphics.drawable.GradientDrawable) root.getBackground().mutate())
                            .setColor(color(root.getContext(), R.attr.md3SurfaceContainerHigh));
                }
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    root.setElevation(dp(root.getContext(), 6f));
                    root.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                }
                break;
            case "md3-icon-primary": tintIcon((ImageView) root, R.attr.md3Primary); break;
            case "md3-icon-on-primary": tintIcon((ImageView) root, R.attr.md3OnPrimary); break;
            case "md3-icon-primary-container": tintIcon((ImageView) root, R.attr.md3OnPrimaryContainer); break;
            case "md3-icon-secondary-container": tintIcon((ImageView) root, R.attr.md3OnSecondaryContainer); break;
            case "md3-icon-tertiary-container": tintIcon((ImageView) root, R.attr.md3OnTertiaryContainer); break;
            case "md3-icon-on-surface": tintIcon((ImageView) root, R.attr.md3OnSurface); break;
            case "md3-icon-on-surface-variant": tintIcon((ImageView) root, R.attr.md3OnSurfaceVariant); break;
            case "md3-icon-outline": tintIcon((ImageView) root, R.attr.md3Outline); break;
            default: break;
        }
    }

    /** 填充按钮：999dp 全圆角背景 + 文字颜色 */
    private static void applyBtn(View v, int bgAttr, int textAttr) {
        setBg(v, rounded(v.getContext(), color(v.getContext(), bgAttr), 999f));
        if (v instanceof android.widget.TextView) {
            ((android.widget.TextView) v).setTextColor(color(v.getContext(), textAttr));
        }
        if (v instanceof ImageView) {
            tintIcon((ImageView) v, textAttr);
        }
    }
}
