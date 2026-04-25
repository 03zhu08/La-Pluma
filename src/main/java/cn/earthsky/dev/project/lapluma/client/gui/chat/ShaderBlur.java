package cn.earthsky.dev.project.lapluma.client.gui.chat;

import cn.earthsky.dev.project.lapluma.LaPluma;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class ShaderBlur {

    private int programH = -1;
    private int programV = -1;
    private Framebuffer fboA;
    private Framebuffer fboB;
    private boolean initialized = false;
    private boolean supported = true;
    private float blurRadius = 12.0f;

    public void init() {
        if (!OpenGlHelper.shadersSupported) {
            supported = false;
            return;
        }
        try {
            int vert = loadShader(new ResourceLocation("lapluma", "shaders/blur.vsh"), GL20.GL_VERTEX_SHADER);
            int fragH = loadShader(new ResourceLocation("lapluma", "shaders/blur_h.fsh"), GL20.GL_FRAGMENT_SHADER);
            int fragV = loadShader(new ResourceLocation("lapluma", "shaders/blur_v.fsh"), GL20.GL_FRAGMENT_SHADER);

            programH = GL20.glCreateProgram();
            GL20.glAttachShader(programH, vert);
            GL20.glAttachShader(programH, fragH);
            GL20.glLinkProgram(programH);

            programV = GL20.glCreateProgram();
            GL20.glAttachShader(programV, vert);
            GL20.glAttachShader(programV, fragV);
            GL20.glLinkProgram(programV);

            initialized = true;
        } catch (Throwable e) {
            LaPluma.getLogger().log(Level.WARNING, "[Shader] Failed to init blur shader", e);
            supported = false;
        }
    }

    private int loadShader(ResourceLocation loc, int type) throws Exception {
        IResource res = Minecraft.getMinecraft().getResourceManager().getResource(loc);
        BufferedReader reader = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        reader.close();

        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, sb.toString());
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 1024);
            throw new RuntimeException("Shader compile error: " + log);
        }
        return shader;
    }

    private void ensureFBOs(int w, int h) {
        if (fboA == null || fboA.framebufferWidth != w || fboA.framebufferHeight != h) {
            if (fboA != null) fboA.deleteFramebuffer();
            if (fboB != null) fboB.deleteFramebuffer();
            fboA = new Framebuffer(w, h, false);
            fboB = new Framebuffer(w, h, false);
        }
    }

    public void renderBlurredBackground(int screenWidth, int screenHeight) {
        if (!supported) return;
        if (!initialized) init();
        if (!initialized) return;

        Minecraft mc = Minecraft.getMinecraft();
        int w = mc.displayWidth;
        int h = mc.displayHeight;
        ensureFBOs(w, h);

        fboA.framebufferClear();
        fboA.bindFramebuffer(true);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, screenWidth, screenHeight, 0, 1000, 3000);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0, 0, -2000);

        mc.getFramebuffer().bindFramebufferTexture();
        drawFullscreenQuad(screenWidth, screenHeight);

        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);

        // Horizontal pass
        fboB.framebufferClear();
        fboB.bindFramebuffer(true);
        GL20.glUseProgram(programH);
        GL20.glUniform1i(GL20.glGetUniformLocation(programH, "textureSampler"), 0);
        GL20.glUniform2f(GL20.glGetUniformLocation(programH, "texelSize"), 1.0f / w, 1.0f / h);
        GL20.glUniform1f(GL20.glGetUniformLocation(programH, "radius"), blurRadius);
        fboA.bindFramebufferTexture();
        drawFullscreenQuad(screenWidth, screenHeight);
        GL20.glUseProgram(0);

        // Vertical pass
        mc.getFramebuffer().bindFramebuffer(true);
        GL20.glUseProgram(programV);
        GL20.glUniform1i(GL20.glGetUniformLocation(programV, "textureSampler"), 0);
        GL20.glUniform2f(GL20.glGetUniformLocation(programV, "texelSize"), 1.0f / w, 1.0f / h);
        GL20.glUniform1f(GL20.glGetUniformLocation(programV, "radius"), blurRadius);
        fboB.bindFramebufferTexture();
        drawFullscreenQuad(screenWidth, screenHeight);
        GL20.glUseProgram(0);
    }

    private void drawFullscreenQuad(int w, int h) {
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(0, 0);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(w, 0);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(w, h);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(0, h);
        GL11.glEnd();
    }

    public void cleanup() {
        if (fboA != null) { fboA.deleteFramebuffer(); fboA = null; }
        if (fboB != null) { fboB.deleteFramebuffer(); fboB = null; }
    }

    public void setBlurRadius(float radius) {
        this.blurRadius = radius;
    }
}
