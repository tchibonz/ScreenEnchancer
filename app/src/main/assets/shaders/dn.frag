#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
//__VIDEO__
uniform vec2 uSize;   // video size in pixels
uniform float uDn;    // 0..1 strength
in vec2 vUV;
in vec2 vVideoUV;
layout(location=0) out vec4 o0;

// Edge-preserving noise / compression-artifact cleaner (5x5 bilateral, luma-guided).
// Flat areas lose mosquito noise and blocking; strong edges keep their weight.
const vec3 LUMA = vec3(0.299, 0.587, 0.114);

void main() {
  vec2 px = 1.0 / uSize;
  vec3 c = texture(uVideo, vVideoUV).rgb;
  float lc = dot(c, LUMA);
  float sr = mix(0.02, 0.12, uDn);
  float inv = -0.5 / (sr * sr);
  vec3 acc = vec3(0.0);
  float wsum = 0.0;
  for (int j = -2; j <= 2; j++) {
    for (int i = -2; i <= 2; i++) {
      vec2 o = vec2(float(i), float(j));
      vec3 s = texture(uVideo, vVideoUV + o * px).rgb;
      float d = dot(s, LUMA) - lc;
      float w = exp(inv * d * d - dot(o, o) / 4.5);
      acc += w * s;
      wsum += w;
    }
  }
  o0 = vec4(mix(c, acc / wsum, min(1.0, 0.4 + 0.8 * uDn)), 1.0);
}
