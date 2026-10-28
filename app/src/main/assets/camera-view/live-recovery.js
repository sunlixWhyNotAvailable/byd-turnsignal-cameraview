/* Tailscale recovery for the bundled hls.js 1.6.13; leave other providers untouched. */
(() => {
  'use strict';

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

  function install(hls, Hls, report) {
    const controller = hls.streamController;
    const synchronize = controller?.synchronizeToLiveEdge;
    const nextFragment = controller?.getNextFragment;
    if (Hls.version !== '1.6.13' || typeof synchronize !== 'function' ||
        typeof nextFragment !== 'function' || typeof controller.mapToInitFragWhenRequired !== 'function' ||
        typeof hls.levelController?.loadPlaylist !== 'function') {
      const error = new Error('Unsupported HLS live-recovery interface');
      error.name = 'HlsRecoveryInterfaceError';
      throw error;
    }

    let missingThrough = -1;
    let awaitingPlaylist = false;
    let disposed = false;

    const settle = () => {
      const video = hls.media;
      if (disposed || !hls.hasEnoughToStart || !video || video.seeking) return;
      const ranges = Array.from({ length: video.buffered.length }, (_, i) =>
        ({ start: video.buffered.start(i), end: video.buffered.end(i) }));
      const target = bufferedTarget(video.currentTime, ranges, hls.config.maxBufferHole);
      if (target === null) return;
      const loading = controller.fragCurrent;
      // An old fragment still being processed may fill this gap; do not abort it with a seek.
      if (loading && ['FRAG_LOADING', 'PARSING', 'PARSED'].includes(controller.state) &&
          loading.start + loading.duration < target) return;
      const from = video.currentTime;
      video.currentTime = target;
      report('buffered', { fromMs: Math.round(from * 1000), targetMs: Math.round(target * 1000) });
    };

    // HLS's buffered mode still seeks into missing media when the buffer is empty.
    // Keep its fragment loader; defer only the sliding-window seek until data is appended.
    controller.synchronizeToLiveEdge = function (details) {
      if (!hls.hasEnoughToStart) {
        if (!this.media?.readyState) synchronize.call(this, details);
        return;
      }
      settle();
    };
    controller.getNextFragment = function (position, details) {
      const fragment = nextFragment.call(this, position, details);
      if (!fragment || !Number.isSafeInteger(fragment.sn) || fragment.sn > missingThrough) return fragment;
      const available = nextAvailable(fragment.sn, missingThrough, details.fragments);
      return available ? this.mapToInitFragWhenRequired(available) : null;
    };

    const refreshed = () => {
      if (disposed || !awaitingPlaylist) return;
      awaitingPlaylist = false;
      hls.resumeBuffering();
    };
    const error = (_, data) => {
      if (disposed || data.fatal || data.details !== Hls.ErrorDetails.FRAG_LOAD_ERROR ||
          data.response?.code !== 404 || data.frag?.type !== 'main' ||
          !Number.isSafeInteger(data.frag.sn)) return;
      missingThrough = Math.max(missingThrough, data.frag.sn);
      report('missing', { sequence: data.frag.sn, httpStatus: 404 });
      if (awaitingPlaylist) return;
      // The failed response is complete. Refresh once before requesting another old segment.
      awaitingPlaylist = true;
      hls.pauseBuffering();
      hls.levelController.loadPlaylist();
    };
    const destroy = () => {
      disposed = true;
      hls.off(Hls.Events.FRAG_BUFFERED, settle);
      hls.off(Hls.Events.LEVEL_UPDATED, refreshed);
      hls.off(Hls.Events.ERROR, error);
      hls.off(Hls.Events.DESTROYING, destroy);
      controller.synchronizeToLiveEdge = synchronize;
      controller.getNextFragment = nextFragment;
    };
    hls.on(Hls.Events.FRAG_BUFFERED, settle);
    hls.on(Hls.Events.LEVEL_UPDATED, refreshed);
    hls.on(Hls.Events.ERROR, error);
    hls.on(Hls.Events.DESTROYING, destroy);
  }

  const recovery = { bufferedTarget, nextAvailable, install };
  if (typeof module === 'object' && module.exports) module.exports = recovery;
  else globalThis.BydLiveRecovery = recovery;
})();
