package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.vfx.RestartForChangesEffect;

public final class DisplaySettingsPromptCompatPatches {
    private static final String LAUNCHER_SETTINGS_PROMPT =
        "请勿在此修改，请在启动器设置中调整画质选项。";

    private DisplaySettingsPromptCompatPatches() {
    }

    @SpirePatch2(
        clz = RestartForChangesEffect.class,
        method = SpirePatch.CONSTRUCTOR
    )
    public static class RestartForChangesEffectConstructorPatch {
        public static void Postfix() {
            if (RestartForChangesEffect.TEXT != null && RestartForChangesEffect.TEXT.length > 0) {
                RestartForChangesEffect.TEXT[0] = LAUNCHER_SETTINGS_PROMPT;
            }
        }
    }
}
