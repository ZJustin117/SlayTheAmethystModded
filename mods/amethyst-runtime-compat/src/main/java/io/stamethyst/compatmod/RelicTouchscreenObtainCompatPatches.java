package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.relics.AbstractRelic;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

public final class RelicTouchscreenObtainCompatPatches {
    private static final String ENABLED_PROP =
        "amethyst.runtime_compat.relic_touchscreen_obtain";
    private static final boolean RELIC_TOUCHSCREEN_OBTAIN_COMPAT_ENABLED =
        readBooleanSystemProperty(ENABLED_PROP, true);

    private RelicTouchscreenObtainCompatPatches() {
    }

    @SpirePatch2(
        clz = AbstractRelic.class,
        method = "update"
    )
    public static class AbstractRelicUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isReader()) {
                        return;
                    }
                    if (!Settings.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if (!"isTouchScreen".equals(access.getFieldName())) {
                        return;
                    }
                    access.replace(
                        "{ $_ = "
                            + RelicTouchscreenObtainCompatPatches.class.getName()
                            + ".resolveTouchscreenForRelicObtain($proceed()); }"
                    );
                }
            };
        }
    }

    public static boolean resolveTouchscreenForRelicObtain(boolean originalTouchscreen) {
        if (!RELIC_TOUCHSCREEN_OBTAIN_COMPAT_ENABLED) {
            return originalTouchscreen;
        }
        return false;
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("false".equalsIgnoreCase(configured)
            || "0".equals(configured)
            || "off".equalsIgnoreCase(configured)) {
            return false;
        }
        if ("true".equalsIgnoreCase(configured)
            || "1".equals(configured)
            || "on".equalsIgnoreCase(configured)) {
            return true;
        }
        return defaultValue;
    }
}
