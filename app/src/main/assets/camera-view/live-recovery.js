/* Buffered live cutover for the bundled hls.js 1.6.13, shared by both HTTP transports. */
(() => {
  'use strict';
  const TARGET_SECONDS = 2;
  function bufferedTarget(position, ranges, hole) {
    if (!Number.isFinite(position)) return null;
    for (const range of ranges) {
      if (range.start <= position && range.end - position > hole) return null;
      if (range.start > position && range.end - range.start > 0.02) return range.start + 0.01;
    }
    return null;
  }
  function nextAvailable(sequence, missingThrough, fragments) {
    if (!Number.isSafeInteger(sequence) || sequence > missingThrough) return null;
    return fragments.find((fragment) => fragment.sn > missingThrough && !fragment.gap) || null;
  }
  function liveTarget(position, start, edge, maximum) {
    if (![position, start, edge, maximum].every(Number.isFinite) || edge <= start) return null;
    if (position >= start && edge - position <= maximum) return null;
    return Math.max(start + 0.01, edge - TARGET_SECONDS);
  }
  function bufferedAt(target, ranges, cushion = 1) {
    return Number.isFinite(target) && ranges.some((range) => range.start <= target && range.end >= target + cushion);
  }
  function install(hls, Hls, report, maximum = () => 4) {
    const controller = hls.streamController;
    const synchronize = controller?.synchronizeToLiveEdge;
    const nextFragment = controller?.getNextFragment;
    const loadPosition = controller?.getLoadPosition;
    if (Hls.version !== '1.6.13' || typeof synchronize !== 'function' || typeof nextFragment !== 'function'
        || typeof loadPosition !== 'function' || typeof controller.mapToInitFragWhenRequired !== 'function'
        || typeof hls.levelController?.loadPlaylist !== 'function') {
      const error = new Error('Unsupported HLS live-recovery interface');
      error.name = 'HlsRecoveryInterfaceError'; throw error;
    }
    let missingThrough = -1, missingCandidate = null, awaitingPlaylist = false;
    let disposed = false, pendingTarget = null, latestDetails = null;
    let cushion = 1;
    const loaded = (_, data) => {
      if (data.frag.type !== 'main' || !Number.isSafeInteger(data.frag.sn)) return;
      const loading = (data.part?.stats || data.frag.stats).loading;
      // Keep enough media for another observed download, without growing the live target.
      cushion = Math.min(TARGET_SECONDS, Math.max(1, (loading.end - loading.start) / 1000));
    };
    const tick = () => {
      const video = hls.media;
      if (disposed || !hls.hasEnoughToStart || !video || video.seeking) return;
      const details = latestDetails || hls.levels[hls.currentLevel]?.details;
      const first = details?.fragments?.find((fragment) => !fragment.gap);
      const ranges = Array.from({ length: video.buffered.length }, (_, i) =>
        ({ start: video.buffered.start(i), end: video.buffered.end(i) }));
      hls.config.maxBufferLength = maximum() === 4 ? 3 : 6;
      if (first) {
        // Retarget only after expiration; a moving target would continually abort useful downloads.
        if (pendingTarget != null && (pendingTarget < first.start || video.currentTime >= pendingTarget)) pendingTarget = null;
        if (pendingTarget == null) {
          const target = liveTarget(video.currentTime, first.start, details.edge, maximum());
          if (target != null) {
            pendingTarget = target;
            // getLoadPosition redirects the next request; finish useful in-flight work first.
          }
        }
      }
      if (pendingTarget != null) {
        if (bufferedAt(pendingTarget, ranges, cushion)) {
          const from = video.currentTime, target = pendingTarget;
          pendingTarget = null;
          video.currentTime = target;
          report('buffered', { fromMs: Math.round(from * 1000), targetMs: Math.round(target * 1000) });
        }
        return;
      }
      const target = bufferedTarget(video.currentTime, ranges, hls.config.maxBufferHole);
      if (target === null || !bufferedAt(target, ranges, cushion)) return;
      const loading = controller.fragCurrent;
      if (loading && ['FRAG_LOADING', 'PARSING', 'PARSED'].includes(controller.state)
          && loading.start + loading.duration < target) return;
      const from = video.currentTime;
      video.currentTime = target;
      report('buffered', { fromMs: Math.round(from * 1000), targetMs: Math.round(target * 1000) });
    };
    controller.synchronizeToLiveEdge = function (details) {
      latestDetails = details;
      if (!hls.hasEnoughToStart) {
        if (!this.media?.readyState) synchronize.call(this, details);
      } else tick();
    };
    // Fetch at the cutover point while the media element still displays its last usable buffer.
    controller.getLoadPosition = function () { return pendingTarget ?? loadPosition.call(this); };
    controller.getNextFragment = function (position, details) {
      const fragment = nextFragment.call(this, position, details);
      if (!fragment || !Number.isSafeInteger(fragment.sn) || fragment.sn > missingThrough) return fragment;
      const available = nextAvailable(fragment.sn, missingThrough, details.fragments);
      return available ? this.mapToInitFragWhenRequired(available) : null;
    };
    const refreshed = (_, data) => {
      latestDetails = data.details;
      if (disposed) return;
      if (awaitingPlaylist) {
        awaitingPlaylist = false;
        const first = data.details.fragments.find((fragment) => !fragment.gap);
        if (first && missingCandidate != null && missingCandidate < first.sn) {
          missingThrough = Math.max(missingThrough, first.sn - 1);
          report('missing', { sequence: missingCandidate, httpStatus: 404, action: 'refresh_playlist' });
        }
        missingCandidate = null;
        hls.resumeBuffering();
      }
      tick();
    };
    const error = (_, data) => {
      if (disposed || data.fatal || data.details !== Hls.ErrorDetails.FRAG_LOAD_ERROR ||
          data.response?.code !== 404 || data.frag?.type !== 'main' || !Number.isSafeInteger(data.frag.sn)) return;
      missingCandidate = data.frag.sn;
      if (awaitingPlaylist) return;
      awaitingPlaylist = true;
      hls.pauseBuffering();
      hls.levelController.loadPlaylist();
    };
    const destroy = () => {
      disposed = true;
      hls.off(Hls.Events.FRAG_BUFFERED, tick);
      hls.off(Hls.Events.FRAG_LOADED, loaded);
      hls.off(Hls.Events.LEVEL_UPDATED, refreshed);
      hls.off(Hls.Events.ERROR, error);
      hls.off(Hls.Events.DESTROYING, destroy);
      controller.synchronizeToLiveEdge = synchronize;
      controller.getNextFragment = nextFragment;
      controller.getLoadPosition = loadPosition;
    };
    hls.on(Hls.Events.FRAG_BUFFERED, tick);
    hls.on(Hls.Events.FRAG_LOADED, loaded);
    hls.on(Hls.Events.LEVEL_UPDATED, refreshed);
    hls.on(Hls.Events.ERROR, error);
    hls.on(Hls.Events.DESTROYING, destroy);
    return tick;
  }
  const recovery = { bufferedTarget, nextAvailable, liveTarget, bufferedAt, install };
  if (typeof module === 'object' && module.exports) module.exports = recovery;
  else globalThis.BydLiveRecovery = recovery;
})();
