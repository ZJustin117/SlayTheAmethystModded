package optispire.patches;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Affine2;
import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import optispire.RamSaverDiag;

public class HandleRenderingFakes {
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, Affine2.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    TextureRegion.class, float.class, float.class, Affine2.class
            }
    )
    public static class FakeRegion {
        private static Texture temp = null;
        @SpirePrefixPatch
        public static void loadTex(TextureRegion region) {
            /*if (region instanceof ManagedAtlas.ManagedRegion) {
                ((ManagedAtlas.ManagedRegion) region).prepTexture();
            }*/
            Texture t = region.getTexture();
            if (t.isFake) {
                long started = RamSaverDiag.now();
                RamSaverDiag.logStackRepeat(
                        "draw_region_fake_texture",
                        textureKey(t),
                        "region=" + regionDetails(region) + " texture=" + textureDetails(t)
                );
                temp = t;
                region.setTexture(t.getRealTexture());
                RamSaverDiag.logDuration(
                        "draw_region_materialize",
                        textureKey(t),
                        started,
                        "region=" + regionDetails(region) + " realTexture=" + textureDetails(region.getTexture()),
                        false
                );
            }
        }

        @SpirePostfixPatch
        public static void nullTex(TextureRegion region) {
            /*if (region instanceof ManagedAtlas.ManagedRegion) {
                ((ManagedAtlas.ManagedRegion) region).nullTexture();
            }*/
            if (temp != null) {
                RamSaverDiag.logRepeat(
                        "draw_region_restore_fake_texture",
                        textureKey(temp),
                        "region=" + regionDetails(region) + " fake=" + textureDetails(temp)
                );
                region.setTexture(temp);
                temp = null;
            }
        }
    }

    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class,
                    int.class, int.class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class,
                    int.class, int.class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float.class, float.class, float.class, float.class
            }
    )
    @SpirePatch2(
            clz = SpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float[].class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float[].class, int.class, int.class
            }
    )
    @SpirePatch2(
            clz = PolygonSpriteBatch.class,
            method = "draw",
            paramtypez = {
                    Texture.class, float[].class, int.class, int.class,
                    short[].class, int.class, int.class
            }
    )
    public static class FakeTextures {
        @SpirePrefixPatch
        public static void handleFakeTexture(@ByRef Texture[] texture) {
            if (texture[0] == null) return;
            Texture original = texture[0];
            if (original.isFake) {
                long started = RamSaverDiag.now();
                RamSaverDiag.logStackRepeat("draw_texture_fake", textureKey(original), textureDetails(original));
                texture[0] = original.getRealTexture();
                RamSaverDiag.logDuration(
                        "draw_texture_materialize",
                        textureKey(original),
                        started,
                        "realTexture=" + textureDetails(texture[0]),
                        false
                );
                return;
            }
            texture[0] = texture[0].getRealTexture();
        }
    }

    private static String textureKey(Texture texture) {
        if (texture == null || texture.file == null) {
            return "null";
        }
        return texture.file.path();
    }

    private static String textureDetails(Texture texture) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (texture == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(RamSaverDiag.describeObject(texture));
        builder.append(" fake=").append(texture.isFake);
        if (texture.file != null) {
            builder.append(" file=").append(RamSaverDiag.safe(texture.file.path()));
        }
        if (!texture.isFake) {
            builder.append(" handle=").append(texture.getTextureObjectHandle());
            builder.append(" size=").append(texture.getWidth()).append('x').append(texture.getHeight());
        }
        return builder.toString();
    }

    private static String regionDetails(TextureRegion region) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (region == null) {
            return "null";
        }
        return RamSaverDiag.describeObject(region)
                + " region=" + region.getRegionX() + ',' + region.getRegionY() + ' '
                + region.getRegionWidth() + 'x' + region.getRegionHeight();
    }
}
