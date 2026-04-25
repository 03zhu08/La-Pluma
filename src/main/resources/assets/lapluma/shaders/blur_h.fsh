#version 120

uniform sampler2D textureSampler;
uniform vec2 texelSize;
uniform float radius;

varying vec2 texCoord;

void main() {
    vec4 color = vec4(0.0);
    float total = 0.0;
    for (float x = -radius; x <= radius; x += 1.0) {
        float weight = exp(-(x * x) / (2.0 * radius * radius / 4.0));
        color += texture2D(textureSampler, texCoord + vec2(x * texelSize.x, 0.0)) * weight;
        total += weight;
    }
    gl_FragColor = color / total;
}
