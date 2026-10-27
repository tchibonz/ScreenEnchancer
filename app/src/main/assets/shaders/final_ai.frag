#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
//__VIDEO__
uniform sampler2D uNet;
uniform vec2 uPx;
uniform float uSharp;
uniform float uContrast;
uniform float uSat;
uniform float uWipe;
in vec2 vUV;
in vec2 vVideoUV;
out vec4 fragColor;

void main() {
  vec3 c = texture(uVideo, vVideoUV).rgb;
  if (vUV.x < uWipe) { fragColor = vec4(c, 1.0); return; }
  float ys = dot(c, vec3(0.299, 0.587, 0.114));
  float yn = texture(uNet, vUV).r;
  if (uSharp > 0.001) {
    float e = texture(uNet, vUV + vec2(uPx.x, 0.0)).r;
    float w = texture(uNet, vUV - vec2(uPx.x, 0.0)).r;
    float n = texture(uNet, vUV + vec2(0.0, uPx.y)).r;
    float s = texture(uNet, vUV - vec2(0.0, uPx.y)).r;
    yn += (yn - 0.25 * (e + w + n + s)) * uSharp;
  }
  vec3 rgb = c + (yn - ys);
  float l = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
  rgb = mix(vec3(l), rgb, uSat);
  rgb = (rgb - 0.5) * uContrast + 0.5;
  fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
