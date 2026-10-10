package cn.breezeth.kaleidoscope_grilling.skewer;

import cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager;
import cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager.Skewer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkewerCacheTest {
  private static final String RAW = "cache_test:raw_example";
  private static final String COOKED = "cache_test:cooked_example";

  private static Skewer skewer(String cooked, String rawModel, String cookedModel) {
    return new Skewer(List.of(), cooked, "", "", 0, rawModel, cookedModel, "default");
  }

  private static Field field(String name) throws Exception {
    Field field = GrillingDataManager.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static Object invoke(Object target, String method) throws Exception {
    Method m = target.getClass().getDeclaredMethod(method);
    m.setAccessible(true);
    return m.invoke(target);
  }

  private static Object snapshot() throws Exception {
    Method m = SkewerRecipes.class.getDeclaredMethod("snapshot");
    m.setAccessible(true);
    return m.invoke(null);
  }

  @Test
  void cachesInvalidateForLoadedAndScriptReplacements() throws Exception {
    Field loaded = field("SKEWERS"), scripts = field("SCRIPT_SKEWERS");
    Object oldLoaded = loaded.get(null), oldScripts = scripts.get(null);
    try {
      Skewer first = skewer(COOKED, "generated", "provided");
      loaded.set(null, Map.of(RAW, first));
      scripts.set(null, Map.of());
      var display = GrillingDataManager.skewersForDisplay();
      assertSame(display, GrillingDataManager.skewersForDisplay());
      Object initial = snapshot();
      assertSame(initial, snapshot());
      assertSame(display, invoke(initial, "source"));
      assertThrows(UnsupportedOperationException.class, () -> display.put("other", first));

      Skewer override = skewer(COOKED, "provided", "generated");
      scripts.set(null, Map.of(RAW, override));
      assertEquals(override, GrillingDataManager.skewersForDisplay().get(RAW));
      Object scripted = snapshot();
      assertNotSame(initial, scripted);
      Map<?, ?> models = (Map<?, ?>) invoke(scripted, "generatedModels");
      assertEquals(false, models.get(ResourceLocation.tryParse(RAW)));
      assertEquals(true, models.get(ResourceLocation.tryParse(COOKED)));

      scripts.set(null, Map.of());
      loaded.set(null, Map.of());
      Object cleared = snapshot();
      assertNotSame(scripted, cleared);
      assertFalse(((Map<?, ?>) invoke(cleared, "generatedModels")).containsKey(ResourceLocation.tryParse(RAW)));
    } finally {
      loaded.set(null, oldLoaded);
      scripts.set(null, oldScripts);
    }
  }

}
