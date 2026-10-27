#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
//__VIDEO__
in vec2 vVideoUV;
layout(location=0) out vec4 o0;

void main() {
  vec3 c = texture(uVideo, vVideoUV).rgb;
  o0 = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
