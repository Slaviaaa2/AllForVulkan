#version 120
const int shadowMapResolution = 32;
const float shadowDistance = 32.0;
varying vec2 texcoord;
varying vec4 tint;
void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    tint = gl_Color;
}
