#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
//__VIDEO__
uniform sampler2D uUp;   // upscaled image, same size as the output
uniform float uSharp;    // 0..1
uniform float uContrast;
uniform float uSat;
uniform float uWipe;
in vec2 vUV;
in vec2 vVideoUV;
out vec4 fragColor;

// Contrast-limited sharpening: the negative lobe is limited per channel so the result cannot
// overshoot the local min/max (no halos), then contrast / saturation.
const float LIMIT = 0.1875;

void main() {
  if (vUV.x < uWipe) {
    fragColor = vec4(texture(uVideo, vVideoUV).rgb, 1.0);
    return;
  }
  ivec2 q = ivec2(gl_FragCoord.xy);
  ivec2 hi = textureSize(uUp, 0) - 1;
  vec3 e = texelFetch(uUp, q, 0).rgb;
  vec3 b = texelFetch(uUp, clamp(q + ivec2(0, -1), ivec2(0), hi), 0).rgb;
  vec3 d = texelFetch(uUp, clamp(q + ivec2(-1, 0), ivec2(0), hi), 0).rgb;
  vec3 f = texelFetch(uUp, clamp(q + ivec2(1, 0), ivec2(0), hi), 0).rgb;
  vec3 h = texelFetch(uUp, clamp(q + ivec2(0, 1), ivec2(0), hi), 0).rgb;

  vec3 c = e;
  if (uSharp > 0.001) {
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec3 hitMin = mn4 / (4.0 * max(mx4, vec3(1e-4)));
    vec3 hitMax = (1.0 - mx4) / (4.0 * mn4 - 4.0 - 1e-4);
    vec3 lobeC = max(-hitMin, hitMax);
    float lobe = max(-LIMIT, min(0.0, max(lobeC.r, max(lobeC.g, lobeC.b)))) * uSharp * 0.5;
    c = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
  }
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(l), c, uSat);
  c = (c - 0.5) * uContrast + 0.5;
  fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
