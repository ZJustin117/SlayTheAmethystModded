package optispire.patches;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.brashmonkey.spriter.Data;
import com.brashmonkey.spriter.FileReference;
import com.brashmonkey.spriter.LibGdx.LibGdxLoader;
import com.evacipated.cardcrawl.modthespire.lib.*;
import javassist.CtBehavior;
import optispire.RamSaverDiag;

public class ChangeSpriterLoader {
    @SpirePatch2(
            clz = LibGdxLoader.class,
            method = SpirePatch.CONSTRUCTOR,
            paramtypez = { Data.class, int.class, int.class }
    )
    public static class NeverPack {
        @SpirePostfixPatch
        public static void No(@ByRef boolean[] ___pack) {
            RamSaverDiag.logStackRepeat("spriter_disable_pack", "LibGdxLoader", "oldPack=" + ___pack[0]);
            ___pack[0] = false;
        }
    }

    @SpirePatch2(
            clz = LibGdxLoader.class,
            method = "loadResource",
            paramtypez = { FileReference.class }
    )
    public static class NoPixmap {
        @SpireInsertPatch(
                locator = Locator.class
        )
        public static SpireReturn<Sprite> justMakeTheSprite(FileReference ref, FileHandle ___f, Data ___data) {
            long started = RamSaverDiag.now();
            Texture t = new Texture(___f);
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            int width = (int)___data.getFile(ref.folder, ref.file).size.width;
            int height = (int)___data.getFile(ref.folder, ref.file).size.height;
            TextureRegion texRegion = new TextureRegion(t, width, height);
            RamSaverDiag.logDuration(
                    "spriter_make_sprite",
                    ___f.path(),
                    started,
                    "refFolder=" + ref.folder
                            + " refFile=" + ref.file
                            + " declaredSize=" + width + 'x' + height
                            + " textureFake=" + t.isFake,
                    true
            );
            return SpireReturn.Return(new Sprite(texRegion));
        }

        private static class Locator extends SpireInsertLocator {
            @Override
            public int[] Locate(CtBehavior ctBehavior) throws Exception {
                Matcher finalMatcher = new Matcher.NewExprMatcher("com.badlogic.gdx.graphics.Pixmap");
                return LineFinder.findInOrder(ctBehavior, finalMatcher);
            }
        }
    }

    @SpirePatch2(
            clz = LibGdxLoader.class,
            method = "finishLoading"
    )
    public static class JustDont {
        @SpirePrefixPatch
        public static SpireReturn<Void> no() {
            RamSaverDiag.logStackRepeat("spriter_skip_finish_loading", "LibGdxLoader", "finishLoading skipped");
            return SpireReturn.Return();
        }
    }
}
