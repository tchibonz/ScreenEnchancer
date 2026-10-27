#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
//__VIDEO__
uniform vec2 uTexel;
uniform float uSharp;
uniform float uContrast;
uniform float uSat;
uniform float uWipe;
in vec2 vUV;
in vec2 vVideoUV;
out vec4 fragColor;

void main() {
  vec3 e = texture(uVideo, vVideoUV).rgb;
  if (vUV.x < uWipe) { fragColor = vec4(e, 1.0); return; }
  vec3 c = e;
  if (uSharp > 0.001) {
    vec3 b = texture(uVideo, vVideoUV + vec2(0.0, -uTexel.y)).rgb;
    vec3 d = texture(uVideo, vVideoUV + vec2(-uTexel.x, 0.0)).rgb;
    vec3 f = texture(uVideo, vVideoUV + vec2(uTexel.x, 0.0)).rgb;
    vec3 h = texture(uVideo, vVideoUV + vec2(0.0, uTexel.y)).rgb;
    vec3 mn = min(min(min(d, e), min(f, b)), h);
    vec3 mx = max(max(max(d, e), max(f, b)), h);
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, uSharp);
    vec3 w = amp * peak;
    c = (b * w + d * w + f * w + h * w + e) / (1.0 + 4.0 * w);
  }
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(l), c, uSat);
  c = (c - 0.5) * uContrast + 0.5;
  fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
