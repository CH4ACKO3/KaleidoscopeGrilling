package cn.breezeth.kaleidoscope_grilling.skewer;

import cn.breezeth.kaleidoscope_grilling.KaleidoscopeGrilling;
import cn.breezeth.kaleidoscope_grilling.registry.ModItems;
import cn.breezeth.kaleidoscope_grilling.SkewerCompatApi;

import cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager;
import cn.breezeth.kaleidoscope_grilling.food.HotFoodConfig;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class SkewerRecipes {
  private record Recipe(
      ResourceLocation rawResult,
      ResourceLocation cookedResult,
      String threadingResult,
      List<List<String>> ingredients,
      GrillingDataManager.Skewer data) {}

  public record Cooking(ItemStack raw, ItemStack cooked) {}

  private record RecipeSnapshot(
      Map<String, GrillingDataManager.Skewer> source,
      List<Recipe> recipes,
      Map<ResourceLocation, Boolean> generatedModels) {}

  private static volatile RecipeSnapshot cachedRecipes;

  private static List<Recipe> recipes() {
    return snapshot().recipes();
  }

  private static RecipeSnapshot snapshot() {
    Map<String, GrillingDataManager.Skewer> source = GrillingDataManager.skewersForDisplay();
    RecipeSnapshot snapshot = cachedRecipes;
    if (snapshot != null && snapshot.source() == source) return snapshot;
    List<Recipe> recipes = source.entrySet().stream()
        .map(SkewerRecipes::recipe)
        .filter(java.util.Objects::nonNull)
        .toList();
    Map<ResourceLocation, Boolean> generatedModels = new HashMap<>();
    for (Recipe recipe : recipes) {
      // Keep the original first-match order, including false results and a raw
      // result which is also its own cooked result.
      generatedModels.putIfAbsent(recipe.rawResult(), "generated".equals(recipe.data().rawModel()));
      if (recipe.cookedResult() != null) {
        generatedModels.putIfAbsent(
            recipe.cookedResult(), "generated".equals(recipe.data().cookedModel()));
      }
    }
    snapshot = new RecipeSnapshot(source, recipes, Map.copyOf(generatedModels));
    cachedRecipes = snapshot;
    return snapshot;
  }

  private static Recipe recipe(Map.Entry<String, GrillingDataManager.Skewer> entry) {
    ResourceLocation raw = ResourceLocation.tryParse(entry.getKey());
    if (raw == null) return null;
    String configured = entry.getValue().cookedResult();
    ResourceLocation cooked =
        configured.isEmpty() ? inferCooked(raw) : ResourceLocation.tryParse(configured);
    if (cooked == null
        && entry.getValue().threadingResult().isEmpty()
        && entry.getValue().ingredients().isEmpty()) return null;
    return new Recipe(
        raw,
        cooked,
        entry.getValue().threadingResult(),
        entry.getValue().ingredients(),
        entry.getValue());
  }

  private static ResourceLocation inferCooked(ResourceLocation raw) {
    String path = raw.getPath();
    if (!path.startsWith("raw_")) return null;
    return ResourceLocation.tryParse(raw.getNamespace() + ":grilled_" + path.substring(4));
  }

  public static boolean canAppend(List<ItemStack> inserted, ItemStack next) {
    int slot = inserted.size();
    return recipes().stream()
        .filter(recipe -> !recipe.ingredients().isEmpty())
        .anyMatch(
            recipe ->
                slot < recipe.ingredients().size()
                    && prefix(recipe, inserted)
                    && matchesAny(next, recipe.ingredients().get(slot)));
  }

  public static boolean isConfiguredIngredient(ItemStack stack) {
    return recipes().stream()
        .filter(recipe -> !recipe.ingredients().isEmpty())
        .flatMap(recipe -> recipe.ingredients().stream())
        .anyMatch(selectors -> matchesAny(stack, selectors));
  }

  public static ResourceLocation completedResult(List<ItemStack> inserted) {
    return recipes().stream()
        .filter(recipe -> !recipe.ingredients().isEmpty())
        .filter(
            recipe -> recipe.ingredients().size() == inserted.size() && prefix(recipe, inserted))
        .map(Recipe::rawResult)
        .findFirst()
        .orElse(null);
  }

  public static ResourceLocation threadingResult(List<ItemStack> inserted) {
    return recipes().stream()
        .filter(recipe -> !recipe.ingredients().isEmpty())
        .filter(
            recipe ->
                recipe.ingredients().size() == inserted.size() && prefix(recipe, inserted))
        .map(Recipe::threadingResult)
        .map(ResourceLocation::tryParse)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  public static int expectedSize(List<ItemStack> inserted) {
    return recipes().stream()
        .filter(recipe -> !recipe.ingredients().isEmpty())
        .filter(recipe -> prefix(recipe, inserted))
        .mapToInt(recipe -> recipe.ingredients().size())
        .max()
        .orElse(3);
  }

  public static boolean isRawSkewer(ItemStack stack) {
    if (stack.is(SkewerCompatApi.RAW_SKEWERS)) return true;
    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    return recipes().stream()
        .anyMatch(
            recipe ->
                recipe.cookedResult() != null
                    && recipe.rawResult().equals(id)
                    && !recipe.rawResult().equals(recipe.cookedResult()));
  }

  public static boolean isCookedSkewer(ItemStack stack) {
    if (ModItems.FIXED_SKEWERS.stream().anyMatch(item -> stack.is(item.get()))) return true;
    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    return recipes().stream()
        .anyMatch(recipe -> recipe.cookedResult() != null && recipe.cookedResult().equals(id));
  }

  public static List<ItemStack> displayIngredients(ItemStack stack) {
    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    Recipe recipe =
        recipes().stream()
            .filter(
                candidate ->
                    candidate.rawResult().equals(id)
                        || candidate.cookedResult() != null && candidate.cookedResult().equals(id))
            .findFirst()
            .orElse(null);
    if (recipe != null) {
      List<ItemStack> configured =
          recipe.ingredients().stream()
              .map(SkewerRecipes::representative)
              .filter(item -> !item.isEmpty())
              .limit(3)
              .toList();
      if (configured.size() == recipe.ingredients().size()) return configured;
    }
    return defaultDisplayIngredients(id);
  }

  public static ItemStack cookedResult(ItemStack rawStack) {
    ItemStack custom = SkewerCompatApi.customCookedResult(rawStack);
    if (!custom.isEmpty()) return custom;
    ResourceLocation rawId = BuiltInRegistries.ITEM.getKey(rawStack.getItem());
    ResourceLocation cookedId =
        recipes().stream()
            .filter(recipe -> recipe.rawResult().equals(rawId))
            .map(Recipe::cookedResult)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    if (cookedId == null) cookedId = inferCooked(rawId);
    if (cookedId == null || !BuiltInRegistries.ITEM.containsKey(cookedId)) return ItemStack.EMPTY;
    ItemStack result = new ItemStack(BuiltInRegistries.ITEM.get(cookedId));
    if (result.is(ModItems.SECRET_SKEWER.get())) {
      List<ItemStack> ingredients = displayIngredients(rawStack);
      if (!ingredients.isEmpty()) SkeweringHandler.writeGeneratedIngredients(result, ingredients);
      SecretSkewerItem.setCooked(result, true);
    }
    return result;
  }

  public static List<Cooking> cookingRecipes() {
    return recipes().stream()
        .filter(recipe -> recipe.cookedResult() != null)
        .filter(recipe -> BuiltInRegistries.ITEM.containsKey(recipe.rawResult()))
        .map(
            recipe -> {
              ItemStack raw = new ItemStack(BuiltInRegistries.ITEM.get(recipe.rawResult()));
              ItemStack cooked = cookedResult(raw);
              return cooked.isEmpty() ? null : new Cooking(raw, cooked);
            })
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  public static boolean usesGeneratedModel(ItemStack stack) {
    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    return snapshot().generatedModels().getOrDefault(id, false);
  }

  @Nullable
  public static MultiBiteSkewerItem.AnimationProfile animationProfile(ItemStack stack) {
    if (!HotFoodConfig.ENABLE_SKEWER_EATING_ANIMATIONS.get()) return null;
    return configuredAnimationProfile(stack);
  }

  /** Resolves the authored profile without applying the global animation toggle. */
  @Nullable
  public static MultiBiteSkewerItem.AnimationProfile configuredAnimationProfile(ItemStack stack) {
    if (stack.getItem() instanceof MultiBiteSkewerItem animated)
      return animated.animationProfile(stack);
    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    GrillingDataManager.Skewer data = GrillingDataManager.skewerForItem(id.toString());
    if (data == null) return null;
    String configured = data.eatingAnimation();
    if (configured.equals("provided") || configured.equals("none")) return null;
    if (!configured.equals("default")) {
      try {
        return MultiBiteSkewerItem.AnimationProfile.valueOf(
            configured.toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    }
    int count = Math.max(1, Math.min(4, data.ingredients().size()));
    return switch (count) {
      case 1 -> MultiBiteSkewerItem.AnimationProfile.ONE;
      case 2 -> MultiBiteSkewerItem.AnimationProfile.TWO;
      case 4 -> MultiBiteSkewerItem.AnimationProfile.FOUR;
      default -> MultiBiteSkewerItem.AnimationProfile.THREE;
    };
  }

  public static boolean usesCustomEating(ItemStack stack) {
    return animationProfile(stack) != null;
  }

  private static List<ItemStack> defaultDisplayIngredients(ResourceLocation id) {
    String path = id.getPath();
    if (path.startsWith("grilled_")) path = "raw_" + path.substring(8);
    List<String> ingredients = defaultIngredientIds(path);
    return ingredients.stream()
        .map(ResourceLocation::tryParse)
        .filter(java.util.Objects::nonNull)
        .filter(BuiltInRegistries.ITEM::containsKey)
        .map(itemId -> new ItemStack(BuiltInRegistries.ITEM.get(itemId)))
        .toList();
  }

  private static List<String> defaultIngredientIds(String path) {
    return switch (path) {
      case "raw_beef_skewer" ->
          List.of(
              "kaleidoscope_grilling:beef_chunks",
              "kaleidoscope_cookery:red_chili",
              "kaleidoscope_grilling:beef_chunks");
      case "raw_pork_belly_skewer" ->
          List.of(
              "kaleidoscope_cookery:raw_pork_belly",
              "kaleidoscope_cookery:green_chili",
              "kaleidoscope_cookery:raw_pork_belly");
      case "raw_chicken_skin_skewer" ->
          List.of("kaleidoscope_grilling:chicken_skin", "kaleidoscope_grilling:chicken_skin");
      case "raw_mid_wing_skewer" ->
          List.of(
              "kaleidoscope_grilling:chicken_wing",
              "kaleidoscope_cookery:red_chili",
              "kaleidoscope_grilling:chicken_wing");
      case "raw_squid_tentacle_skewer" ->
          List.of(
              "kaleidoscope_grilling:squid_tentacle",
              "kaleidoscope_grilling:squid_tentacle",
              "kaleidoscope_grilling:squid_tentacle");
      case "raw_fish_skewer" -> List.of("minecraft:cod");
      case "raw_sweet_potato_sheet_skewer" ->
          List.of(
              "kaleidoscope_grilling:raw_sweet_potato_sheet",
              "kaleidoscope_grilling:minced_houttuynia",
              "kaleidoscope_grilling:minced_houttuynia");
      case "raw_potato_slice_skewer" ->
          List.of(
              "kaleidoscope_grilling:potato_slice",
              "kaleidoscope_grilling:potato_slice",
              "kaleidoscope_grilling:potato_slice");
      case "raw_caterpillar_skewer" -> List.of("kaleidoscope_cookery:caterpillar");
      case "raw_mushroom_skewer" ->
          List.of(
              "minecraft:brown_mushroom",
              "kaleidoscope_grilling:carrot_dice",
              "minecraft:brown_mushroom");
      case "raw_bun_slice_skewer" ->
          List.of(
              "kaleidoscope_grilling:raw_mantou_slice",
              "kaleidoscope_grilling:raw_mantou_slice",
              "kaleidoscope_grilling:raw_mantou_slice");
      case "raw_ender_pearl_skewer" ->
          List.of("minecraft:ender_pearl", "minecraft:beetroot", "minecraft:ender_pearl");
      case "raw_meatball_skewer" ->
          List.of(
              "kaleidoscope_cookery:raw_meatball",
              "kaleidoscope_cookery:raw_meatball",
              "kaleidoscope_cookery:raw_meatball");
      case "raw_slime_skewer" ->
          List.of(
              "minecraft:slime_ball", "kaleidoscope_grilling:houttuynia", "minecraft:slime_ball");
      case "raw_meat_and_bone_skewer" ->
          List.of(
              "kaleidoscope_cookery:raw_cut_small_meats",
              "minecraft:bone",
              "kaleidoscope_cookery:raw_cut_small_meats");
      case "raw_fried_egg_skewer" ->
          List.of("kaleidoscope_cookery:fried_egg", "kaleidoscope_cookery:fried_egg");
      default -> List.of();
    };
  }

  public static List<List<String>> getIngredients(String id) {
    ResourceLocation full =
        ResourceLocation.tryParse(id.contains(":") ? id : KaleidoscopeGrilling.MOD_ID + ":" + id);
    if (full == null) return null;
    return recipes().stream()
        .filter(recipe -> recipe.rawResult().equals(full))
        .findFirst()
        .map(Recipe::ingredients)
        .orElse(null);
  }

  public static boolean matchesSelector(ItemStack stack, String selector) {
    if (selector.startsWith("#")) {
      ResourceLocation tagId = ResourceLocation.tryParse(selector.substring(1));
      return tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId));
    }
    ResourceLocation itemId = ResourceLocation.tryParse(selector);
    return itemId != null && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
  }

  private static boolean matchesAny(ItemStack stack, List<String> selectors) {
    return selectors.stream().anyMatch(selector -> matchesSelector(stack, selector));
  }

  private static ItemStack representative(List<String> selectors) {
    for (String selector : selectors) {
      if (selector.startsWith("#")) {
        ResourceLocation tagId = ResourceLocation.tryParse(selector.substring(1));
        if (tagId == null) continue;
        var tag = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tagId));
        if (tag.isPresent()) {
          var first = tag.get().stream().findFirst();
          if (first.isPresent()) return new ItemStack(first.get().value());
        }
      } else {
        ResourceLocation itemId = ResourceLocation.tryParse(selector);
        if (itemId != null && BuiltInRegistries.ITEM.containsKey(itemId)) {
          return new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        }
      }
    }
    return ItemStack.EMPTY;
  }

  private static boolean prefix(Recipe recipe, List<ItemStack> inserted) {
    if (inserted.size() > recipe.ingredients().size()) return false;
    for (int i = 0; i < inserted.size(); i++)
      if (!matchesAny(inserted.get(i), recipe.ingredients().get(i))) return false;
    return true;
  }

  private SkewerRecipes() {}
}
