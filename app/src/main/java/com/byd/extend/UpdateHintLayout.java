package com.byd.extend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic column-major layout for update-hint cards. */
public final class UpdateHintLayout {
    public static final float OUTER_MARGIN_DP = 18f;
    public static final float GAP_DP = 8f;

    private UpdateHintLayout() {}

    public static Result calculate(
            List<UpdateHintState> input,
            int displayId,
            int availableLeftPx,
            int availableTopPx,
            int availableWidthPx,
            int availableHeightPx,
            float density,
            long nowElapsedNanos,
            long nowElapsedMs) {
        return calculate(input, displayId, availableLeftPx, availableTopPx, availableWidthPx,
                availableHeightPx, density, nowElapsedNanos, nowElapsedMs, null);
    }

    public static Result calculate(
            List<UpdateHintState> input,
            int displayId,
            int availableLeftPx,
            int availableTopPx,
            int availableWidthPx,
            int availableHeightPx,
            float density,
            long nowElapsedNanos,
            long nowElapsedMs,
            String localOwnerPackage) {
        ArrayList<UpdateHintState> cards = new ArrayList<>();
        if (input != null) {
            for (UpdateHintState state : input) {
                if (state != null && state.displayId == displayId
                        && (state.isActiveAt(nowElapsedNanos, nowElapsedMs)
                        || (state.ownerPackage.equals(localOwnerPackage)
                        && UpdateHintState.PENDING.equals(state.phase)))) cards.add(state);
            }
        }
        cards.sort(UpdateHintState.DISPLAY_ORDER);
        if (cards.isEmpty() || availableWidthPx <= 0 || availableHeightPx <= 0) {
            return new Result(0, 0, Collections.emptyList());
        }
        int margin = Math.max(0, Math.round(OUTER_MARGIN_DP * density));
        int gap = Math.max(0, Math.round(GAP_DP * density));
        int width = Math.max(0, availableWidthPx - margin * 2);
        int height = Math.max(0, availableHeightPx - margin * 2);
        int maxPreferred = 1;
        for (UpdateHintState card : cards) maxPreferred = Math.max(maxPreferred, card.preferredSizePercent);

        Candidate candidate = find(cards, maxPreferred, width, height, gap);
        int ceiling = maxPreferred;
        if (candidate == null) {
            int low = 1;
            int high = maxPreferred - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                Candidate fit = find(cards, middle, width, height, gap);
                if (fit != null) {
                    ceiling = middle;
                    candidate = fit;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
        }
        if (candidate == null) return new Result(0, 0, Collections.emptyList());

        ArrayList<Placement> placements = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            int column = i / candidate.rows;
            int row = i % candidate.rows;
            int x = availableLeftPx + margin;
            for (int c = 0; c < column; c++) x += candidate.columnWidths[c] + gap;
            int y = availableTopPx + margin;
            for (int r = 0; r < row; r++) y += candidate.rowHeights[r] + gap;
            UpdateHintState card = cards.get(i);
            int scale = Math.min(card.preferredSizePercent, ceiling);
            placements.add(new Placement(card.ownerPackage, card.eventId, x, y,
                    scaled(card.preferredWidthPx, scale, card.preferredSizePercent),
                    scaled(card.preferredHeightPx, scale, card.preferredSizePercent), scale,
                    column, row));
        }
        return new Result(candidate.rows, candidate.columnWidths.length,
                Collections.unmodifiableList(placements));
    }

    private static Candidate find(
            List<UpdateHintState> cards, int ceiling, int width, int height, int gap) {
        for (int rows = cards.size(); rows >= 1; rows--) {
            int columns = (cards.size() + rows - 1) / rows;
            int[] rowHeights = new int[rows];
            int[] columnWidths = new int[columns];
            for (int i = 0; i < cards.size(); i++) {
                UpdateHintState card = cards.get(i);
                int scale = Math.min(card.preferredSizePercent, ceiling);
                int column = i / rows;
                int row = i % rows;
                columnWidths[column] = Math.max(columnWidths[column],
                        scaled(card.preferredWidthPx, scale, card.preferredSizePercent));
                rowHeights[row] = Math.max(rowHeights[row],
                        scaled(card.preferredHeightPx, scale, card.preferredSizePercent));
            }
            if (sum(columnWidths) + gap * (columns - 1) <= width
                    && sum(rowHeights) + gap * (rows - 1) <= height) {
                return new Candidate(rows, columnWidths, rowHeights);
            }
        }
        return null;
    }

    private static int scaled(int measuredPx, int effectivePercent, int preferredPercent) {
        return Math.max(1, (int) Math.round(
                measuredPx * (double) effectivePercent / preferredPercent));
    }

    private static int sum(int[] values) {
        int result = 0;
        for (int value : values) result += value;
        return result;
    }

    private static final class Candidate {
        final int rows;
        final int[] columnWidths;
        final int[] rowHeights;

        Candidate(int rows, int[] columnWidths, int[] rowHeights) {
            this.rows = rows;
            this.columnWidths = columnWidths;
            this.rowHeights = rowHeights;
        }
    }

    public static final class Placement {
        public final String ownerPackage;
        public final String eventId;
        public final int xPx;
        public final int yPx;
        public final int widthPx;
        public final int heightPx;
        public final int effectiveScalePercent;
        public final int column;
        public final int row;

        Placement(String ownerPackage, String eventId, int xPx, int yPx, int widthPx,
                int heightPx, int effectiveScalePercent, int column, int row) {
            this.ownerPackage = ownerPackage;
            this.eventId = eventId;
            this.xPx = xPx;
            this.yPx = yPx;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.effectiveScalePercent = effectiveScalePercent;
            this.column = column;
            this.row = row;
        }
    }

    public static final class Result {
        public final int rows;
        public final int columns;
        public final List<Placement> placements;

        Result(int rows, int columns, List<Placement> placements) {
            this.rows = rows;
            this.columns = columns;
            this.placements = placements;
        }

        public Placement find(String ownerPackage, String eventId) {
            for (Placement placement : placements) {
                if (placement.ownerPackage.equals(ownerPackage)
                        && placement.eventId.equals(eventId)) return placement;
            }
            return null;
        }
    }
}
