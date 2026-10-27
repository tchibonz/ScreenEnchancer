#version 300 es
in vec2 aPos;
in vec2 aUV;
uniform mat4 uST;
out vec2 vUV;
out vec2 vVideoUV;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vUV = aUV;
  vVideoUV = (uST * vec4(aUV, 0.0, 1.0)).xy;
}
