package com.byd.extend.artwork;

import java.io.IOException;

/** Fixed artwork transport paths and timing; no caller-selected Binder operations. */
public final class ArtworkDirectPolicy {
    private ArtworkDirectPolicy() {}
    public static final long REPEAT_MS = 1000;

    public static String hostMediaRoot(String vm, String singleOs) throws IOException {
        if ("1".equals(singleOs)) {
            if (vm != null && vm.matches("cell[1-9][0-9]*"))
                throw new IOException("Conflicting artwork host mapping: single_os=1, vm=" + vm);
            return "/data/media";
        }
        if ("0".equals(singleOs) && vm != null && vm.matches("cell[0-9]+"))
            return "/data/cells/" + vm + "-rw/data/media";
        throw new IOException("Unknown artwork host mapping: single_os=" + singleOs + ", vm=" + vm);
    }

    public static String hostPath(String root, int user, String prefix, String local) throws IOException {
        if (user < 0 || root == null || !root.matches("/data/(?:cells/cell[0-9]+-rw/data/)?media"))
            throw new IOException("Invalid artwork host root/user");
        String base = "/storage/emulated/" + user + "/";
        if (prefix == null || !prefix.matches(java.util.regex.Pattern.quote(base)
                + "Download/\\.byd-extend-artwork/[a-f0-9]{32}/")
                || !MusicArtworkPolicy.owns(prefix, local))
            throw new IOException("Artwork image outside owned session/user");
        return root + "/" + user + "/" + local.substring(base.length());
    }

    public static long delay(long now, long lastSend, boolean changed, boolean hasImage) {
        if (changed) return 0;
        if (!hasImage) return REPEAT_MS;
        return Math.max(0, REPEAT_MS - Math.max(0, now - lastSend));
    }

    public static boolean current(long expectedEpoch, long epoch, long expectedRevision, long revision,
            Object prepared, Object selected) {
        return expectedEpoch == epoch && expectedRevision == revision && prepared != null && prepared == selected;
    }
}
