#version 120

uniform sampler2D textureSampler;
uniform vec2 texelSize;
uniform float radius;

varying vec2 texCoord;

void main() {
    vec4 color = vec4(0.0);
    float total = 0.0;
    for (float y = -radius; y <= radius; y += 1.0) {
        float weight = exp(-(y * y) / (2.0 * radius * radius / 4.0));
        color += texture2D(textureSampler, texCoord + vec2(0.0, y * texelSize.y)) * weight;
        total += weight;
    }
    gl_FragColor = color / total;
}
