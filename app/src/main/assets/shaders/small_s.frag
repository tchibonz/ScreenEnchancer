#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uF0;
layout(location=0) out vec4 o0;

void main() {
  ivec2 q = ivec2(gl_FragCoord.xy);
  vec4 v = texelFetch(uF0, q >> 1, 0);
  int k = (q.y & 1) * 2 + (q.x & 1);
  float y = k == 0 ? v.x : (k == 1 ? v.y : (k == 2 ? v.z : v.w));
  o0 = vec4(y + 0.398698032, 0.0, 0.0, 1.0);
}
