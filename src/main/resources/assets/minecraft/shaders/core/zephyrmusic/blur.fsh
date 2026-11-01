#version 150

in vec2 texCoord;

uniform sampler2D Sampler0;
uniform vec2 TexelSize;
uniform vec2 Direction;

out vec4 fragColor;

/* 9-tap 可分离高斯模糊（sigma ~ 2.0），按 Direction 水平/垂直两次通过 */
void main() {
    float w0 = 0.227027;
    float w1 = 0.1945946;
    float w2 = 0.1216216;
    float w3 = 0.0540540;
    float w4 = 0.0162162;

    vec2 st = Direction * TexelSize;

    vec4 c = texture(Sampler0, texCoord) * w0;
    c += texture(Sampler0, texCoord + st * 1.5) * w1;
    c += texture(Sampler0, texCoord - st * 1.5) * w1;
    c += texture(Sampler0, texCoord + st * 3.5) * w2;
    c += texture(Sampler0, texCoord - st * 3.5) * w2;
    c += texture(Sampler0, texCoord + st * 5.5) * w3;
    c += texture(Sampler0, texCoord - st * 5.5) * w3;
    c += texture(Sampler0, texCoord + st * 7.5) * w4;
    c += texture(Sampler0, texCoord - st * 7.5) * w4;

    fragColor = c;
}
