#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
//__VIDEO__
uniform vec2 uSize;   // source texture size in texels
uniform float uUp;    // >0.5: edge-adaptive upscale, otherwise plain bilinear (downscale / 1:1)
in vec2 vVideoUV;
layout(location=0) out vec4 o0;

// Edge-adaptive spatial upsampling.
// 4x4 source taps, a local edge direction from a smoothed structure tensor, and a Catmull-Rom
// kernel that is stretched along the edge and compressed across it. The result is clamped to the
// range of the four nearest source texels (no ringing).
const float ACROSS = 1.15;  // kernel scale across the edge (>1 = narrower = sharper)
const float ALONG = 0.65;  // kernel scale along the edge  (<1 = longer = smoother edges)
const float EDGE_LO = 0.0004;
const float EDGE_HI = 0.01;

// Catmull-Rom as a radial function of the (anisotropically scaled) distance.
float kern(float x2) {
  if (x2 >= 4.0) return 0.0;
  float x = sqrt(x2);
  return x < 1.0 ? (1.5 * x - 2.5) * x2 + 1.0 : ((-0.5 * x + 2.5) * x - 4.0) * x + 2.0;
}

void main() {
  if (uUp < 0.5) {
    o0 = vec4(texture(uVideo, vVideoUV).rgb, 1.0);
    return;
  }
  vec2 p = vVideoUV * uSize - 0.5;
  vec2 b = floor(p);
  vec2 f = p - b;

  vec3 c[16];
  float l[16];
  for (int j = 0; j < 4; j++) {
    for (int i = 0; i < 4; i++) {
      vec3 s = texture(uVideo, (b + vec2(float(i - 1), float(j - 1)) + 0.5) / uSize).rgb;
      c[j * 4 + i] = s;
      l[j * 4 + i] = dot(s, vec3(0.299, 0.587, 0.114));
    }
  }

  // structure tensor over the four centre texels, weighted bilinearly
  float jxx = 0.0, jyy = 0.0, jxy = 0.0;
  for (int j = 1; j <= 2; j++) {
    for (int i = 1; i <= 2; i++) {
      float w = (i == 1 ? 1.0 - f.x : f.x) * (j == 1 ? 1.0 - f.y : f.y);
      float gx = (l[j * 4 + i + 1] - l[j * 4 + i - 1]) * 0.5;
      float gy = (l[(j + 1) * 4 + i] - l[(j - 1) * 4 + i]) * 0.5;
      jxx += w * gx * gx;
      jyy += w * gy * gy;
      jxy += w * gx * gy;
    }
  }
  float a = jxx - jyy;
  float bb = 2.0 * jxy;
  float len = sqrt(a * a + bb * bb);
  float tr = jxx + jyy;
  vec2 v = vec2(a + len, bb);
  vec2 n = dot(v, v) > 1e-12 ? normalize(v) : vec2(0.0, 1.0);   // across the edge
  vec2 t = vec2(-n.y, n.x);                                       // along the edge
  float k = (len / (tr + 1e-6)) * smoothstep(EDGE_LO, EDGE_HI, tr);
  float sx = mix(1.0, ACROSS, k);
  float st = mix(1.0, ALONG, k);

  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int j = 0; j < 4; j++) {
    for (int i = 0; i < 4; i++) {
      vec2 d = vec2(float(i - 1), float(j - 1)) - f;
      float dn = dot(d, n) * sx;
      float dt = dot(d, t) * st;
      float w = kern(dn * dn + dt * dt);
      sum += w * c[j * 4 + i];
      wsum += w;
    }
  }
  vec3 r = sum / max(wsum, 1e-4);
  vec3 mn = min(min(c[5], c[6]), min(c[9], c[10]));
  vec3 mx = max(max(c[5], c[6]), max(c[9], c[10]));
  o0 = vec4(clamp(r, mn, mx), 1.0);
}
