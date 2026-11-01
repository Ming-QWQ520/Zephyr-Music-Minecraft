#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 panelPos;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // UV 承载"面板局部像素坐标"（0..w, 0..h），供片元端做 SDF 计算
    panelPos = UV0;
    vertexColor = Color;
}
