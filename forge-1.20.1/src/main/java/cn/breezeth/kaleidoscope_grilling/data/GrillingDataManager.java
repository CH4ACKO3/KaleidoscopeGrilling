package cn.breezeth.kaleidoscope_grilling.data;

import cn.breezeth.kaleidoscope_grilling.network.GrillingNetwork;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

public final class GrillingDataManager extends SimpleJsonResourceReloadListener {
  private static final Gson GSON = new Gson();
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final Map<String, Skewer> BUILT_IN_SKEWERS = loadBuiltInSkewers();
  private static final Map<String, String> BUILT_IN_KINDS = loadBuiltInKinds();

  public record Skewer(
      List<List<String>> ingredients,
      String cookedResult,
      String threadingResult,
      String effect,
      int effectSeconds,
      String rawModel,
      String cookedModel,
      String eatingAnimation) {}

  public record ScriptSkewerRecipe(
      String id,
      List<List<String>> ingredients,
      String cookedResult,
      String threadingResult,
      String effect,
      int effectSeconds,
      String rawModel,
      String cookedModel,
      String eatingAnimation) {}

  private static volatile Map<String, Skewer> SKEWERS = Map.of();
  private static volatile Map<String, String> KINDS = Map.of();
  private static volatile Map<String, Skewer> SCRIPT_SKEWERS = Map.of();
  private record DisplaySnapshot(
      Map<String, Skewer> loaded, Map<String, Skewer> scripts, Map<String, Skewer> merged) {}

  private static volatile DisplaySnapshot displaySnapshot;
  private static final Map<String, Skewer> PENDING_SCRIPT_SKEWERS =
      new ConcurrentHashMap<>();

  public GrillingDataManager() {
    super(GSON, "grilling");
  }

  public static void register(AddReloadListenerEvent event) {
    event.addListener(new GrillingDataManager());
  }

  public static Skewer skewer(String id) {
    return SKEWERS.get(id);
  }

  public static Skewer skewerForDisplay(String id) {
    Skewer loaded = SKEWERS.get(id);
    return loaded != null ? loaded : BUILT_IN_SKEWERS.get(id);
  }

  /** Finds data by either the raw item id or its configured cooked item id. */
  public static Skewer skewerForItem(String id) {
    Map<String, Skewer> available = skewersForDisplay();
    Skewer direct = available.get(id);
    if (direct != null) return direct;
    return available.values().stream()
        .filter(skewer -> id.equals(skewer.cookedResult()))
        .findFirst()
        .orElse(null);
  }

  public static Map<String, Skewer> skewers() {
    Map<String, Skewer> result = new HashMap<>(SKEWERS);
    result.putAll(SCRIPT_SKEWERS);
    return Map.copyOf(result);
  }

  public static Map<String, Skewer> skewersForDisplay() {
    // Reloads, script commits and network sync replace these immutable maps.
    Map<String, Skewer> loaded = SKEWERS;
    Map<String, Skewer> scripts = SCRIPT_SKEWERS;
    DisplaySnapshot snapshot = displaySnapshot;
    if (snapshot != null && snapshot.loaded() == loaded && snapshot.scripts() == scripts) {
      return snapshot.merged();
    }
    Map<String, Skewer> result = new HashMap<>(BUILT_IN_SKEWERS);
    result.putAll(loaded);
    result.putAll(scripts);
    snapshot = new DisplaySnapshot(loaded, scripts, Map.copyOf(result));
    displaySnapshot = snapshot;
    return snapshot.merged();
  }

  /** Registers a mode-4 recipe from the optional KubeJS integration. */
  public static void registerThreadingRecipe(
      ResourceLocation id,
      List<List<String>> ingredients,
      ResourceLocation result) {
    if (id == null || result == null || ingredients == null || ingredients.isEmpty()) return;
    List<List<String>> slots =
        ingredients.stream().map(List::copyOf).filter(slot -> !slot.isEmpty()).toList();
    if (slots.isEmpty() || slots.size() > 3) return;
    PENDING_SCRIPT_SKEWERS.put(
        id.toString(),
        new Skewer(slots, "", result.toString(), "", 0, "provided", "provided", "provided"));
  }

  /** Registers modes 1 and 2: ingredients -> raw skewer -> cooked skewer. */
  public static void registerSkewerRecipe(
      ResourceLocation raw,
      ResourceLocation cooked,
      List<List<String>> ingredients,
      String effect,
      int effectSeconds,
      String rawModel,
      String cookedModel,
      String eatingAnimation) {
    if (raw == null || cooked == null || ingredients == null || ingredients.isEmpty()) return;
    List<List<String>> slots =
        ingredients.stream().map(List::copyOf).filter(slot -> !slot.isEmpty()).toList();
    if (slots.isEmpty() || slots.size() > 3) return;
    PENDING_SCRIPT_SKEWERS.put(
        raw.toString(),
        new Skewer(
            slots,
            cooked.toString(),
            "",
            effect,
            Math.max(0, effectSeconds),
            modelSource(rawModel),
            modelSource(cookedModel),
            eatingAnimation(eatingAnimation)));
  }

  /** Registers mode 3: an existing raw skewer gains a cooked result. */
  public static void registerCookingRecipe(
      ResourceLocation raw,
      ResourceLocation cooked,
      List<List<String>> displayIngredients,
      String effect,
      int effectSeconds,
      String rawModel,
      String cookedModel,
      String eatingAnimation) {
    if (raw == null || cooked == null) return;
    List<List<String>> slots =
        displayIngredients == null
            ? List.of()
            : displayIngredients.stream()
                .map(List::copyOf)
                .filter(slot -> !slot.isEmpty())
                .limit(3)
                .toList();
    PENDING_SCRIPT_SKEWERS.put(
        raw.toString(),
        new Skewer(
            slots,
            cooked.toString(),
            "",
            effect,
            Math.max(0, effectSeconds),
            modelSource(rawModel),
            modelSource(cookedModel),
            eatingAnimation(eatingAnimation)));
  }

  /**
   * Overrides a fixed skewer while inheriting every option omitted by the script.
   * The raw entry controls threading and rendering; the cooked entry controls food effects.
   */
  public static void modifyFixedSkewer(
      ResourceLocation raw,
      List<List<String>> ingredients,
      String effect,
      Integer effectSeconds,
      String rawModel,
      String cookedModel,
      String eatingAnimation) {
    if (raw == null) throw new IllegalArgumentException("Raw skewer ID cannot be null");
    Skewer originalRaw = configuredSkewer(raw.toString());
    if (originalRaw == null || originalRaw.cookedResult().isBlank())
      throw new IllegalArgumentException(
          "No fixed raw skewer with a cooked result exists for " + raw);

    String cookedId = originalRaw.cookedResult();
    if (ResourceLocation.tryParse(cookedId) == null)
      throw new IllegalArgumentException(
          "Fixed skewer " + raw + " has an invalid cooked result: " + cookedId);
    Skewer originalCooked = configuredSkewer(cookedId);
    if (originalCooked == null)
      originalCooked =
          new Skewer(List.of(), "", "", "", 0, "auto", "auto", "default");

    List<List<String>> inheritedSlots =
        ingredients == null
            ? originalRaw.ingredients()
            : ingredients.stream().map(List::copyOf).toList();
    String inheritedRawModel =
        rawModel == null ? originalRaw.rawModel() : modelSource(rawModel);
    String inheritedCookedModel =
        cookedModel == null ? originalRaw.cookedModel() : modelSource(cookedModel);
    String inheritedEating =
        eatingAnimation == null
            ? originalRaw.eatingAnimation()
            : eatingAnimation(eatingAnimation);

    PENDING_SCRIPT_SKEWERS.put(
        raw.toString(),
        new Skewer(
            inheritedSlots,
            cookedId,
            originalRaw.threadingResult(),
            originalRaw.effect(),
            originalRaw.effectSeconds(),
            inheritedRawModel,
            inheritedCookedModel,
            inheritedEating));
    PENDING_SCRIPT_SKEWERS.put(
        cookedId,
        new Skewer(
            originalCooked.ingredients(),
            originalCooked.cookedResult(),
            originalCooked.threadingResult(),
            effect == null ? originalCooked.effect() : effect,
            effectSeconds == null
                ? originalCooked.effectSeconds()
                : Math.max(0, effectSeconds),
            rawModel == null ? originalCooked.rawModel() : modelSource(rawModel),
            cookedModel == null ? originalCooked.cookedModel() : modelSource(cookedModel),
            eatingAnimation == null
                ? originalCooked.eatingAnimation()
                : eatingAnimation(eatingAnimation)));
  }

  private static Skewer configuredSkewer(String id) {
    Skewer pending = PENDING_SCRIPT_SKEWERS.get(id);
    if (pending != null) return pending;
    Skewer loaded = SKEWERS.get(id);
    return loaded != null ? loaded : BUILT_IN_SKEWERS.get(id);
  }

  /** Publishes the complete recipe set after KubeJS server scripts finish reloading. */
  public static void commitScriptThreadingRecipes() {
    SCRIPT_SKEWERS = Map.copyOf(PENDING_SCRIPT_SKEWERS);
    PENDING_SCRIPT_SKEWERS.clear();
    LOGGER.info("Loaded {} KubeJS skewer recipes", SCRIPT_SKEWERS.size());
    GrillingNetwork.broadcastThreadingRecipes();
  }

  public static List<ScriptSkewerRecipe> scriptSkewerRecipes() {
    return SCRIPT_SKEWERS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                new ScriptSkewerRecipe(
                    entry.getKey(),
                    entry.getValue().ingredients(),
                    entry.getValue().cookedResult(),
                    entry.getValue().threadingResult(),
                    entry.getValue().effect(),
                    entry.getValue().effectSeconds(),
                    entry.getValue().rawModel(),
                    entry.getValue().cookedModel(),
                    entry.getValue().eatingAnimation()))
        .toList();
  }

  public static void replaceSyncedSkewerRecipes(List<ScriptSkewerRecipe> recipes) {
    Map<String, Skewer> synced = new HashMap<>();
    for (ScriptSkewerRecipe recipe : recipes) {
      if (recipe == null || recipe.id() == null) continue;
      synced.put(
          recipe.id(),
          new Skewer(
              recipe.ingredients().stream().map(List::copyOf).toList(),
              recipe.cookedResult(),
              recipe.threadingResult(),
              recipe.effect(),
              recipe.effectSeconds(),
              modelSource(recipe.rawModel()),
              modelSource(recipe.cookedModel()),
              eatingAnimation(recipe.eatingAnimation())));
    }
    SCRIPT_SKEWERS = Map.copyOf(synced);
    LOGGER.info("Received {} synced KubeJS skewer recipes", SCRIPT_SKEWERS.size());
  }

  private static String modelSource(String value) {
    return switch (value == null ? "auto" : value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "generated", "provided" -> value.trim().toLowerCase(java.util.Locale.ROOT);
      default -> "auto";
    };
  }

  private static String eatingAnimation(String value) {
    return value == null || value.isBlank()
        ? "default"
        : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  public static long seasoningCount(List<String> values, String kind) {
    Map<String, String> loaded = KINDS;
    Map<String, String> kinds = loaded.isEmpty() ? BUILT_IN_KINDS : loaded;
    return values.stream().filter(id -> kind.equals(kinds.get(id))).count();
  }

  public static boolean isSeasoningIngredient(String id) {
    Map<String, String> loaded = KINDS;
    Map<String, String> kinds = loaded.isEmpty() ? BUILT_IN_KINDS : loaded;
    return kinds.containsKey(id);
  }

  @Override
  protected void apply(
      Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
    // Start with built-ins so an addon/datapack may contribute one or two entries
    // without accidentally hiding the rest of the default recipes.
    Map<String, Skewer> skewers = new HashMap<>(BUILT_IN_SKEWERS);
    Map<String, String> kinds = new HashMap<>(BUILT_IN_KINDS);
    files.values().forEach(json -> readRoot(json.getAsJsonObject(), skewers, kinds));
    SKEWERS = Map.copyOf(skewers);
    KINDS = Map.copyOf(kinds);
  }

  private static Map<String, Skewer> loadBuiltInSkewers() {
    var stream =
        GrillingDataManager.class.getResourceAsStream(
            "/data/kaleidoscope_grilling/grilling/skewers.json");
    if (stream == null) return Map.of();
    try (stream;
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      Map<String, Skewer> skewers = new HashMap<>();
      readRoot(GSON.fromJson(reader, JsonObject.class), skewers, new HashMap<>());
      return Map.copyOf(skewers);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private static Map<String, String> loadBuiltInKinds() {
    var stream =
        GrillingDataManager.class.getResourceAsStream(
            "/data/kaleidoscope_grilling/grilling/skewers.json");
    if (stream == null) return Map.of();
    try (stream;
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      Map<String, String> kinds = new HashMap<>();
      readRoot(GSON.fromJson(reader, JsonObject.class), new HashMap<>(), kinds);
      return Map.copyOf(kinds);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private static void readRoot(
      JsonObject root, Map<String, Skewer> skewers, Map<String, String> kinds) {
    if (root.has("skewers"))
      for (JsonElement element : root.getAsJsonArray("skewers")) {
        JsonObject object = element.getAsJsonObject();
        List<List<String>> slots = new ArrayList<>();
        for (JsonElement slot : object.getAsJsonArray("ingredients")) {
          List<String> selectors = new ArrayList<>();
          for (JsonElement selector : slot.getAsJsonArray())
            selectors.add(selector.getAsString());
          slots.add(List.copyOf(selectors));
        }
        skewers.put(
            object.get("id").getAsString(),
            new Skewer(
                List.copyOf(slots),
                object.has("cooked_result") ? object.get("cooked_result").getAsString() : "",
                object.has("threading_result")
                    ? object.get("threading_result").getAsString()
                    : "",
                object.has("effect") ? object.get("effect").getAsString() : "",
                object.has("effect_seconds") ? object.get("effect_seconds").getAsInt() : 0,
                object.has("raw_model") ? object.get("raw_model").getAsString() : "auto",
                object.has("cooked_model") ? object.get("cooked_model").getAsString() : "auto",
                object.has("eating_animation")
                    ? object.get("eating_animation").getAsString()
                    : "default"));
      }
    if (root.has("seasoning_effects"))
      for (JsonElement element : root.getAsJsonArray("seasoning_effects")) {
        JsonObject object = element.getAsJsonObject();
        kinds.put(object.get("ingredient").getAsString(), object.get("kind").getAsString());
      }
  }
}
