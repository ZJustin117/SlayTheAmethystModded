package io.stamethyst.compatmod;

import basemod.ModLabeledButton;
import com.badlogic.gdx.files.FileHandle;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;

import java.io.File;
import java.lang.reflect.Method;

public final class CardImageReplacerFileDialogMimePatches {
    private static final String MOD_ID = "CardImageReplacer";
    private static final String REPLACE_IMAGES_CLASS = "CardImageReplacer.utils.ReplaceImages";
    private static final String ENABLED_PROP =
        "amethyst.runtime_compat.card_image_replacer_android_file_picker";
    private static Method fileChooserCallbackMethod;

    private CardImageReplacerFileDialogMimePatches() {
    }

    @SpirePatch2(
        cls = REPLACE_IMAGES_CLASS,
        method = "openFileChooser",
        paramtypez = {ModLabeledButton.class},
        requiredModId = MOD_ID,
        optional = true
    )
    public static class OpenFileChooserPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (!readBooleanSystemProperty(ENABLED_PROP, true) || !AndroidFilePickerBridge.isAvailable()) {
                return SpireReturn.Continue();
            }
            try {
                File picked = AndroidFilePickerBridge.pickFile("CardImageReplacer", "image/*");
                if (picked != null) {
                    getFileChooserCallbackMethod().invoke(null, new FileHandle(picked));
                }
                return SpireReturn.Return(null);
            } catch (Throwable throwable) {
                System.out.println(
                    "[amethyst-runtime-compat] CardImageReplacer Android image picker failed: "
                        + throwable.getClass().getSimpleName()
                        + ": "
                        + throwable.getMessage()
                );
                return SpireReturn.Continue();
            }
        }
    }

    private static Method getFileChooserCallbackMethod() throws Exception {
        if (fileChooserCallbackMethod == null) {
            fileChooserCallbackMethod = Class.forName(REPLACE_IMAGES_CLASS)
                .getMethod("FileChooserCallBack", FileHandle.class);
        }
        return fileChooserCallbackMethod;
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (normalized.length() == 0) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(normalized)
            || "1".equals(normalized)
            || "yes".equalsIgnoreCase(normalized)
            || "on".equalsIgnoreCase(normalized);
    }
}
