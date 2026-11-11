package com.byd.extend;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reads saved choices without rewriting IDs, enabled flags or any composition. */
final class OptionalPackSelection {
    static Set<String> required(String avas, String templates) throws JSONException {
        Set<String> result = new LinkedHashSet<>();
        if (avas != null) {
            JSONObject engine = new JSONObject(avas).optJSONObject("engine");
            if (engine != null) {
                OptionalPackCatalog.Pack pack = OptionalPackCatalog.engine(engine.optString("packId", "ferrari_v8"));
                if (!pack.bundled()) result.add(pack.key());
            }
        }
        if (templates != null) {
            JSONArray saved = new JSONObject(templates).optJSONArray("saved");
            if (saved != null) for (int i = 0; i < saved.length(); i++) {
                JSONArray texts = saved.getJSONObject(i).getJSONArray("texts");
                for (int j = 0; j < texts.length(); j++) {
                    int index = Math.max(0, Math.min(7, texts.getJSONObject(j).optInt("font", 0)));
                    OptionalPackCatalog.Pack pack = OptionalPackCatalog.font(index);
                    if (!pack.bundled()) result.add(pack.key());
                }
            }
        }
        // Built-in active compositions use Arimo; saved active compositions are included above.
        return result;
    }
}
