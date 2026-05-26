package optispire;

/*
Why does modded sts take so much more ram?
Probably just due to recompiling the game and stuff


CURRENT METHOD UTILIZING MANY DYNAMIC PATCHES:
unnacceptable.
takes too much ram just through the patching process.
need to handle things more like ManagedAtlas, changing behavior in just a few places.



notes:
MTS no mods: 550-600mb
MTS+ ANY MOD: 750-800mb
MTS basemod stslib memlogger: 1460 mb
1160, 1400mb allocated (increased after garbage collection)
MTS basemod stslib memlogger optispire: 1500 mb
A larger amount of memory was allocated for the heap, though.
+optimize the spire: 1600 mb
note: basicmod adds effectively no usage. What causes more ram usage?

mts is just not very efficient...



SPRITER ANIMATIONS??!?!?!?
I think every instance loads a LOT of textures. And there's multiple instances made of pretty much every creature and player and stuff that uses them.
process:
load images as pixmaps
create textures from pixmaps, texture -> textureregion -> sprite
store sprites in resources map
if pack (default true)
    combine into larger pixmaps

After loading all:
If pack: generate a TextureAtlas from the combined pixmap
    Dispose all existing sprites
    Set resource entries to entries of the atlas

dispose loaded pixmaps after

Then, create textures from pixmaps, convert to textureregions
later, convert to textureregions




Packmaster without opti: 3000mb
With no card images: 2600mb
Without registering anything other than loading strings: 2150mb
No packmaster (given time to settle): 1500mb
Basicmod: also around 1500mb



chunky List: 10000mb




ConstructorConstructor line 33:
Gson ends up using LinkedTreeMap for all the localization text
*/


import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.RealTexture;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.ObjectSet;
import com.badlogic.gdx.utils.Pool;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Supplier;

@SpirePatch(
        clz = CardCrawlGame.class,
        method = "render"
)
public class RamSaver {
    private static final float TICK = 5f;
    private static final int SET_LIMIT = 48;
    private static int nextSet = 0;
    private static float timer = TICK;

    //public static Texture blank = new Texture(1, 1, Pixmap.Format.RGBA8888);

    /*
        ManagedAsset pooling rules:
        When an asset is asked for, get existing one if it exists, otherwise load new one.
        If existing one is disposed (weak reference), replace it with a new reference.
        The old one is disposed during in the loading process.

        After an asset is disposed, it should not exist ANYWHERE other than possibly the referenceQueue.

        On update:
        Any assets that were garbage collected will be within the reference queue.
        If they have not already been disposed, dispose them.

            On tick:
            Process 1 "set" of assets.
            Any assets that can age (disposed of if not requested) will age.
            Any old/dead assets will be cleaned up.
     */




    @SpirePostfixPatch
    public static void update() {
        long updateStarted = RamSaverDiag.now();
        int queuedReferences = 0;
        int disposedQueuedReferences = 0;
        ManagedAsset.ManagedAssetReference o;
        while ((o = (ManagedAsset.ManagedAssetReference) referenceQueue.poll()) != null) {
            queuedReferences++;
            if (o.holder.asset == o) { //Maybe disposed and replaced while in queue
                disposedQueuedReferences++;
                RamSaverDiag.logRepeat("reference_queue_dispose", o.holder.ID, o.holder.describe() + " " + inventoryDetails());
                dispose(o.holder);
            }
            else {
                RamSaverDiag.logRepeat("reference_queue_stale", o.holder.ID, o.holder.describe() + " " + inventoryDetails());
            }
        }

        timer -= Gdx.graphics.getRawDeltaTime();
        if (timer <= 0) {
            long bucketStarted = RamSaverDiag.now();
            int setIndex = nextSet;
            timer = TICK / loadedSets.size();

            ArrayList<String> set = loadedSets.get(nextSet);
            int sizeBefore = set.size();
            int missingAssets = 0;
            int disposedOldAssets = 0;
            int agedAssets = 0;
            int keptFreshAssets = 0;
            for (int i = set.size() - 1; i >= 0; --i) {
                String id = set.get(i);
                ManagedAsset asset = loadedAssets.get(id);
                if (asset == null) {
                    missingAssets++;
                    loadedAssets.remove(id);
                    set.remove(i);
                }
                else if (!asset.isFresh()) {
                    //old news
                    disposedOldAssets++;
                    dispose(asset);
                }
                else if (asset.canAge()) {
                    agedAssets++;
                    asset.age();
                }
                else {
                    keptFreshAssets++;
                }
            }

            nextSet = (nextSet + 1) % loadedSets.size();
            RamSaverDiag.logDuration(
                    "update_bucket",
                    "set-" + setIndex,
                    bucketStarted,
                    "setIndex=" + setIndex
                            + " sizeBefore=" + sizeBefore
                            + " sizeAfter=" + set.size()
                            + " missing=" + missingAssets
                            + " disposedOld=" + disposedOldAssets
                            + " aged=" + agedAssets
                            + " keptFresh=" + keptFreshAssets
                            + " queuedReferences=" + queuedReferences
                            + " disposedQueuedReferences=" + disposedQueuedReferences
                            + " nextSet=" + nextSet
                            + " " + inventoryDetails(),
                    false
            );

            /*if (nextSet == 0) {
                SystemStats.logMemoryStats();
            }*/
        }
        else if (queuedReferences > 0) {
            RamSaverDiag.logDuration(
                    "update_reference_queue",
                    "queue",
                    updateStarted,
                    "queuedReferences=" + queuedReferences
                            + " disposedQueuedReferences=" + disposedQueuedReferences
                            + " timer=" + timer
                            + " " + inventoryDetails(),
                    false
            );
        }
    }

    private static final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private static final Map<String, ManagedAsset> loadedAssets = new HashMap<>();
    private static final Set<String> nullAssets = new HashSet<>();
    private static final Set<String> rejectedTextures = new HashSet<>();
    private static final Map<String, FakeTextureState> fakeTextureStates = new HashMap<>();
    private static final ArrayList<ArrayList<String>> loadedSets = new ArrayList<>();
    private static final int RENDER_CREATE_REPEAT_THRESHOLD = 25;
    static {
        loadedSets.add(new ArrayList<>());
        RamSaverDiag.log("init", "tickSeconds=" + TICK + " setLimit=" + SET_LIMIT + " " + inventoryDetails());
    }

    private static final Map<String, FileTextureSupplier> textures = new HashMap<>(256);
    public static class FileTextureSupplier implements Supplier<Texture> {
        final FileHandle file;
        final Pixmap.Format format;
        final boolean useMipMaps;

        protected Texture.TextureFilter minFilter = Texture.TextureFilter.Nearest;
        protected Texture.TextureFilter magFilter = Texture.TextureFilter.Nearest;
        protected Texture.TextureWrap uWrap = Texture.TextureWrap.ClampToEdge;
        protected Texture.TextureWrap vWrap = Texture.TextureWrap.ClampToEdge;

        public FileTextureSupplier(FileHandle file, Pixmap.Format format, boolean useMipMaps) {
            this.file = file;
            this.format = format;
            this.useMipMaps = useMipMaps;
        }

        @Override
        public Texture get() {
            long started = RamSaverDiag.now();
            try {
                RealTexture real = new RealTexture(file, format, useMipMaps);
                real.setFilter(this.minFilter, this.magFilter);
                real.setWrap(this.uWrap, this.vWrap);
                RamSaverDiag.logDuration(
                        "supplier_get_real_texture",
                        file.path(),
                        started,
                        "format=" + format
                                + " useMipMaps=" + useMipMaps
                                + " minFilter=" + minFilter
                                + " magFilter=" + magFilter
                                + " uWrap=" + uWrap
                                + " vWrap=" + vWrap
                                + " " + textureDetails(real),
                        true
                );
                return real;
            }
            catch (RuntimeException e) {
                RamSaverDiag.logStackRepeat(
                        "supplier_get_failed",
                        file.path(),
                        "format=" + format + " useMipMaps=" + useMipMaps + " error=" + e.getClass().getName() + ":" + e.getMessage()
                );
                throw e;
            }
        }

        public void setFilter(Texture.TextureFilter minFilter, Texture.TextureFilter magFilter) {
            this.minFilter = minFilter;
            this.magFilter = magFilter;
        }

        public void setWrap(Texture.TextureWrap u, Texture.TextureWrap v) {
            this.uWrap = u;
            this.vWrap = v;
        }
    }


    public static boolean textureExists(String ID) {
        return textures.containsKey(ID);
    }

    public static boolean isTextureRejected(String textureID) {
        if (textureID == null) {
            return false;
        }
        FakeTextureState state = getFakeTextureState(textureID);
        return rejectedTextures.contains(textureID) || (state != null && state.rejected);
    }

    public static void markTextureRejected(String textureID, String details) {
        if (textureID == null) {
            return;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        boolean first = rejectedTextures.add(textureID) || !state.rejected;
        state.rejected = true;
        state.rejectionDetails = details;
        RamSaverDiag.logRepeat(
                first ? "texture_rejected_cached" : "texture_rejected_cache_hit",
                textureID,
                details + " " + inventoryDetails()
        );
    }

    public static int recordFakeTextureCreate(String textureID, boolean rejected) {
        if (textureID == null) {
            return 0;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        int createCount;
        String knownRenderSignature;
        synchronized (state) {
            createCount = ++state.createCount;
            if (rejected) {
                state.rejected = true;
            }
            knownRenderSignature = state.renderSignature;
        }
        if (!RamSaverDiag.enabled()) {
            return 0;
        }
        String renderSignature = knownRenderSignature;
        if (renderSignature == null && shouldProbeRenderCreateStack(createCount)) {
            renderSignature = findRenderTextureCreationSignature();
        }
        if (renderSignature == null) {
            return 0;
        }
        int next;
        synchronized (state) {
            if (state.renderSignature == null) {
                state.renderSignature = renderSignature;
            }
            else {
                renderSignature = state.renderSignature;
            }
            next = ++state.renderCreateCount;
            if (next >= RENDER_CREATE_REPEAT_THRESHOLD) {
                state.repeatedRenderCreate = true;
            }
        }
        if (isRenderCreateMilestone(next)) {
            RamSaverDiag.logRepeat(
                    "repeated_render_texture_create",
                    textureID,
                    "count=" + next
                            + " createCount=" + createCount
                            + " rejected=" + rejected
                            + " renderStack=" + renderSignature
                            + " " + inventoryDetails()
            );
        }
        return next;
    }

    public static boolean isRepeatedRenderTexture(String textureID) {
        FakeTextureState state = getFakeTextureState(textureID);
        return state != null && state.repeatedRenderCreate;
    }

    public static int[] getCachedTextureSize(String textureID) {
        FakeTextureState state = getFakeTextureState(textureID);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.knowSize ? new int[] { state.width, state.height } : null;
        }
    }

    public static void cacheTextureSize(String textureID, int width, int height) {
        if (textureID == null || width <= 0 || height <= 0) {
            return;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        synchronized (state) {
            state.width = width;
            state.height = height;
            state.knowSize = true;
        }
    }

    public static void registerTexture(String textureID, FileTextureSupplier texSupplier) {
        if (textureID == null) {
            return;
        }
        FakeTextureState state = getOrCreateFakeTextureState(textureID);
        boolean replacing = textures.containsKey(textureID);
        synchronized (state) {
            if (state.supplier != null && replacing) {
                RamSaverDiag.logRepeat(
                        "register_texture_reuse",
                        textureID,
                        "supplier=" + RamSaverDiag.describeObject(state.supplier) + " " + inventoryDetails()
                );
                return;
            }
            state.supplier = texSupplier;
        }
        textures.put(textureID, texSupplier);
        RamSaverDiag.logStackRepeat(
                "register_texture",
                textureID,
                "replacing=" + replacing
                        + " supplier=" + RamSaverDiag.describeObject(texSupplier)
                        + " file=" + (texSupplier == null ? "null" : RamSaverDiag.safe(texSupplier.file.path()))
                        + " " + inventoryDetails()
        );
    }

    private static Texture makeTexture(String path) {
        try {
            Texture t = new Texture(path);
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            RamSaverDiag.logStackRepeat("make_texture", path, textureDetails(t));
            return t;
        }
        catch (Exception ignored) {
            System.out.println("Failed to load texture " + path);
            RamSaverDiag.logStackRepeat("make_texture_failed", path, "error=" + ignored.getClass().getName() + ":" + ignored.getMessage());
        }
        return null;
    }

    public static <T> T getAsset(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            asset.refresh();

            //If item has been disposed, will return null
            //This results in loading the item again, replacing entry in loaded assets
            //And existing entry in loadedSets will not be modified
            T item = asset.item();
            if (item == null) {
                RamSaverDiag.logRepeat("asset_empty", id, asset.describe() + " " + inventoryDetails());
            }
            else {
                RamSaverDiag.logRepeat("asset_hit", id, asset.describe() + " item=" + RamSaverDiag.describeObject(item));
            }
            return item;
        }
        RamSaverDiag.logStackRepeat("asset_miss", id, inventoryDetails());
        return null;
    }
    public static ManagedAsset getAssetHolder(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            asset.refresh();
            RamSaverDiag.logRepeat("asset_holder_hit", id, asset.describe());
        }
        else {
            RamSaverDiag.logStackRepeat("asset_holder_miss", id, inventoryDetails());
        }
        return asset;
    }

    //Load methods can be called either fresh, or with an old invalid version still within maps
    public static Texture loadTexture(String id, boolean canAge) {
        long started = RamSaverDiag.now();
        if (id == null) {
            RamSaverDiag.logStackRepeat("load_texture_null_id", "null", inventoryDetails());
            return null;
        }
        Supplier<Texture> supplier = textures.get(id);
        if (supplier == null) {
            System.out.println("Attempted to load unknown texture " + id);
            RamSaverDiag.logStackRepeat("load_texture_unknown", id, inventoryDetails());
            return null;
        }
        Texture t = supplier.get();
        if (t == null) { //nulls get saved permanently. Usually means invalid filepath.
            ManagedAsset holder = managedAssetPool.obtain();
            holder.canAge = false;
            holder.setAsset(id, null, ManagedAsset.AssetType.REGION);
            loadedAssets.put(id, holder);
            nullAssets.add(id);
            RamSaverDiag.logDuration(
                    "load_texture_null",
                    id,
                    started,
                    "canAge=" + canAge + " supplier=" + RamSaverDiag.describeObject(supplier) + " " + holder.describe() + " " + inventoryDetails(),
                    true
            );
            return null;
        }
        ManagedAsset holder = managedAssetPool.obtain();
        holder.setAsset(id, t, ManagedAsset.AssetType.TEXTURE);
        holder.canAge = canAge;
        ManagedAsset old = loadedAssets.put(id, holder);
        boolean createdSet = false;
        boolean appendedToExistingSet = false;
        //For this to be called, old item is null due to GC, not yet disposed
        //If not null, already exists in a set.
        if (old == null) {
            //Store in a set, then return
            for (ArrayList<String> set : loadedSets) {
                if (set.size() < SET_LIMIT) {
                    set.add(id);
                    holder.set = set;
                    appendedToExistingSet = true;
                    RamSaverDiag.logDuration(
                            "load_texture",
                            id,
                            started,
                            "canAge=" + canAge
                                    + " replaced=false appendedToExistingSet=true createdSet=false setSize=" + set.size()
                                    + " " + textureDetails(t)
                                    + " " + holder.describe()
                                    + " " + inventoryDetails(),
                            true
                    );
                    return t;
                }
            }
            ArrayList<String> newSet = new ArrayList<>(SET_LIMIT);
            newSet.add(id);
            holder.set = newSet;
            loadedSets.add(newSet);
            createdSet = true;
        }
        else {
            //Properly dispose of it.
            holder.set = old.set;
            dispose(old, false);
        }
        RamSaverDiag.logDuration(
                "load_texture",
                id,
                started,
                "canAge=" + canAge
                        + " replaced=" + (old != null)
                        + " appendedToExistingSet=" + appendedToExistingSet
                        + " createdSet=" + createdSet
                        + " " + textureDetails(t)
                        + " " + holder.describe()
                        + " " + inventoryDetails(),
                true
        );
        return t;
    }

    public static TextureAtlas.AtlasRegion loadRegion(String id, TextureAtlas.AtlasRegion region, ManagedAsset parent, boolean canAge) {
        long started = RamSaverDiag.now();
        ManagedAsset holder = managedAssetPool.obtain();
        holder.setAsset(id, region, ManagedAsset.AssetType.REGION);
        holder.canAge = canAge;
        holder.parent = parent;
        parent.dependent.add(holder);

        ManagedAsset old = loadedAssets.put(id, holder);
        boolean createdSet = false;
        boolean appendedToExistingSet = false;
        //For this to be called, old item is null due to GC
        //If not null, already exists in a set.
        if (old == null) {
            //Store in a set, then return
            for (ArrayList<String> set : loadedSets) {
                if (set.size() < SET_LIMIT) {
                    set.add(id);
                    holder.set = set;
                    appendedToExistingSet = true;
                    RamSaverDiag.logDuration(
                            "load_region",
                            id,
                            started,
                            "canAge=" + canAge
                                    + " replaced=false appendedToExistingSet=true createdSet=false setSize=" + set.size()
                                    + " region=" + describeRegion(region)
                                    + " parent=" + (parent == null ? "null" : parent.describe())
                                    + " " + holder.describe()
                                    + " " + inventoryDetails(),
                            true
                    );
                    return region;
                }
            }
            ArrayList<String> newSet = new ArrayList<>(SET_LIMIT);
            newSet.add(id);
            holder.set = newSet;
            loadedSets.add(newSet);
            createdSet = true;
        }
        else {
            //Properly dispose of it.
            holder.set = old.set;
            dispose(old, false);
        }
        RamSaverDiag.logDuration(
                "load_region",
                id,
                started,
                "canAge=" + canAge
                        + " replaced=" + (old != null)
                        + " appendedToExistingSet=" + appendedToExistingSet
                        + " createdSet=" + createdSet
                        + " region=" + describeRegion(region)
                        + " parent=" + (parent == null ? "null" : parent.describe())
                        + " " + holder.describe()
                        + " " + inventoryDetails(),
                true
        );
        return region;
    }

    public static FileTextureSupplier getTextureSupplier(String id) {
        return textures.get(id);
    }
    public static Texture getExistingTexture(String id) {
        Texture t = getAsset(id);
        boolean usable = t != null && t.getTextureObjectHandle() != 0;
        RamSaverDiag.logRepeat("get_existing_texture", id, "usable=" + usable + " " + textureDetails(t));
        return usable ? t : null;
    }
    public static Texture getTexture(Texture original, String id) {
        return getTexture(original, id, true);
    }
    public static Texture getTexture(Texture original, String id, boolean canAge) {
        long started = RamSaverDiag.now();
        Texture t = getAsset(id); //get first, if t matches original will cause a refresh

        if (original != null) {
            RamSaverDiag.logRepeat("get_texture_original", id, "canAge=" + canAge + " original=" + textureDetails(original));
            return original;
        }

        if (nullAssets.contains(id)) {
            RamSaverDiag.logRepeat("get_texture_null_asset", id, "canAge=" + canAge + " " + inventoryDetails());
            return t;
        }

        if (t != null && t.getTextureObjectHandle() != 0) {
            RamSaverDiag.logRepeat("get_texture_cache_hit", id, "canAge=" + canAge + " " + textureDetails(t));
            return t;
        }

        RamSaverDiag.logStackRepeat("get_texture_cache_miss", id, "canAge=" + canAge + " existing=" + textureDetails(t) + " " + inventoryDetails());
        Texture loaded = loadTexture(id, canAge);
        RamSaverDiag.logDuration(
                "get_texture_load_path",
                id,
                started,
                "canAge=" + canAge + " loaded=" + textureDetails(loaded) + " " + inventoryDetails(),
                false
        );
        return loaded;
    }
    public static TextureAtlas.AtlasRegion getTextureAsRegion(TextureAtlas.AtlasRegion original, String id) {
        return getTextureAsRegion(original, id, false);
    }
    public static TextureAtlas.AtlasRegion getTextureAsRegion(TextureAtlas.AtlasRegion original, String id, boolean canAge) {
        long started = RamSaverDiag.now();
        //See if already loaded
        String regionID = id + "RGN";
        TextureAtlas.AtlasRegion region = getAsset(regionID);

        if (original != null) {
            RamSaverDiag.logRepeat("get_region_original", regionID, "source=" + RamSaverDiag.safe(id) + " region=" + describeRegion(original));
            return original;
        }

        if (region != null) {
            RamSaverDiag.logRepeat("get_region_cache_hit", regionID, "source=" + RamSaverDiag.safe(id) + " region=" + describeRegion(region));
            return region;
        }

        //Get/load texture
        RamSaverDiag.logStackRepeat("get_region_cache_miss", regionID, "source=" + RamSaverDiag.safe(id) + " canAge=" + canAge + " " + inventoryDetails());
        Texture t = getTexture(null, id, canAge);
        if (t == null) {
            RamSaverDiag.logDuration("get_region_null_texture", regionID, started, "source=" + RamSaverDiag.safe(id) + " canAge=" + canAge, true);
            return null;
        }

        //Make atlasregion
        ManagedAsset texture = loadedAssets.get(id);
        region = new TextureAtlas.AtlasRegion(t, 0, 0, t.getWidth(), t.getHeight());

        TextureAtlas.AtlasRegion loaded = loadRegion(regionID, region, texture, false); //the region itself doesn't need to age
        RamSaverDiag.logDuration(
                "get_region_load_path",
                regionID,
                started,
                "source=" + RamSaverDiag.safe(id)
                        + " canAge=" + canAge
                        + " texture=" + textureDetails(t)
                        + " region=" + describeRegion(loaded)
                        + " " + inventoryDetails(),
                false
        );
        return loaded;
    }

    public static void age(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            asset.age();
            RamSaverDiag.logRepeat("age_asset", id, asset.describe());
        }
        else {
            RamSaverDiag.logRepeat("age_missing_asset", id, inventoryDetails());
        }
    }
    public static void dispose(String id) {
        ManagedAsset asset = loadedAssets.get(id);
        if (asset != null) {
            dispose(asset, true);
        }
        else {
            RamSaverDiag.logRepeat("dispose_missing_asset", id, inventoryDetails());
        }
    }
    private static void dispose(ManagedAsset asset) {
        dispose(asset, true);
    }
    private static void dispose(ManagedAsset asset, boolean removeKey) {
        long started = RamSaverDiag.now();
        String id = asset.ID;
        String before = asset.describe();
        asset.dispose();
        if (removeKey) {
            loadedAssets.remove(asset.ID);
            if (asset.set != null)
                asset.set.remove(asset.ID);
        }
        managedAssetPool.free(asset);
        RamSaverDiag.logDuration(
                "dispose_asset",
                id,
                started,
                "removeKey=" + removeKey + " before=" + before + " " + inventoryDetails(),
                true
        );
    }

    private static String inventoryDetails() {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        return "loadedAssets=" + (loadedAssets == null ? -1 : loadedAssets.size())
                + " nullAssets=" + (nullAssets == null ? -1 : nullAssets.size())
                + " loadedSets=" + (loadedSets == null ? -1 : loadedSets.size())
                + " registeredTextures=" + (textures == null ? -1 : textures.size())
                + " fakeTextureStates=" + (fakeTextureStates == null ? -1 : fakeTextureStates.size());
    }

    private static FakeTextureState getFakeTextureState(String textureID) {
        if (textureID == null) {
            return null;
        }
        synchronized (fakeTextureStates) {
            return fakeTextureStates.get(textureID);
        }
    }

    private static FakeTextureState getOrCreateFakeTextureState(String textureID) {
        synchronized (fakeTextureStates) {
            FakeTextureState state = fakeTextureStates.get(textureID);
            if (state == null) {
                state = new FakeTextureState(textureID);
                fakeTextureStates.put(textureID, state);
            }
            return state;
        }
    }

    private static boolean shouldProbeRenderCreateStack(int createCount) {
        return createCount <= 3
                || createCount == 5
                || createCount == 10
                || createCount == 25
                || createCount == 100
                || createCount == 1000
                || createCount % 10000 == 0;
    }

    private static boolean isRenderCreateMilestone(int renderCreateCount) {
        return renderCreateCount == 25
                || renderCreateCount == 100
                || renderCreateCount == 1000
                || renderCreateCount % 10000 == 0;
    }

    private static String findRenderTextureCreationSignature() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        String creator = null;
        String loader = null;
        String render = null;
        String publisher = null;
        for (StackTraceElement element : trace) {
            String className = element.getClassName();
            String methodName = element.getMethodName();
            if (className == null || methodName == null) {
                continue;
            }
            if (className.startsWith("optispire.RamSaver") || className.startsWith("optispire.RamSaverDiag")) {
                continue;
            }
            if (className.equals(Thread.class.getName())
                    || className.equals(Texture.class.getName())
                    || className.equals(RealTexture.class.getName())
                    || className.equals("com.badlogic.gdx.graphics.GLTexture")) {
                continue;
            }
            if (creator == null) {
                creator = simpleFrame(element);
            }
            if (loader == null && className.indexOf("TextureLoader") >= 0) {
                loader = simpleFrame(element);
            }
            if (render == null && ("render".equals(methodName) || methodName.endsWith("Render"))) {
                render = simpleFrame(element);
            }
            if (publisher == null && (className.indexOf("BaseMod") >= 0 || methodName.indexOf("PreRoomRender") >= 0)) {
                publisher = simpleFrame(element);
            }
        }
        if (render == null) {
            return null;
        }
        String source = loader == null ? creator : loader;
        if (source == null) {
            return null;
        }
        return source + " <- " + render + (publisher == null ? "" : " <- " + publisher);
    }

    private static String simpleFrame(StackTraceElement element) {
        return element.getClassName() + '#' + element.getMethodName() + ':' + element.getLineNumber();
    }

    private static class FakeTextureState {
        final String textureID;
        FileTextureSupplier supplier;
        boolean rejected;
        String rejectionDetails;
        int createCount;
        int renderCreateCount;
        String renderSignature;
        boolean repeatedRenderCreate;
        boolean knowSize;
        int width;
        int height;

        FakeTextureState(String textureID) {
            this.textureID = textureID;
        }
    }

    private static String textureDetails(Texture texture) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (texture == null) {
            return "texture=null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("texture=").append(RamSaverDiag.describeObject(texture));
        builder.append(" fake=").append(texture.isFake);
        if (texture.file != null) {
            builder.append(" file=").append(RamSaverDiag.safe(texture.file.path()));
        }
        if (!texture.isFake) {
            try {
                builder.append(" handle=").append(texture.getTextureObjectHandle());
                builder.append(" size=").append(texture.getWidth()).append('x').append(texture.getHeight());
            }
            catch (RuntimeException e) {
                builder.append(" detailError=").append(e.getClass().getName()).append(':').append(e.getMessage());
            }
        }
        return builder.toString();
    }

    private static String describeRegion(TextureAtlas.AtlasRegion region) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (region == null) {
            return "null";
        }
        return RamSaverDiag.describeObject(region)
                + " name=" + RamSaverDiag.safe(region.name)
                + " index=" + region.index
                + " region=" + region.getRegionX() + ',' + region.getRegionY() + ' '
                + region.getRegionWidth() + 'x' + region.getRegionHeight()
                + " texture=" + textureDetails(region.getTexture());
    }

    private static final Pool<ManagedAsset> managedAssetPool = new Pool<ManagedAsset>(128) {
        @Override
        protected ManagedAsset newObject() {
            return new ManagedAsset();
        }
    };

    private static class ManagedAsset implements Pool.Poolable {
        String ID = "";
        ManagedAssetReference asset = null;

        List<String> set = null;
        ManagedAsset parent = null;
        final List<ManagedAsset> dependent = new ArrayList<>();
        AssetType type = AssetType.NONE;

        private boolean fresh = true;
        private boolean canAge = false;
        private int[] disposeParams = empty;

        enum AssetType {
            NONE,
            TEXTURE,
            ATLAS,
            REGION
        }

        public void setAsset(String ID, Object o, AssetType type) {
            this.ID = ID;
            this.type = type;
            asset = new ManagedAssetReference(this, o, referenceQueue);
            switch (type) {
                case TEXTURE:
                    disposeParams = new int[]{((Texture) o).getTextureObjectHandle() };
                    break;
                case ATLAS:
                    ObjectSet<Texture> textures = ((TextureAtlas) o).getTextures();
                    disposeParams = new int[textures.size];
                    int i = 0;
                    for (Texture t : textures)
                        disposeParams[i] = t.getTextureObjectHandle();
                    break;
                case REGION:
                    break;
            }
            RamSaverDiag.logStackRepeat("managed_asset_set", ID, describe() + " item=" + RamSaverDiag.describeObject(o));
        }
        public void setNull(String ID) {
            this.ID = ID;
            this.type = AssetType.REGION;
            asset = new LockedNullReference(this, referenceQueue);
            canAge = false;
            RamSaverDiag.logStackRepeat("managed_asset_set_null", ID, describe());
        }

        public boolean canAge() {
            return canAge;
        }

        public boolean isFresh() {
            return parent != null ? parent.isFresh() : fresh;
        }

        public void age() {
            fresh = false;
            RamSaverDiag.logRepeat("managed_asset_age", ID, describe());
        }

        public void refresh() {
            if ((fresh = (asset instanceof LockedNullReference || (asset.get() != null))) && parent != null)
                parent.refresh();
        }

        @SuppressWarnings("unchecked")
        public <T> T item() {
            return (T) asset.get();
        }

        //Dispose of texture, clear reference, make it old
        public void dispose() {
            RamSaverDiag.logRepeat("managed_asset_dispose_begin", ID, describe());
            for (ManagedAsset child : dependent) {
                child.parent = null;
                child.dispose();
            }
            dependent.clear();

            for (int handle : disposeParams) {
                if (handle != 0) {
                    RamSaverDiag.logRepeat("delete_texture_handle", ID, "handle=" + handle + " " + describe());
                    Gdx.gl.glDeleteTexture(handle);
                }
            }

            parent = null;

            asset.clear();
            age();
        }

        public String describe() {
            if (!RamSaverDiag.enabled()) {
                return "";
            }
            Object item = asset == null ? null : asset.get();
            return "id=" + RamSaverDiag.safe(ID)
                    + " type=" + type
                    + " canAge=" + canAge
                    + " fresh=" + fresh
                    + " parent=" + (parent == null ? "null" : RamSaverDiag.safe(parent.ID))
                    + " dependents=" + dependent.size()
                    + " setSize=" + (set == null ? -1 : set.size())
                    + " disposeHandles=" + disposeParams.length
                    + " item=" + RamSaverDiag.describeObject(item);
        }

        @Override
        public void reset() {
            if (asset != null)
                asset.clear();
            ID = "";
            disposeParams = empty;
            type = AssetType.NONE;

            fresh = true;
            canAge = false;

            set = null;
            parent = null;
            dependent.clear();
        }

        private static final int[] empty = new int[] { };

        static class ManagedAssetReference extends WeakReference<Object> {
            final ManagedAsset holder;
            public ManagedAssetReference(ManagedAsset holder, Object referent, ReferenceQueue<? super Object> q) {
                super(referent, q);
                this.holder = holder;
            }
        }
        static class LockedNullReference extends ManagedAssetReference {
            private static final Object lock = new Object();
            public LockedNullReference(ManagedAsset holder, ReferenceQueue<? super Object> q) {
                super(holder, lock, q);
            }

            @Override
            public Object get() {
                return null;
            }
        }
    }
}

