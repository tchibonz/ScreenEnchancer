/* Screen Enhancer - in-page video enhancer (WebGL2).
 * Injected at document start into every frame of the in-app browser.
 * Finds <video> elements, draws them to a canvas on top through the same shader chain as the
 * native player (FSRCNN luma upscale, or edge-adaptive upscale + sharpening), and hides the
 * original video. Does not work for DRM-protected or cross-origin (no CORS) video.
 * FSRCNN weights: Saafke/FSRCNN_Tensorflow (Apache-2.0). */
(function () {
  'use strict';
  if (window.__nsLoaded) return;
  window.__nsLoaded = true;

  var SHADERS = __SHADERS__;
  var MODELS = [
    null,
    { name: 'small', nt: 2, a: 'small_a', convs: ['small_c3'], b: 'small_b', s: 'small_s' },
    { name: 'full', nt: 3, a: 'full_a', convs: ['full_c3', 'full_c4', 'full_c5', 'full_c6'], b: 'full_b', s: 'full_s' }
  ];
  // ai: 0 auto, 1 off, 2 small, 3 full. thermal: Android PowerManager thermal status 0..6.
  var P = { dn: 0.35, sharp: 0.6, contrast: 1.05, sat: 1.1, ai: 0, thermal: 0, enable: true };
  var enhancers = [];
  var IDENT = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);

  function log(msg) { try { console.log('NS:' + msg); } catch (e) { /* ignore */ } }

  // ------------------------------------------------------------ parameter sync across frames
  function forward(msg) {
    var fr = document.getElementsByTagName('iframe');
    for (var i = 0; i < fr.length; i++) {
      try { fr[i].contentWindow.postMessage(msg, '*'); } catch (e) { /* ignore */ }
    }
  }
  function applyParams(p) {
    for (var k in p) { if (Object.prototype.hasOwnProperty.call(p, k)) P[k] = p[k]; }
    for (var i = 0; i < enhancers.length; i++) enhancers[i].redraw();
  }
  window.__nsSet = function (p) { applyParams(p); forward({ ns: 'params', p: p }); };
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object' || !d.ns) return;
    if (d.ns === 'params') { applyParams(d.p); forward(d); }
    else if (d.ns === 'hello' && e.source) {
      try { e.source.postMessage({ ns: 'params', p: P }, '*'); } catch (err) { /* ignore */ }
    }
  });
  if (window.top !== window) {
    try { window.parent.postMessage({ ns: 'hello' }, '*'); } catch (e) { /* ignore */ }
  }

  // Fullscreen: make the container (which holds our canvas) fullscreen instead of the bare <video>.
  ['requestFullscreen', 'webkitRequestFullscreen'].forEach(function (name) {
    var orig = Element.prototype[name];
    if (!orig) return;
    Element.prototype[name] = function () {
      var t = this;
      if (t instanceof HTMLVideoElement && t.__ns && t.parentElement) t = t.parentElement;
      return orig.apply(t, arguments);
    };
  });

  // ------------------------------------------------------------ enhancer
  function Enhancer(video) {
    this.v = video;
    video.__ns = true;
    this.dead = false;
    this.shown = false;
    this.progs = {};
    this.targetsKey = '';
    this.tex = { y: null, feat: [], b: null, x2: null, up: null };
    this.dnTex = null;
    this.srcTex = null;
    this.cap = 2;
    this.cooldownUntil = 0;
    this.cooldownMs = 60000;
    this.frames = 0;
    this.nextMeasure = 0;
    this.active = -1;
    this.interval = 41.7;
    this.lastT = 0;
    this.gpuMs = 0;
    this.lastStatus = 0;
    this.broken = [false, false, false];
    this.cw = 0; this.ch = 0;
    this.cssKey = '';
    this.prevOpacity = video.style.getPropertyValue('opacity');
    this.prevPrio = video.style.getPropertyPriority('opacity');

    this.canvas = document.createElement('canvas');
    this.canvas.setAttribute('data-ns', '1');
    this.canvas.style.cssText = 'position:absolute;pointer-events:none;margin:0;padding:0;border:0;display:none;';
    video.parentNode.insertBefore(this.canvas, video.nextSibling);
    try {
      this.initGL();
    } catch (e) {
      this.fail('WebGL2 is not available (' + e.message + ')');
      return;
    }
    this.schedule();
  }

  Enhancer.prototype.fail = function (why) {
    log('off: ' + why);
    this.dispose();
  };

  Enhancer.prototype.dispose = function () {
    this.dead = true;
    this.restore();
    if (this.canvas && this.canvas.parentNode) this.canvas.parentNode.removeChild(this.canvas);
    try {
      var ext = this.gl && this.gl.getExtension('WEBGL_lose_context');
      if (ext) ext.loseContext();
    } catch (e) { /* ignore */ }
    var i = enhancers.indexOf(this);
    if (i >= 0) enhancers.splice(i, 1);
    this.v.__ns = false;
    // allow a later retry only if the failure was not permanent
    this.v.__nsFailed = true;
  };

  Enhancer.prototype.restore = function () {
    var v = this.v;
    if (this.shown) {
      if (this.prevOpacity) v.style.setProperty('opacity', this.prevOpacity, this.prevPrio);
      else v.style.removeProperty('opacity');
      this.shown = false;
    }
    if (this.canvas) this.canvas.style.display = 'none';
  };

  Enhancer.prototype.initGL = function () {
    var gl = this.canvas.getContext('webgl2', {
      alpha: false, antialias: false, depth: false, stencil: false, powerPreference: 'high-performance'
    });
    if (!gl) throw new Error('no webgl2');
    this.gl = gl;
    this.aiOk = !!gl.getExtension('EXT_color_buffer_float');
    this.vbo = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 16, 0);
    gl.enableVertexAttribArray(1);
    gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 16, 8);
    this.fbo = gl.createFramebuffer();
    this.texVideo = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.texVideo);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.prog('final_easu');
    this.prog('final_rcas');
  };

  Enhancer.prototype.prog = function (name) {
    if (this.progs[name]) return this.progs[name];
    var gl = this.gl;
    function sh(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        var l = gl.getShaderInfoLog(s);
        gl.deleteShader(s);
        throw new Error('compile ' + name + ': ' + l);
      }
      return s;
    }
    var fs = SHADERS[name].replace('//__VIDEO__', 'uniform sampler2D uVideo;');
    var p = gl.createProgram();
    gl.attachShader(p, sh(gl.VERTEX_SHADER, SHADERS.vert));
    gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, 'aPos');
    gl.bindAttribLocation(p, 1, 'aUV');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link ' + name + ': ' + gl.getProgramInfoLog(p));
    p.__l = {};
    this.progs[name] = p;
    return p;
  };

  Enhancer.prototype.loc = function (p, name) {
    if (!(name in p.__l)) p.__l[name] = this.gl.getUniformLocation(p, name);
    return p.__l[name];
  };

  Enhancer.prototype.newTex = function (w, h, fmt, filt) {
    var gl = this.gl;
    var t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texStorage2D(gl.TEXTURE_2D, 1, fmt, w, h);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filt);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filt);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return { t: t, w: w, h: h };
  };

  Enhancer.prototype.freeTargets = function () {
    var gl = this.gl, T = this.tex;
    var all = [T.y, T.b, T.x2, T.up, this.dnTex].concat(T.feat);
    this.dnTex = null;
    for (var i = 0; i < all.length; i++) if (all[i]) gl.deleteTexture(all[i].t);
    this.tex = { y: null, feat: [], b: null, x2: null, up: null };
    this.targetsKey = '';
  };

  Enhancer.prototype.ensureAi = function (m, w, h) {
    var key = w + 'x' + h + 'x' + m + ':' + this.cw + 'x' + this.ch;
    if (this.tex.y && this.targetsKey === key) return;
    var gl = this.gl;
    var keepUp = this.tex.up && this.tex.up.w === this.cw && this.tex.up.h === this.ch ? this.tex.up : null;
    var T = this.tex;
    [T.y, T.b, T.x2].concat(T.feat).forEach(function (x) { if (x) gl.deleteTexture(x.t); });
    if (T.up && T.up !== keepUp) gl.deleteTexture(T.up.t);
    var nt = MODELS[m].nt, feat = [], i;
    for (i = 0; i < nt * 2; i++) feat.push(this.newTex(w, h, gl.RGBA16F, gl.NEAREST));
    this.tex = {
      y: this.newTex(w, h, gl.R16F, gl.NEAREST),
      feat: feat,
      b: this.newTex(w, h, gl.RGBA16F, gl.NEAREST),
      x2: this.newTex(w * 2, h * 2, gl.R16F, gl.LINEAR),
      up: keepUp
    };
    this.targetsKey = key;
  };

  // Optional noise / compression-artifact cleaning of the video frame; later passes read its output.
  Enhancer.prototype.prepareSource = function (w, h) {
    var gl = this.gl;
    this.srcTex = this.texVideo;
    if (!(P.dn > 0.02)) return;
    try {
      if (!this.dnTex || this.dnTex.w !== w || this.dnTex.h !== h) {
        if (this.dnTex) gl.deleteTexture(this.dnTex.t);
        this.dnTex = this.newTex(w, h, gl.RGBA8, gl.LINEAR);
      }
      this.pass(this.prog('dn'), [this.dnTex], w, h, [['uVideo', this.texVideo]], { uSize: [w, h], uDn: P.dn });
      this.srcTex = this.dnTex.t;
    } catch (e) {
      log('denoise off: ' + e.message);
      P.dn = 0;
    }
  };

  Enhancer.prototype.ensureUp = function () {
    var T = this.tex;
    if (T.up && T.up.w === this.cw && T.up.h === this.ch) return;
    if (T.up) this.gl.deleteTexture(T.up.t);
    T.up = this.newTex(this.cw, this.ch, this.gl.RGBA8, this.gl.NEAREST);
  };

  // targets: array of {t,w,h} or null for the canvas; inputs: [[samplerName, tex]]
  Enhancer.prototype.pass = function (p, targets, w, h, inputs, uni) {
    var gl = this.gl, i;
    if (!targets) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    } else {
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);
      var bufs = [];
      for (i = 0; i < 4; i++) {
        if (i < targets.length) {
          gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, targets[i].t, 0);
          bufs.push(gl.COLOR_ATTACHMENT0 + i);
        } else {
          gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, null, 0);
        }
      }
      gl.drawBuffers(bufs);
    }
    gl.viewport(0, 0, w, h);
    gl.useProgram(p);
    gl.uniformMatrix4fv(this.loc(p, 'uST'), false, IDENT);
    for (i = 0; i < inputs.length; i++) {
      gl.activeTexture(gl.TEXTURE0 + i);
      gl.bindTexture(gl.TEXTURE_2D, inputs[i][1]);
      var l = this.loc(p, inputs[i][0]);
      if (l !== null) gl.uniform1i(l, i);
    }
    if (uni) {
      for (var k in uni) {
        var ul = this.loc(p, k);
        if (ul === null) continue;
        if (typeof uni[k] === 'number') gl.uniform1f(ul, uni[k]);
        else gl.uniform2f(ul, uni[k][0], uni[k][1]);
      }
    }
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  };

  // ------------------------------------------------------------ layout
  Enhancer.prototype.layout = function (vw, vh) {
    var v = this.v, c = this.canvas;
    var W = v.clientWidth, H = v.clientHeight;
    var cs = getComputedStyle(v);
    if (W < 2 || H < 2 || cs.display === 'none' || cs.visibility === 'hidden') {
      this.restore();
      return false;
    }
    var w, h;
    if (cs.objectFit === 'fill') {
      w = W; h = H;
    } else {
      var s = Math.min(W / vw, H / vh);
      w = vw * s; h = vh * s;
    }
    var left = v.offsetLeft + (W - w) / 2, top = v.offsetTop + (H - h) / 2;
    var dpr = window.devicePixelRatio || 1;
    var k = Math.min(1, 3840 / Math.max(1, w * dpr), 2160 / Math.max(1, h * dpr));
    this.cw = Math.max(2, Math.round(w * dpr * k));
    this.ch = Math.max(2, Math.round(h * dpr * k));
    var z = cs.zIndex;
    var key = [left.toFixed(1), top.toFixed(1), w.toFixed(1), h.toFixed(1), z].join('|');
    if (key !== this.cssKey) {
      this.cssKey = key;
      c.style.left = left + 'px';
      c.style.top = top + 'px';
      c.style.width = w + 'px';
      c.style.height = h + 'px';
      c.style.zIndex = z === 'auto' ? 'auto' : z;
    }
    if (c.width !== this.cw) c.width = this.cw;
    if (c.height !== this.ch) c.height = this.ch;
    return true;
  };

  // ------------------------------------------------------------ rendering
  Enhancer.prototype.desired = function (vw, vh) {
    if (!this.aiOk) return 0;
    var px = vw * vh, user = P.ai;
    var m = user === 1 ? 0 : user === 2 ? 1 : user === 3 ? 2 : (px > 1e6 ? 0 : (px > 520000 ? 1 : 2));
    var now = performance.now();
    if (user === 0) {
      if (this.cap < 2 && P.thermal <= 1 && now > this.cooldownUntil) { this.cap++; this.nextMeasure = this.frames + 15; }
      m = Math.min(m, this.cap);
    }
    if (P.thermal >= 3) m = 0;
    else if (P.thermal >= 2 && user === 0) m = Math.min(m, 1);
    while (m > 0 && this.broken[m]) m--;
    return m;
  };

  Enhancer.prototype.draw = function () {
    var v = this.v, gl = this.gl;
    var vw = v.videoWidth, vh = v.videoHeight;
    if (!vw || !vh || v.readyState < 2) return;
    if (!this.layout(vw, vh)) return;

    gl.bindTexture(gl.TEXTURE_2D, this.texVideo);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    try {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, v);
    } catch (e) {
      this.fail(e && e.name === 'SecurityError' ? 'this video is protected or cross-origin and cannot be enhanced' : 'cannot read video frame: ' + e.message);
      return;
    }
    this.frames++;
    this.prepareSource(vw, vh);
    var m = this.desired(vw, vh);
    if (m !== this.active) { this.active = m; this.nextMeasure = this.frames + 15; }

    var measure = m > 0 && P.ai === 0 && this.frames >= this.nextMeasure;
    var t0 = 0;
    if (measure) { gl.finish(); t0 = performance.now(); }

    var done = false;
    if (m > 0) {
      try { this.drawAi(m, vw, vh); done = true; }
      catch (e) { log('ai ' + m + ' failed: ' + e.message); this.broken[m] = true; this.active = 0; }
    }
    if (!done) this.drawClassic(vw, vh);

    if (measure && done) {
      gl.finish();
      var ms = performance.now() - t0;
      this.gpuMs = ms;
      this.nextMeasure = this.frames + 120;
      var budget = this.interval * 0.35;
      if (ms > budget && this.cap >= m) {
        this.cap = m - 1;
        this.cooldownMs = Math.min(this.cooldownMs * 2, 600000);
        this.cooldownUntil = performance.now() + this.cooldownMs;
      }
    }

    if (!this.shown) {
      this.shown = true;
      this.v.style.setProperty('opacity', '0', 'important');
      this.canvas.style.display = '';
    }
    this.status(m, vw, vh);
  };

  Enhancer.prototype.drawAi = function (m, w, h) {
    var md = MODELS[m], T, nt = md.nt, i;
    this.ensureAi(m, w, h);
    T = this.tex;
    var gl = this.gl;
    this.pass(this.prog('pass_y'), [T.y], w, h, [['uVideo', this.srcTex]]);
    var fa = [], fb = [];
    for (i = 0; i < nt; i++) { fa.push(T.feat[i]); fb.push(T.feat[nt + i]); }
    var src = fa, dst = fb;
    this.pass(this.prog(md.a), src, w, h, [['uY', T.y.t]]);
    for (var c = 0; c < md.convs.length; c++) {
      this.pass(this.prog(md.convs[c]), dst, w, h, src.map(function (x, j) { return ['uF' + j, x.t]; }));
      var tmp = src; src = dst; dst = tmp;
    }
    this.pass(this.prog(md.b), [T.b], w, h, src.map(function (x, j) { return ['uF' + j, x.t]; }));
    this.pass(this.prog(md.s), [T.x2], w * 2, h * 2, [['uF0', T.b.t]]);
    this.pass(this.prog('final_ai'), null, this.cw, this.ch,
      [['uVideo', this.srcTex], ['uNet', T.x2.t]],
      { uPx: [1 / this.cw, 1 / this.ch], uSharp: P.sharp, uContrast: P.contrast, uSat: P.sat, uWipe: 0 });
    if (gl.getError() !== 0) throw new Error('gl error');
  };

  Enhancer.prototype.drawClassic = function (w, h) {
    this.ensureUp();
    var T = this.tex;
    var upscale = this.cw > w * 1.05 || this.ch > h * 1.05;
    this.pass(this.prog('final_easu'), [T.up], this.cw, this.ch, [['uVideo', this.srcTex]],
      { uSize: [w, h], uUp: upscale ? 1 : 0 });
    this.pass(this.prog('final_rcas'), null, this.cw, this.ch,
      [['uVideo', this.srcTex], ['uUp', T.up.t]],
      { uSharp: P.sharp, uContrast: P.contrast, uSat: P.sat, uWipe: 0 });
  };

  Enhancer.prototype.status = function (m, w, h) {
    var now = performance.now();
    if (now - this.lastStatus < 3000) return;
    this.lastStatus = now;
    log(m > 0 ? 'AI ' + MODELS[m].name + ' ' + w + 'x' + h + '->' + (w * 2) + 'x' + (h * 2) + (this.gpuMs ? ' GPU ' + this.gpuMs.toFixed(1) + ' ms' : '') + ' thermal ' + P.thermal
      : 'classic upscaler ' + w + 'x' + h + '->' + this.cw + 'x' + this.ch + ' thermal ' + P.thermal);
  };

  Enhancer.prototype.redraw = function () {
    if (this.dead) return;
    if (!P.enable) { this.restore(); return; }
    if (this.v.paused || this.v.ended) { try { this.draw(); } catch (e) { /* ignore */ } }
  };

  Enhancer.prototype.schedule = function () {
    if (this.dead) return;
    var self = this;
    var cb = function (now) { self.onFrame(now); };
    if (this.v.requestVideoFrameCallback) this.v.requestVideoFrameCallback(cb);
    else requestAnimationFrame(cb);
  };

  Enhancer.prototype.onFrame = function (now) {
    if (this.dead) return;
    if (!document.documentElement.contains(this.v)) { this.dispose(); this.v.__nsFailed = false; return; }
    if (this.lastT) {
      var d = now - this.lastT;
      if (d >= 8 && d <= 100) this.interval = this.interval * 0.9 + d * 0.1;
    }
    this.lastT = now;
    try {
      if (P.enable && !document.hidden) this.draw();
      else this.restore();
    } catch (e) {
      log('draw error: ' + e.message);
    }
    this.schedule();
  };

  // ------------------------------------------------------------ discovery
  function scan() {
    var vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (v.__ns || v.__nsFailed || !v.parentNode) continue;
      if (v.videoWidth > 0 && v.clientWidth > 160 && v.clientHeight > 90) {
        var e = new Enhancer(v);
        if (!e.dead) enhancers.push(e);
      }
    }
  }
  setInterval(scan, 1000);
  window.__nsEnhancers = enhancers;   // for tests
  window.__nsParams = P;
})();
