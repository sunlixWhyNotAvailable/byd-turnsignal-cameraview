package com.byd.extend;

/** Geometry for remote live channels; source pixels always keep their original aspect ratio. */
public final class RemoteCameraLiveLayout {
    public static final class Rect {
        public final int left, top, right, bottom;

        private Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    private RemoteCameraLiveLayout() {}

    public static Rect channelBounds(int view, int interiorMask, int channel,
            int outputWidth, int outputHeight) {
        if (!RemoteCameraView.valid(view) || outputWidth < 2 || outputHeight < 2
                || channel < 0 || channel > 5) return null;
        RemoteCameraView.validateMask(interiorMask);
        if (channel < 4) {
            if (view == RemoteCameraView.COMPOSITION)
                return exteriorCell(channel, outputWidth, outputHeight);
            if (view == RemoteCameraView.COMBINED)
                return exteriorCell(channel, outputWidth * 2 / 3, outputHeight);
            return view == channel ? new Rect(0, 0, outputWidth, outputHeight) : null;
        }

        int bit = channel == 4 ? RemoteCameraView.ALLOW_CABIN : RemoteCameraView.ALLOW_DRIVER;
        int requested = RemoteCameraView.interiorMask(view, interiorMask);
        if ((requested & bit) == 0) return null;
        if (view == RemoteCameraView.CABIN || view == RemoteCameraView.DRIVER)
            return new Rect(0, 0, outputWidth, outputHeight);

        int left = view == RemoteCameraView.COMBINED ? outputWidth * 2 / 3 : 0;
        int right = outputWidth;
        int count = Integer.bitCount(requested);
        if (count == 1) return new Rect(left, 0, right, outputHeight);
        if (view == RemoteCameraView.INTERIOR) {
            int middle = (left + right) / 2;
            return channel == 4 ? new Rect(left, 0, middle, outputHeight)
                    : new Rect(middle, 0, right, outputHeight);
        }
        int middle = outputHeight / 2;
        return channel == 4 ? new Rect(left, 0, right, middle)
                : new Rect(left, middle, right, outputHeight);
    }

    public static Rect fit(Rect bounds, int sourceWidth, int sourceHeight) {
        if (bounds == null || bounds.width() < 1 || bounds.height() < 1
                || sourceWidth < 1 || sourceHeight < 1) return null;
        int width;
        int height;
        if ((long) bounds.width() * sourceHeight <= (long) bounds.height() * sourceWidth) {
            width = bounds.width();
            height = (int) ((long) bounds.width() * sourceHeight / sourceWidth);
        } else {
            height = bounds.height();
            width = (int) ((long) bounds.height() * sourceWidth / sourceHeight);
        }
        int left = bounds.left + (bounds.width() - width) / 2;
        int top = bounds.top + (bounds.height() - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    private static Rect exteriorCell(int cell, int width, int height) {
        if (cell < 0 || cell >= 4 || width < 2 || height < 2) return null;
        int left = cell % 2 * width / 2;
        int right = (cell % 2 + 1) * width / 2;
        int top = cell / 2 * height / 2;
        int bottom = (cell / 2 + 1) * height / 2;
        return fit(new Rect(left, top, right, bottom), 1920, 1300);
    }
}
