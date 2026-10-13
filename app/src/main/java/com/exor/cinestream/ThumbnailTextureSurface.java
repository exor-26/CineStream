package com.exor.cinestream;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Decoder-private buffers are sampled through a texture, never interpreted as RGBA planes. */
final class ThumbnailTextureSurface implements AutoCloseable {
    private final int width;
    private final int height;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface surface;
    private int program;
    private int textureId;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    ThumbnailTextureSurface(int width, int height, Handler handler, Runnable onFrame) {
        this.width = width;
        this.height = height;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                throw new IllegalStateException("Thumbnail EGL initialization failed");
            }
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) throw new IllegalStateException("No thumbnail EGL config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                throw new IllegalStateException("Thumbnail EGL surface unavailable");
            }
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            int vertex = shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 aPosition; attribute vec2 aUv; uniform mat4 uTransform;"
                    + "varying vec2 vUv; void main(){ gl_Position=vec4(aPosition,0.,1.);"
                    + "vUv=(uTransform*vec4(aUv,0.,1.)).xy; }");
            int fragment;
            try {
                fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                        "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float; uniform samplerExternalOES uTexture;"
                        + "varying vec2 vUv; void main(){ gl_FragColor=texture2D(uTexture,vUv); }");
            } catch (RuntimeException failure) {
                GLES20.glDeleteShader(vertex);
                throw failure;
            }
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException("Thumbnail shader link failed");
            // Flip vertically while sampling: glReadPixels starts at the bottom of the buffer.
            setVideoAspect((float) width / height);
            texture = new SurfaceTexture(textureId);
            texture.setDefaultBufferSize(width, height);
            texture.setOnFrameAvailableListener(ignored -> onFrame.run(), handler);
            surface = new Surface(texture);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    Surface surface() { return surface; }

    void setVideoAspect(float sourceAspect) {
        if (!(sourceAspect > 0)) return;
        float outputAspect = (float) width / height;
        float halfU = sourceAspect > outputAspect ? outputAspect / sourceAspect / 2 : .5f;
        float halfV = sourceAspect < outputAspect ? sourceAspect / outputAspect / 2 : .5f;
        float u0 = .5f - halfU, u1 = .5f + halfU;
        float v0 = .5f - halfV, v1 = .5f + halfV;
        vertices.rewind();
        vertices.put(new float[]{-1,-1,u0,v1, 1,-1,u1,v1, -1,1,u0,v0, 1,1,u1,v0}).rewind();
    }

    Bitmap capture() {
        texture.updateTexImage();
        float[] transform = new float[16];
        texture.getTransformMatrix(transform);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"),
                1, false, transform, 0);
        int position = GLES20.glGetAttribLocation(program, "aPosition");
        int uv = GLES20.glGetAttribLocation(program, "aUv");
        vertices.position(0);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(position);
        vertices.position(2);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException("Thumbnail texture read failed");
        }
        pixels.rewind();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(pixels);
        return bitmap;
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Thumbnail shader compilation failed");
        }
        return shader;
    }

    @Override public void close() {
        if (surface != null) { surface.release(); surface = null; }
        if (texture != null) { texture.release(); texture = null; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
                if (program != 0) GLES20.glDeleteProgram(program);
                if (textureId != 0) GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }
}
