#version 120
const int shadowMapResolution = 32;
const bool shadowcolor0Clear = true;
const vec4 shadowcolor0ClearColor = vec4(0.5019607843137255, 0.25098039215686274, 0.7529411764705882, 1.0);
void main() {
    gl_FragData[0] = vec4(128.0 / 255.0, 64.0 / 255.0, 192.0 / 255.0, 1.0);
}
