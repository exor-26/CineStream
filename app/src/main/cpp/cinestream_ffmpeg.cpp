#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
}

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x00000040
#endif

#define LOG_TAG "CineFFmpeg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int RESULT_FRAME = 0;
constexpr int RESULT_NO_FRAME = 1;
constexpr int RESULT_END_OF_STREAM = 2;
constexpr int RESULT_ERROR = -1;
constexpr int VIDEO_OUTPUT_MODE_SURFACE_YUV = 1;

struct PendingPacket {
    std::vector<uint8_t> bytes;
    int64_t ptsUs = AV_NOPTS_VALUE;
};

struct ProgramState {
    GLuint id = 0;
    GLint colorMatrix = -1;
    GLint offsets = -1;
    GLint rotation = -1;
};

struct DecoderContext {
    AVCodecContext* codec = nullptr;
    int rotationDegrees = 0;
    std::deque<PendingPacket> pendingPackets;
    std::mutex frameMutex;
    std::unordered_set<AVFrame*> outstandingFrames;

    jmethodID outputInitMethod = nullptr;
    jmethodID initForPrivateFrameMethod = nullptr;
    jfieldID decoderPrivateField = nullptr;

    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    EGLConfig eglConfig = nullptr;
    ANativeWindow* nativeWindow = nullptr;

    ProgramState program8;
    ProgramState program10;
    GLuint textures[3] = {0, 0, 0};
    GLuint vertexBuffer = 0;
};

const char* kVertexShader = R"glsl(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
uniform int uRotation;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    if (uRotation == 1) {
        vTexCoord = vec2(aTexCoord.y, 1.0 - aTexCoord.x);
    } else if (uRotation == 2) {
        vTexCoord = vec2(1.0 - aTexCoord.x, 1.0 - aTexCoord.y);
    } else if (uRotation == 3) {
        vTexCoord = vec2(1.0 - aTexCoord.y, aTexCoord.x);
    } else {
        vTexCoord = aTexCoord;
    }
}
)glsl";

const char* kFragmentShader8 = R"glsl(#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D yTex;
uniform sampler2D uTex;
uniform sampler2D vTex;
uniform mat3 uColorMatrix;
uniform vec3 uOffsets;
out vec4 outColor;
void main() {
    vec3 yuv = vec3(
        texture(yTex, vTexCoord).r,
        texture(uTex, vTexCoord).r,
        texture(vTex, vTexCoord).r
    ) + uOffsets;
    outColor = vec4(uColorMatrix * yuv, 1.0);
}
)glsl";

const char* kFragmentShader10 = R"glsl(#version 300 es
precision highp float;
precision highp usampler2D;
in vec2 vTexCoord;
uniform usampler2D yTex;
uniform usampler2D uTex;
uniform usampler2D vTex;
uniform mat3 uColorMatrix;
uniform vec3 uOffsets;
out vec4 outColor;

float sample10(usampler2D tex, vec2 uv) {
    ivec2 size = textureSize(tex, 0);
    vec2 p = uv * vec2(size) - vec2(0.5);
    ivec2 base = ivec2(floor(p));
    vec2 f = fract(p);
    ivec2 hi = size - ivec2(1);
    ivec2 p00 = clamp(base, ivec2(0), hi);
    ivec2 p10 = clamp(base + ivec2(1, 0), ivec2(0), hi);
    ivec2 p01 = clamp(base + ivec2(0, 1), ivec2(0), hi);
    ivec2 p11 = clamp(base + ivec2(1, 1), ivec2(0), hi);
    float s00 = float(texelFetch(tex, p00, 0).r);
    float s10 = float(texelFetch(tex, p10, 0).r);
    float s01 = float(texelFetch(tex, p01, 0).r);
    float s11 = float(texelFetch(tex, p11, 0).r);
    return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y) / 1023.0;
}

void main() {
    vec3 yuv = vec3(
        sample10(yTex, vTexCoord),
        sample10(uTex, vTexCoord),
        sample10(vTex, vTexCoord)
    ) + uOffsets;
    outColor = vec4(uColorMatrix * yuv, 1.0);
}
)glsl";

constexpr float kLimited601[9] = {
        1.164f, 1.164f, 1.164f,
        0.0f, -0.392f, 2.017f,
        1.596f, -0.813f, 0.0f,
};
constexpr float kLimited709[9] = {
        1.164f, 1.164f, 1.164f,
        0.0f, -0.213f, 2.112f,
        1.793f, -0.533f, 0.0f,
};
constexpr float kLimited2020[9] = {
        1.168f, 1.168f, 1.168f,
        0.0f, -0.188f, 2.148f,
        1.683f, -0.652f, 0.0f,
};
constexpr float kFull601[9] = {
        1.0f, 1.0f, 1.0f,
        0.0f, -0.344136f, 1.772f,
        1.402f, -0.714136f, 0.0f,
};
constexpr float kFull709[9] = {
        1.0f, 1.0f, 1.0f,
        0.0f, -0.187324f, 1.8556f,
        1.5748f, -0.468124f, 0.0f,
};
constexpr float kFull2020[9] = {
        1.0f, 1.0f, 1.0f,
        0.0f, -0.164553f, 1.8814f,
        1.4746f, -0.571353f, 0.0f,
};

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) {
        return shader;
    }
    GLint length = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
    std::vector<char> log(std::max(1, length));
    glGetShaderInfoLog(shader, static_cast<GLsizei>(log.size()), nullptr, log.data());
    LOGE("Shader compilation failed: %s", log.data());
    glDeleteShader(shader);
    return 0;
}

ProgramState createProgram(const char* fragmentSource) {
    ProgramState state;
    GLuint vertex = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return state;
    }

    state.id = glCreateProgram();
    glAttachShader(state.id, vertex);
    glAttachShader(state.id, fragment);
    glLinkProgram(state.id);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(state.id, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint length = 0;
        glGetProgramiv(state.id, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(std::max(1, length));
        glGetProgramInfoLog(state.id, static_cast<GLsizei>(log.size()), nullptr, log.data());
        LOGE("Program link failed: %s", log.data());
        glDeleteProgram(state.id);
        state.id = 0;
        return state;
    }

    glUseProgram(state.id);
    glUniform1i(glGetUniformLocation(state.id, "yTex"), 0);
    glUniform1i(glGetUniformLocation(state.id, "uTex"), 1);
    glUniform1i(glGetUniformLocation(state.id, "vTex"), 2);
    state.colorMatrix = glGetUniformLocation(state.id, "uColorMatrix");
    state.offsets = glGetUniformLocation(state.id, "uOffsets");
    state.rotation = glGetUniformLocation(state.id, "uRotation");
    return state;
}

void destroyEglSurface(DecoderContext* context) {
    if (context->eglDisplay != EGL_NO_DISPLAY && context->eglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(
                context->eglDisplay,
                EGL_NO_SURFACE,
                EGL_NO_SURFACE,
                context->eglContext
        );
        eglDestroySurface(context->eglDisplay, context->eglSurface);
        context->eglSurface = EGL_NO_SURFACE;
    }
    if (context->nativeWindow != nullptr) {
        ANativeWindow_release(context->nativeWindow);
        context->nativeWindow = nullptr;
    }
}

bool initializeEglContext(DecoderContext* context) {
    if (context->eglContext != EGL_NO_CONTEXT) {
        return true;
    }

    context->eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (context->eglDisplay == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(context->eglDisplay, nullptr, nullptr)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        return false;
    }
    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
        LOGE("eglBindAPI failed: 0x%x", eglGetError());
        return false;
    }

    const EGLint configAttributes[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
    };
    EGLint count = 0;
    if (!eglChooseConfig(
            context->eglDisplay,
            configAttributes,
            &context->eglConfig,
            1,
            &count
    ) || count < 1) {
        LOGE("No GLES3 EGL config available");
        return false;
    }

    const EGLint contextAttributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
    };
    context->eglContext = eglCreateContext(
            context->eglDisplay,
            context->eglConfig,
            EGL_NO_CONTEXT,
            contextAttributes
    );
    if (context->eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext(GLES3) failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

bool initializeGlObjects(DecoderContext* context) {
    if (context->program8.id != 0 && context->program10.id != 0) {
        return true;
    }

    context->program8 = createProgram(kFragmentShader8);
    context->program10 = createProgram(kFragmentShader10);
    if (context->program8.id == 0 || context->program10.id == 0) {
        return false;
    }

    const float vertices[] = {
            -1.0f,  1.0f, 0.0f, 0.0f,
            -1.0f, -1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 1.0f,
    };
    glGenBuffers(1, &context->vertexBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, context->vertexBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    glGenTextures(3, context->textures);
    for (GLuint texture : context->textures) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    return glGetError() == GL_NO_ERROR;
}

bool ensureSurface(JNIEnv* env, DecoderContext* context, jobject surface) {
    if (surface == nullptr || !initializeEglContext(context)) {
        return false;
    }

    ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);
    if (newWindow == nullptr) {
        LOGE("ANativeWindow_fromSurface failed");
        return false;
    }

    if (context->nativeWindow != newWindow) {
        destroyEglSurface(context);
        context->nativeWindow = newWindow;
        context->eglSurface = eglCreateWindowSurface(
                context->eglDisplay,
                context->eglConfig,
                context->nativeWindow,
                nullptr
        );
        if (context->eglSurface == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
            destroyEglSurface(context);
            return false;
        }
    } else {
        ANativeWindow_release(newWindow);
    }

    if (!eglMakeCurrent(
            context->eglDisplay,
            context->eglSurface,
            context->eglSurface,
            context->eglContext
    )) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    return initializeGlObjects(context);
}

void chooseColorConversion(
        const AVFrame* frame,
        int bitDepth,
        const float** matrix,
        float offsets[3]
) {
    bool fullRange = frame->color_range == AVCOL_RANGE_JPEG;
    AVColorSpace colorSpace = frame->colorspace;
    if (colorSpace == AVCOL_SPC_UNSPECIFIED) {
        colorSpace = frame->width >= 1280 ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M;
    }

    if (fullRange) {
        if (colorSpace == AVCOL_SPC_BT2020_NCL || colorSpace == AVCOL_SPC_BT2020_CL) {
            *matrix = kFull2020;
        } else if (colorSpace == AVCOL_SPC_BT709) {
            *matrix = kFull709;
        } else {
            *matrix = kFull601;
        }
        offsets[0] = 0.0f;
        offsets[1] = bitDepth == 10 ? -512.0f / 1023.0f : -128.0f / 255.0f;
        offsets[2] = offsets[1];
    } else {
        if (colorSpace == AVCOL_SPC_BT2020_NCL || colorSpace == AVCOL_SPC_BT2020_CL) {
            *matrix = kLimited2020;
        } else if (colorSpace == AVCOL_SPC_BT709) {
            *matrix = kLimited709;
        } else {
            *matrix = kLimited601;
        }
        offsets[0] = bitDepth == 10 ? -64.0f / 1023.0f : -16.0f / 255.0f;
        offsets[1] = bitDepth == 10 ? -512.0f / 1023.0f : -128.0f / 255.0f;
        offsets[2] = offsets[1];
    }
}

bool uploadPlane(
        GLuint texture,
        const uint8_t* data,
        int width,
        int height,
        int lineSize,
        int bitDepth
) {
    if (data == nullptr || width <= 0 || height <= 0 || lineSize <= 0) {
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    if (bitDepth == 10) {
        if ((lineSize & 1) != 0) {
            return false;
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, lineSize / 2);
        glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_R16UI,
                width,
                height,
                0,
                GL_RED_INTEGER,
                GL_UNSIGNED_SHORT,
                data
        );
    } else {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, lineSize);
        glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_R8,
                width,
                height,
                0,
                GL_RED,
                GL_UNSIGNED_BYTE,
                data
        );
    }
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    return glGetError() == GL_NO_ERROR;
}

int normalizedRotation(int degrees) {
    int value = ((degrees % 360) + 360) % 360;
    if (value == 90) return 1;
    if (value == 180) return 2;
    if (value == 270) return 3;
    return 0;
}

bool renderFrame(DecoderContext* context, const AVFrame* frame) {
    AVPixelFormat pixelFormat = static_cast<AVPixelFormat>(frame->format);
    int bitDepth = 0;
    if (pixelFormat == AV_PIX_FMT_YUV420P || pixelFormat == AV_PIX_FMT_YUVJ420P) {
        bitDepth = 8;
    } else if (pixelFormat == AV_PIX_FMT_YUV420P10LE) {
        bitDepth = 10;
    } else {
        const char* name = av_get_pix_fmt_name(pixelFormat);
        LOGE("Unsupported FFmpeg output pixel format: %s", name != nullptr ? name : "unknown");
        return false;
    }

    int chromaWidth = (frame->width + 1) / 2;
    int chromaHeight = (frame->height + 1) / 2;
    const int widths[3] = {frame->width, chromaWidth, chromaWidth};
    const int heights[3] = {frame->height, chromaHeight, chromaHeight};

    for (int i = 0; i < 3; i++) {
        glActiveTexture(GL_TEXTURE0 + i);
        if (!uploadPlane(
                context->textures[i],
                frame->data[i],
                widths[i],
                heights[i],
                frame->linesize[i],
                bitDepth
        )) {
            LOGE("Unable to upload YUV plane %d", i);
            return false;
        }
    }

    EGLint surfaceWidth = 0;
    EGLint surfaceHeight = 0;
    eglQuerySurface(context->eglDisplay, context->eglSurface, EGL_WIDTH, &surfaceWidth);
    eglQuerySurface(context->eglDisplay, context->eglSurface, EGL_HEIGHT, &surfaceHeight);
    glViewport(0, 0, surfaceWidth, surfaceHeight);

    ProgramState& program = bitDepth == 10 ? context->program10 : context->program8;
    glUseProgram(program.id);
    const float* matrix = nullptr;
    float offsets[3] = {0.0f, 0.0f, 0.0f};
    chooseColorConversion(frame, bitDepth, &matrix, offsets);
    glUniformMatrix3fv(program.colorMatrix, 1, GL_FALSE, matrix);
    glUniform3fv(program.offsets, 1, offsets);
    glUniform1i(program.rotation, normalizedRotation(context->rotationDegrees));

    glBindBuffer(GL_ARRAY_BUFFER, context->vertexBuffer);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
    glVertexAttribPointer(
            1,
            2,
            GL_FLOAT,
            GL_FALSE,
            4 * sizeof(float),
            reinterpret_cast<void*>(2 * sizeof(float))
    );

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glGetError() != GL_NO_ERROR) {
        LOGE("OpenGL error while drawing FFmpeg frame");
        return false;
    }

    if (!eglSwapBuffers(context->eglDisplay, context->eglSurface)) {
        LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
        destroyEglSurface(context);
        return false;
    }
    return true;
}

int sendPacket(DecoderContext* context, const uint8_t* data, int length, int64_t ptsUs) {
    AVPacket* packet = av_packet_alloc();
    if (packet == nullptr) {
        return AVERROR(ENOMEM);
    }
    packet->data = const_cast<uint8_t*>(data);
    packet->size = length;
    packet->pts = ptsUs;
    packet->dts = AV_NOPTS_VALUE;
    int result = avcodec_send_packet(context->codec, packet);
    packet->data = nullptr;
    packet->size = 0;
    av_packet_free(&packet);
    return result;
}

int sendPendingPacket(DecoderContext* context) {
    if (context->pendingPackets.empty()) {
        return 0;
    }
    PendingPacket& pending = context->pendingPackets.front();
    int result = sendPacket(
            context,
            pending.bytes.data(),
            static_cast<int>(pending.bytes.size()),
            pending.ptsUs
    );
    if (result == 0) {
        context->pendingPackets.pop_front();
    }
    return result;
}

int64_t frameTimestampUs(const AVFrame* frame, int64_t fallbackUs) {
    if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
        return frame->best_effort_timestamp;
    }
    if (frame->pts != AV_NOPTS_VALUE) {
        return frame->pts;
    }
    return fallbackUs;
}

void freeOutstandingFrame(DecoderContext* context, AVFrame* frame) {
    if (frame == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(context->frameMutex);
    auto found = context->outstandingFrames.find(frame);
    if (found != context->outstandingFrames.end()) {
        context->outstandingFrames.erase(found);
        av_frame_free(&frame);
    }
}

void releaseContext(DecoderContext* context) {
    if (context == nullptr) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(context->frameMutex);
        for (AVFrame* frame : context->outstandingFrames) {
            AVFrame* owned = frame;
            av_frame_free(&owned);
        }
        context->outstandingFrames.clear();
    }

    if (context->eglDisplay != EGL_NO_DISPLAY && context->eglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(
                context->eglDisplay,
                context->eglSurface,
                context->eglSurface,
                context->eglContext
        );
        if (context->vertexBuffer != 0) glDeleteBuffers(1, &context->vertexBuffer);
        if (context->textures[0] != 0) glDeleteTextures(3, context->textures);
        if (context->program8.id != 0) glDeleteProgram(context->program8.id);
        if (context->program10.id != 0) glDeleteProgram(context->program10.id);
    }

    destroyEglSurface(context);
    if (context->eglDisplay != EGL_NO_DISPLAY) {
        if (context->eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(context->eglDisplay, context->eglContext);
        }
        eglTerminate(context->eglDisplay);
    }

    if (context->codec != nullptr) {
        avcodec_free_context(&context->codec);
    }
    delete context;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegLibrary_nativeIsUsable(
        JNIEnv*,
        jclass
) {
    return avcodec_find_decoder_by_name("hevc") != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegLibrary_nativeHasDecoder(
        JNIEnv* env,
        jclass,
        jstring codecName
) {
    std::string name = jstringToString(env, codecName);
    return !name.empty() && avcodec_find_decoder_by_name(name.c_str()) != nullptr
            ? JNI_TRUE
            : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegLibrary_nativeGetVersion(
        JNIEnv* env,
        jclass
) {
    return env->NewStringUTF(av_version_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegVideoDecoder_nativeInitialize(
        JNIEnv* env,
        jclass,
        jstring codecName,
        jbyteArray extraData,
        jint threads,
        jint rotationDegrees,
        jint width,
        jint height
) {
    std::string name = jstringToString(env, codecName);
    const AVCodec* codec = avcodec_find_decoder_by_name(name.c_str());
    if (codec == nullptr) {
        LOGE("FFmpeg decoder not found: %s", name.c_str());
        return 0L;
    }

    auto* context = new DecoderContext();
    context->codec = avcodec_alloc_context3(codec);
    if (context->codec == nullptr) {
        delete context;
        return 0L;
    }

    context->rotationDegrees = rotationDegrees;
    context->codec->thread_count = std::max(1, static_cast<int>(threads));
    context->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    context->codec->pkt_timebase = AVRational{1, 1000000};
    if (width > 0) context->codec->width = width;
    if (height > 0) context->codec->height = height;

    if (extraData != nullptr) {
        jsize size = env->GetArrayLength(extraData);
        if (size > 0) {
            context->codec->extradata = static_cast<uint8_t*>(
                    av_mallocz(static_cast<size_t>(size) + AV_INPUT_BUFFER_PADDING_SIZE)
            );
            if (context->codec->extradata == nullptr) {
                releaseContext(context);
                return 0L;
            }
            context->codec->extradata_size = size;
            env->GetByteArrayRegion(
                    extraData,
                    0,
                    size,
                    reinterpret_cast<jbyte*>(context->codec->extradata)
            );
        }
    }

    int openResult = avcodec_open2(context->codec, codec, nullptr);
    if (openResult < 0) {
        char error[AV_ERROR_MAX_STRING_SIZE] = {};
        av_strerror(openResult, error, sizeof(error));
        LOGE("avcodec_open2(%s) failed: %s", name.c_str(), error);
        releaseContext(context);
        return 0L;
    }

    jclass outputBufferClass = env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
    if (outputBufferClass == nullptr) {
        releaseContext(context);
        return 0L;
    }
    context->outputInitMethod = env->GetMethodID(
            outputBufferClass,
            "init",
            "(JILjava/nio/ByteBuffer;)V"
    );
    context->initForPrivateFrameMethod = env->GetMethodID(
            outputBufferClass,
            "initForPrivateFrame",
            "(II)V"
    );
    context->decoderPrivateField = env->GetFieldID(outputBufferClass, "decoderPrivate", "J");
    if (context->outputInitMethod == nullptr
            || context->initForPrivateFrameMethod == nullptr
            || context->decoderPrivateField == nullptr) {
        releaseContext(context);
        return 0L;
    }

    LOGI("Initialized FFmpeg %s decoder, threads=%d", name.c_str(), context->codec->thread_count);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegVideoDecoder_nativeDecodePacket(
        JNIEnv* env,
        jclass,
        jlong nativeContext,
        jobject encodedData,
        jint offset,
        jint length,
        jlong presentationTimeUs,
        jint outputMode,
        jobject outputBuffer,
        jboolean decodeOnly
) {
    auto* context = reinterpret_cast<DecoderContext*>(nativeContext);
    if (context == nullptr || context->codec == nullptr || length < 0 || outputBuffer == nullptr) {
        return RESULT_ERROR;
    }

    const uint8_t* packetData = nullptr;
    if (length > 0) {
        if (encodedData == nullptr) {
            return RESULT_ERROR;
        }
        auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(encodedData));
        jlong capacity = env->GetDirectBufferCapacity(encodedData);
        if (base == nullptr
                || offset < 0
                || static_cast<jlong>(offset) + length > capacity) {
            return RESULT_ERROR;
        }
        packetData = base + offset;
    }

    if (length > 0) {
        if (context->pendingPackets.empty()) {
            int sendResult = sendPacket(context, packetData, length, presentationTimeUs);
            if (sendResult == AVERROR(EAGAIN)) {
                PendingPacket pending;
                pending.bytes.assign(packetData, packetData + length);
                pending.ptsUs = presentationTimeUs;
                context->pendingPackets.push_back(std::move(pending));
            } else if (sendResult < 0) {
                char error[AV_ERROR_MAX_STRING_SIZE] = {};
                av_strerror(sendResult, error, sizeof(error));
                LOGE("avcodec_send_packet failed: %s", error);
                return RESULT_ERROR;
            }
        } else {
            PendingPacket pending;
            pending.bytes.assign(packetData, packetData + length);
            pending.ptsUs = presentationTimeUs;
            context->pendingPackets.push_back(std::move(pending));
        }
    }

    while (true) {
        AVFrame* frame = av_frame_alloc();
        if (frame == nullptr) {
            return RESULT_ERROR;
        }
        int receiveResult = avcodec_receive_frame(context->codec, frame);
        if (receiveResult == 0) {
            if (decodeOnly == JNI_TRUE || outputMode != VIDEO_OUTPUT_MODE_SURFACE_YUV) {
                av_frame_free(&frame);
                return RESULT_NO_FRAME;
            }

            int64_t ptsUs = frameTimestampUs(frame, presentationTimeUs);
            env->CallVoidMethod(
                    outputBuffer,
                    context->outputInitMethod,
                    static_cast<jlong>(ptsUs),
                    outputMode,
                    nullptr
            );
            int displayWidth = frame->width;
            int displayHeight = frame->height;
            int rotation = ((context->rotationDegrees % 360) + 360) % 360;
            if (rotation == 90 || rotation == 270) {
                std::swap(displayWidth, displayHeight);
            }
            env->CallVoidMethod(
                    outputBuffer,
                    context->initForPrivateFrameMethod,
                    displayWidth,
                    displayHeight
            );
            if (env->ExceptionCheck()) {
                av_frame_free(&frame);
                return RESULT_ERROR;
            }

            {
                std::lock_guard<std::mutex> lock(context->frameMutex);
                context->outstandingFrames.insert(frame);
            }
            env->SetLongField(
                    outputBuffer,
                    context->decoderPrivateField,
                    reinterpret_cast<jlong>(frame)
            );
            return RESULT_FRAME;
        }

        av_frame_free(&frame);
        if (receiveResult == AVERROR(EAGAIN)) {
            if (context->pendingPackets.empty()) {
                return RESULT_NO_FRAME;
            }
            int sendResult = sendPendingPacket(context);
            if (sendResult == 0) {
                continue;
            }
            if (sendResult == AVERROR(EAGAIN)) {
                LOGE("FFmpeg send/receive contract stalled with EAGAIN on both sides");
                return RESULT_ERROR;
            }
            char error[AV_ERROR_MAX_STRING_SIZE] = {};
            av_strerror(sendResult, error, sizeof(error));
            LOGE("Unable to send pending packet: %s", error);
            return RESULT_ERROR;
        }
        if (receiveResult == AVERROR_EOF) {
            return RESULT_END_OF_STREAM;
        }

        char error[AV_ERROR_MAX_STRING_SIZE] = {};
        av_strerror(receiveResult, error, sizeof(error));
        LOGE("avcodec_receive_frame failed: %s", error);
        return RESULT_ERROR;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegVideoDecoder_nativeSendEndOfStream(
        JNIEnv*,
        jclass,
        jlong nativeContext
) {
    auto* context = reinterpret_cast<DecoderContext*>(nativeContext);
    if (context == nullptr || context->codec == nullptr) {
        return RESULT_ERROR;
    }
    if (!context->pendingPackets.empty()) {
        LOGE("Cannot signal FFmpeg EOS while compressed packets remain pending");
        return RESULT_NO_FRAME;
    }

    int result = avcodec_send_packet(context->codec, nullptr);
    if (result == 0) {
        return RESULT_FRAME;
    }
    if (result == AVERROR(EAGAIN)) {
        return RESULT_NO_FRAME;
    }
    if (result == AVERROR_EOF) {
        return RESULT_END_OF_STREAM;
    }

    char error[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(result, error, sizeof(error));
    LOGE("Unable to signal FFmpeg EOS: %s", error);
    return RESULT_ERROR;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegVideoDecoder_nativeFlush(
        JNIEnv*,
        jclass,
        jlong nativeContext
) {
    auto* context = reinterpret_cast<DecoderContext*>(nativeContext);
    if (context == nullptr || context->codec == nullptr) {
        return RESULT_ERROR;
    }
    context->pendingPackets.clear();
    avcodec_flush_buffers(context->codec);
    return RESULT_FRAME;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegVideoDecoder_nativeRenderFrame(
        JNIEnv* env,
        jclass,
        jlong nativeContext,
        jlong nativeFrame,
        jobject surface
) {
    auto* context = reinterpret_cast<DecoderContext*>(nativeContext);
    auto* frame = reinterpret_cast<AVFrame*>(nativeFrame);
    if (context == nullptr || frame == nullptr || surface == nullptr) {
        return RESULT_ERROR;
    }

    {
        std::lock_guard<std::mutex> lock(context->frameMutex);
        if (context->outstandingFrames.find(frame) == context->outstandingFrames.end()) {
            return RESULT_ERROR;
        }
    }

    if (!ensureSurface(env, context, surface)) {
        return RESULT_ERROR;
    }
    return renderFrame(context, frame) ? RESULT_FRAME : RESULT_ERROR;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegVideoDecoder_nativeReleaseFrame(
        JNIEnv*,
        jclass,
        jlong nativeContext,
        jlong nativeFrame
) {
    auto* context = reinterpret_cast<DecoderContext*>(nativeContext);
    auto* frame = reinterpret_cast<AVFrame*>(nativeFrame);
    if (context != nullptr && frame != nullptr) {
        freeOutstandingFrame(context, frame);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cinestream_ffmpeg_CineFfmpegVideoDecoder_nativeRelease(
        JNIEnv*,
        jclass,
        jlong nativeContext
) {
    releaseContext(reinterpret_cast<DecoderContext*>(nativeContext));
}
