#version 120
const int shadowMapResolution = 32;
const float shadowDistance = 32.0;
const bool shadowcolor0Clear = true;
const vec4 shadowcolor0ClearColor = vec4(0.125, 0.0, 0.125, 1.0);
uniform sampler2D gtexture;
varying vec2 texcoord;
varying vec4 tint;
void main() {
    gl_FragData[0] = texture2D(gtexture, texcoord) * tint;
}
