package com.byd.extend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable, allowlisted AVAS configuration shared by the app and audio helper. */
public final class AvasConfig {
    public static final List<String> PROFILE_IDS = Collections.unmodifiableList(Arrays.asList(
            "lock", "unlock", "power_off", "power_on"));
    private static final int MAX_JSON_LENGTH = 1_048_576;
    private static final int VERSION = 1;

    public final List<Profile> profiles;

    public AvasConfig(List<Profile> profiles) {
        if (profiles == null || profiles.size() != PROFILE_IDS.size()) {
            throw new IllegalArgumentException("all AVAS profiles are required");
        }
        List<Profile> copy = Collections.unmodifiableList(new ArrayList<>(profiles));
        Set<String> assetIds = new HashSet<>();
        for (int index = 0; index < PROFILE_IDS.size(); index++) {
            if (copy.get(index) == null
                    || !PROFILE_IDS.get(index).equals(copy.get(index).id)) {
                throw new IllegalArgumentException("invalid AVAS profile order");
            }
            for (Asset asset : copy.get(index).assets) {
                if (!assetIds.add(asset.id)) {
                    throw new IllegalArgumentException("duplicate asset id");
                }
            }
        }
        this.profiles = copy;
    }

    public static AvasConfig empty() {
        List<Profile> profiles = new ArrayList<>();
        for (String id : PROFILE_IDS) {
            profiles.add(new Profile(id, false, false, 15, "", Collections.emptyList()));
        }
        return new AvasConfig(profiles);
    }

    public Profile profile(String id) {
        int index = PROFILE_IDS.indexOf(id);
        if (index < 0) throw new IllegalArgumentException("unknown AVAS profile");
        return profiles.get(index);
    }

    public AvasConfig withProfile(Profile profile) {
        if (profile == null) throw new IllegalArgumentException("profile is null");
        int index = PROFILE_IDS.indexOf(profile.id);
        if (index < 0) throw new IllegalArgumentException("unknown AVAS profile");
        List<Profile> copy = new ArrayList<>(profiles);
        copy.set(index, profile);
        return new AvasConfig(copy);
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject().put("version", VERSION);
            JSONArray values = new JSONArray();
            for (Profile profile : profiles) {
                JSONArray assets = new JSONArray();
                for (Asset asset : profile.assets) {
                    assets.put(new JSONObject().put("id", asset.id).put("name", asset.name));
                }
                values.put(new JSONObject()
                        .put("id", profile.id)
                        .put("enabled", profile.enabled)
                        .put("random", profile.random)
                        .put("volume", profile.volume)
                        .put("selectedAssetId", profile.selectedAssetId)
                        .put("assets", assets));
            }
            return root.put("profiles", values).toString();
        } catch (Exception error) {
            throw new IllegalStateException("AVAS serialization failed", error);
        }
    }

    public static AvasConfig parse(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("invalid AVAS configuration size");
        }
        try {
            JSONObject root = new JSONObject(json);
            requireKeys(root, "version", "profiles");
            if (strictInt(root.get("version"), "version") != VERSION) {
                throw new IllegalArgumentException("unsupported AVAS configuration");
            }
            JSONArray input = root.getJSONArray("profiles");
            if (input.length() != PROFILE_IDS.size()) {
                throw new IllegalArgumentException("all AVAS profiles are required");
            }
            List<Profile> profiles = new ArrayList<>();
            for (int index = 0; index < input.length(); index++) {
                JSONObject value = input.getJSONObject(index);
                requireKeys(value, "id", "enabled", "random", "volume",
                        "selectedAssetId", "assets");
                JSONArray assetValues = value.getJSONArray("assets");
                List<Asset> assets = new ArrayList<>();
                for (int assetIndex = 0; assetIndex < assetValues.length(); assetIndex++) {
                    JSONObject asset = assetValues.getJSONObject(assetIndex);
                    requireKeys(asset, "id", "name");
                    assets.add(new Asset(asset.getString("id"), asset.getString("name")));
                }
                profiles.add(new Profile(value.getString("id"), strictBoolean(value.get("enabled")),
                        strictBoolean(value.get("random")), strictInt(value.get("volume"), "volume"),
                        value.getString("selectedAssetId"), assets));
            }
            return new AvasConfig(profiles);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid AVAS configuration", error);
        }
    }

    private static void requireKeys(JSONObject value, String... expected) {
        if (value.length() != expected.length) {
            throw new IllegalArgumentException("unexpected AVAS configuration field");
        }
        for (String key : expected) {
            if (!value.has(key)) {
                throw new IllegalArgumentException("missing AVAS configuration field");
            }
        }
    }

    private static boolean strictBoolean(Object value) {
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("invalid boolean");
        return (Boolean) value;
    }

    private static int strictInt(Object value, String field) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("invalid " + field);
        Number number = (Number) value;
        double decimal = number.doubleValue();
        long integer = number.longValue();
        if (!Double.isFinite(decimal) || decimal != integer
                || integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return (int) integer;
    }

    public static final class Profile {
        public final String id;
        public final boolean enabled;
        public final boolean random;
        public final int volume;
        public final String selectedAssetId;
        public final List<Asset> assets;

        public Profile(String id, boolean enabled, boolean random, int volume,
                String selectedAssetId, List<Asset> assets) {
            if (!PROFILE_IDS.contains(id)) throw new IllegalArgumentException("invalid profile id");
            if (volume < 0 || volume > 100) throw new IllegalArgumentException("invalid volume");
            if (selectedAssetId == null || assets == null) {
                throw new IllegalArgumentException("null profile field");
            }
            List<Asset> copy = Collections.unmodifiableList(new ArrayList<>(assets));
            Set<String> ids = new HashSet<>();
            for (Asset asset : copy) {
                if (asset == null || !ids.add(asset.id)) {
                    throw new IllegalArgumentException("duplicate asset id");
                }
            }
            if (!selectedAssetId.isEmpty() && !ids.contains(selectedAssetId)) {
                throw new IllegalArgumentException("selected asset is not in profile");
            }
            this.id = id;
            this.enabled = enabled;
            this.random = random;
            this.volume = volume;
            this.selectedAssetId = selectedAssetId;
            this.assets = copy;
        }

        public Profile withSettings(boolean enabled, boolean random, int volume,
                String selectedAssetId) {
            return new Profile(id, enabled, random, volume, selectedAssetId, assets);
        }

        public Profile withAssets(List<Asset> assets, String selectedAssetId) {
            return new Profile(id, enabled, random, volume, selectedAssetId, assets);
        }
    }

    public static final class Asset {
        public final String id;
        public final String name;

        public Asset(String id, String name) {
            if (id == null || !id.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("invalid asset id");
            }
            if (name == null || name.trim().isEmpty() || name.length() > 256) {
                throw new IllegalArgumentException("invalid asset name");
            }
            for (int index = 0; index < name.length(); index++) {
                if (Character.isISOControl(name.charAt(index))) {
                    throw new IllegalArgumentException("invalid asset name");
                }
            }
            this.id = id;
            this.name = name;
        }

        public static Asset create(String name) {
            return new Asset(UUID.randomUUID().toString().replace("-", ""), name);
        }
    }
}
