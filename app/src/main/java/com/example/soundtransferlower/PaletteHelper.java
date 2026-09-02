package com.example.soundtransferlower;

import android.content.Context;

/**
 * 配色（主题）选择辅助：读取/保存用户选择的调色板，并提供对应主题资源 id。
 * MainActivityNew 在 super.onCreate 之前调用 {@link #getThemeResId} 完成 setTheme。
 */
public final class PaletteHelper {

    public static final String PREF_NAME = "app_settings";
    public static final String KEY_PALETTE = "theme_palette";

    public static final String PALETTE_INDIGO = "indigo";
    public static final String PALETTE_GREEN_WHITE = "green_white";
    public static final String PALETTE_TEAL = "teal";
    public static final String PALETTE_ROSE = "rose";
    public static final String PALETTE_AMBER = "amber";
    public static final String PALETTE_MONO = "mono";

    private PaletteHelper() {}

    public static String getPalette(Context ctx) {
        if (ctx == null) return PALETTE_INDIGO;
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PALETTE, PALETTE_INDIGO);
    }

    public static void savePalette(Context ctx, String paletteId) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PALETTE, paletteId)
                .apply();
    }

    /** 当前调色板对应的主题资源 id（含亮/暗自适应） */
    public static int getThemeResId(Context ctx) {
        String p = getPalette(ctx);
        if (PALETTE_GREEN_WHITE.equals(p)) return R.style.Theme_Md3_GreenWhite;
        if (PALETTE_TEAL.equals(p)) return R.style.Theme_Md3_Teal;
        if (PALETTE_ROSE.equals(p)) return R.style.Theme_Md3_Rose;
        if (PALETTE_AMBER.equals(p)) return R.style.Theme_Md3_Amber;
        if (PALETTE_MONO.equals(p)) return R.style.Theme_Md3_Mono;
        return R.style.Theme_Md3_Indigo;
    }
}
