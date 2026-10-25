#version 120
const int shadowMapResolution = 32;
const bool shadowcolor0Clear = true;
const vec4 shadowcolor0ClearColor = vec4(0.5019607843137255, 0.25098039215686274, 0.7529411764705882, 1.0);
varying vec2 texcoord;
varying vec4 tint;
void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    tint = gl_Color;
}
