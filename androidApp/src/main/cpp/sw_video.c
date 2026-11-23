// H.264/HEVC software decoding for ExoPlayer, through the FFmpeg libmpv ships, for streams the
// device's decoders can't take (10-bit H.264). Frames come out as 8-bit I420 and go to the
// surface as YV12.

#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "libavcodec/avcodec.h"
#include "libavutil/frame.h"
#include "libavutil/mem.h"
#include "libswscale/swscale.h"

#define TAG "SoftwareVideo"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define RESULT_OK 0
#define RESULT_SKIP 1
#define RESULT_ERROR (-1)
#define RESULT_AGAIN 2
#define RESULT_EOF 3

#define COLORSPACE_UNKNOWN 0
#define COLORSPACE_BT601 1
#define COLORSPACE_BT709 2
#define COLORSPACE_BT2020 3

#define HAL_PIXEL_FORMAT_YV12 0x32315659
#define ALIGN(x, a) (((x) + ((a) - 1)) & ~((a) - 1))

#define AVCODEC(X) \
    X(avcodec_version) X(avcodec_find_decoder_by_name) X(avcodec_alloc_context3) X(avcodec_open2) \
    X(avcodec_free_context) X(avcodec_send_packet) X(avcodec_receive_frame) X(avcodec_flush_buffers) \
    X(av_packet_alloc) X(av_packet_free)
#define AVUTIL(X) X(avutil_version) X(av_frame_alloc) X(av_frame_ref) X(av_frame_free) X(av_frame_unref) X(av_mallocz)
#define SWSCALE(X) X(swscale_version) X(sws_getCachedContext) X(sws_getCoefficients) X(sws_setColorspaceDetails) X(sws_scale) X(sws_freeContext)

typedef int32_t (*SetDataSpace)(ANativeWindow *window, int32_t dataSpace);

static struct {
#define MEMBER(name) __typeof__(name) *name;
    AVCODEC(MEMBER)
    AVUTIL(MEMBER)
    SWSCALE(MEMBER)
#undef MEMBER
    SetDataSpace set_data_space;
    int loaded;
} ff;

static int load_ffmpeg(void) {
    if (ff.loaded) return ff.loaded > 0;
    ff.loaded = -1;
    void *avutil = dlopen("libavutil.so", RTLD_NOW);
    void *swscale = dlopen("libswscale.so", RTLD_NOW);
    void *avcodec = dlopen("libavcodec.so", RTLD_NOW);
    if (!avutil || !swscale || !avcodec) {
        LOGW("FFmpeg libraries are missing");
        return 0;
    }
#define RESOLVE(lib, name) if (!(ff.name = dlsym(lib, #name))) { LOGW("missing %s", #name); return 0; }
#define FROM_AVCODEC(name) RESOLVE(avcodec, name)
#define FROM_AVUTIL(name) RESOLVE(avutil, name)
#define FROM_SWSCALE(name) RESOLVE(swscale, name)
    AVCODEC(FROM_AVCODEC)
    AVUTIL(FROM_AVUTIL)
    SWSCALE(FROM_SWSCALE)
#undef FROM_SWSCALE
#undef FROM_AVUTIL
#undef FROM_AVCODEC
#undef RESOLVE
    if ((ff.avcodec_version() >> 16) != LIBAVCODEC_VERSION_MAJOR ||
        (ff.avutil_version() >> 16) != LIBAVUTIL_VERSION_MAJOR ||
        (ff.swscale_version() >> 16) != LIBSWSCALE_VERSION_MAJOR) {
        LOGW("FFmpeg ABI mismatch: avcodec %u avutil %u swscale %u",
             ff.avcodec_version() >> 16, ff.avutil_version() >> 16, ff.swscale_version() >> 16);
        return 0;
    }
    void *android = dlopen("libandroid.so", RTLD_NOW);
    if (android) ff.set_data_space = (SetDataSpace) dlsym(android, "ANativeWindow_setBuffersDataSpace");
    ff.loaded = 1;
    return 1;
}

typedef struct {
    AVCodecContext *codec;
    AVPacket *packet;
    AVFrame *frame;
    int has_frame;
    struct SwsContext *sws;
    int sws_source_width;
    int sws_source_height;
    int sws_source_format;
    int sws_colorspace;
    int sws_full_range;
    int sws_colorspace_configured;
    ANativeWindow *window;
    jobject surface;
    int window_width;
    int window_height;
    int32_t window_data_space;
    int32_t window_data_space_attempted;
} Decoder;

static int colorspace_of(const AVFrame *frame) {
    switch (frame->colorspace) {
        case AVCOL_SPC_BT709: return COLORSPACE_BT709;
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M: return COLORSPACE_BT601;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: return COLORSPACE_BT2020;
        default: return frame->height > 576 ? COLORSPACE_BT709 : COLORSPACE_BT601;
    }
}

static int swscale_colorspace_of(const AVFrame *frame) {
    switch (colorspace_of(frame)) {
        case COLORSPACE_BT2020: return SWS_CS_BT2020;
        case COLORSPACE_BT709: return SWS_CS_ITU709;
        default: return SWS_CS_ITU601;
    }
}

/* Matrix/range configuration is invariant until source geometry, format or color metadata changes. */
static int configure_sws_colorspace(Decoder *d, const AVFrame *frame) {
    const int source_colorspace = swscale_colorspace_of(frame);
    const int full_range = frame->color_range == AVCOL_RANGE_JPEG;
    if (d->sws_colorspace_configured &&
        d->sws_source_width == frame->width &&
        d->sws_source_height == frame->height &&
        d->sws_source_format == frame->format &&
        d->sws_colorspace == source_colorspace &&
        d->sws_full_range == full_range) {
        return 1;
    }

    const int *coefficients = ff.sws_getCoefficients(source_colorspace);
    if (!coefficients || ff.sws_setColorspaceDetails(
            d->sws, coefficients, full_range, coefficients, full_range, 0, 1 << 16, 1 << 16) < 0) {
        d->sws_colorspace_configured = 0;
        return 0;
    }

    d->sws_source_width = frame->width;
    d->sws_source_height = frame->height;
    d->sws_source_format = frame->format;
    d->sws_colorspace = source_colorspace;
    d->sws_full_range = full_range;
    d->sws_colorspace_configured = 1;
    return 1;
}

static int32_t data_space_of(const AVFrame *frame) {
    int32_t standard;
    switch (colorspace_of(frame)) {
        case COLORSPACE_BT2020: standard = 6 << 16; break;
        case COLORSPACE_BT601: standard = (frame->height == 480 ? 4 : 2) << 16; break;
        default: standard = 1 << 16; break;
    }
    int32_t transfer;
    switch (frame->color_trc) {
        case AVCOL_TRC_SMPTE2084: transfer = 7 << 22; break;
        case AVCOL_TRC_ARIB_STD_B67: transfer = 8 << 22; break;
        default: transfer = 3 << 22; break;
    }
    int32_t range = frame->color_range == AVCOL_RANGE_JPEG ? 1 << 27 : 2 << 27;
    return standard | transfer | range;
}

/* Validate the Java-owned I420 buffer layout before any native pointer arithmetic. */
static int yuv420_buffer_size(int width, int height, int y_stride, int uv_stride, size_t *size) {
    if (width <= 0 || height <= 0 || y_stride < width || uv_stride < (width / 2) + (width % 2)) {
        return 0;
    }
    size_t y_row = (size_t) y_stride;
    size_t uv_row = (size_t) uv_stride;
    size_t y_height = (size_t) height;
    size_t uv_height = (y_height + 1) / 2;
    if (y_height > SIZE_MAX / y_row || uv_height > SIZE_MAX / uv_row) return 0;
    size_t y_bytes = y_row * y_height;
    size_t uv_bytes = uv_row * uv_height;
    if (uv_bytes > (SIZE_MAX - y_bytes) / 2) return 0;
    *size = y_bytes + (uv_bytes * 2);
    return 1;
}

static void release_window(JNIEnv *env, Decoder *d) {
    if (d->window) ANativeWindow_release(d->window);
    if (d->surface) (*env)->DeleteGlobalRef(env, d->surface);
    d->window = NULL;
    d->surface = NULL;
    d->window_width = 0;
    d->window_height = 0;
    d->window_data_space = 0;
    d->window_data_space_attempted = 0;
}

static void destroy(Decoder *d) {
    if (d->sws) ff.sws_freeContext(d->sws);
    if (d->frame) ff.av_frame_free(&d->frame);
    if (d->packet) ff.av_packet_free(&d->packet);
    if (d->codec) ff.avcodec_free_context(&d->codec);
    free(d);
}

#define METHOD(name) Java_com_nuvio_app_features_player_swdecoder_SoftwareVideoDecoder_##name

JNIEXPORT jboolean JNICALL METHOD(nativeLoad)(JNIEnv *env, jclass clazz) {
    return load_ffmpeg() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL METHOD(nativeCreate)(JNIEnv *env, jclass clazz, jstring codec_name,
                                              jbyteArray extra_data, jint threads) {
    if (!load_ffmpeg()) return 0;
    const char *name = (*env)->GetStringUTFChars(env, codec_name, NULL);
    const AVCodec *codec = ff.avcodec_find_decoder_by_name(name);
    (*env)->ReleaseStringUTFChars(env, codec_name, name);
    if (!codec) return 0;

    Decoder *d = calloc(1, sizeof(Decoder));
    if (!d) return 0;
    d->codec = ff.avcodec_alloc_context3(codec);
    d->packet = ff.av_packet_alloc();
    d->frame = ff.av_frame_alloc();
    if (!d->codec || !d->packet || !d->frame) {
        destroy(d);
        return 0;
    }
    if (extra_data) {
        jsize size = (*env)->GetArrayLength(env, extra_data);
        d->codec->extradata = ff.av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!d->codec->extradata) {
            destroy(d);
            return 0;
        }
        (*env)->GetByteArrayRegion(env, extra_data, 0, size, (jbyte *) d->codec->extradata);
        d->codec->extradata_size = size;
    }
    // Java supplies AVPacket PTS values in Media3 microseconds.
    d->codec->pkt_timebase = (AVRational) {1, 1000000};
    d->codec->thread_count = threads;
    d->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    d->codec->flags2 |= AV_CODEC_FLAG2_FAST;
    int result = ff.avcodec_open2(d->codec, codec, NULL);
    if (result < 0) {
        LOGW("avcodec_open2 failed: %d", result);
        destroy(d);
        return 0;
    }
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL METHOD(nativeFlush)(JNIEnv *env, jclass clazz, jlong handle) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    ff.avcodec_flush_buffers(d->codec);
    ff.av_frame_unref(d->frame);
    d->has_frame = 0;
}

JNIEXPORT void JNICALL METHOD(nativeRelease)(JNIEnv *env, jclass clazz, jlong handle) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d) return;
    release_window(env, d);
    destroy(d);
}

JNIEXPORT jint JNICALL METHOD(nativeSendPacket)(JNIEnv *env, jclass clazz, jlong handle,
                                                 jobject data, jint size, jlong time_us) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    uint8_t *bytes = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!bytes || size < 0 || capacity < (jlong) size + AV_INPUT_BUFFER_PADDING_SIZE) {
        LOGW("Rejected packet buffer without FFmpeg padding (size=%d capacity=%lld)",
             size, (long long) capacity);
        return RESULT_ERROR;
    }
    if (d->has_frame) {
        LOGW("Refusing to send a packet while a decoded frame is still pending");
        return RESULT_ERROR;
    }
    d->packet->data = bytes;
    d->packet->size = size;
    d->packet->pts = time_us;
    int result = ff.avcodec_send_packet(d->codec, d->packet);
    d->packet->data = NULL;
    d->packet->size = 0;
    if (result == 0) return RESULT_OK;
    if (result == AVERROR(EAGAIN)) return RESULT_AGAIN;
    if (result == AVERROR_INVALIDDATA) return RESULT_SKIP;
    LOGW("avcodec_send_packet failed: %d", result);
    return RESULT_ERROR;
}

JNIEXPORT jint JNICALL METHOD(nativeSendEndOfStream)(JNIEnv *env, jclass clazz, jlong handle) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d || d->has_frame) return RESULT_ERROR;
    int result = ff.avcodec_send_packet(d->codec, NULL);
    if (result == 0) return RESULT_OK;
    if (result == AVERROR(EAGAIN)) return RESULT_AGAIN;
    if (result == AVERROR_EOF) return RESULT_EOF;
    LOGW("avcodec_send_packet(NULL) failed: %d", result);
    return RESULT_ERROR;
}

JNIEXPORT jlong JNICALL METHOD(nativeTakeFrame)(JNIEnv *env, jclass clazz, jlong handle,
                                                 jlong reusable_frame_handle) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    AVFrame *owned_frame = (AVFrame *) (intptr_t) reusable_frame_handle;
    if (!d || !d->has_frame) {
        if (owned_frame) ff.av_frame_free(&owned_frame);
        return 0;
    }
    if (owned_frame) {
        ff.av_frame_unref(owned_frame);
    } else {
        owned_frame = ff.av_frame_alloc();
    }
    if (!owned_frame) {
        LOGW("Failed to allocate native output frame owner");
        return 0;
    }
    int result = ff.av_frame_ref(owned_frame, d->frame);
    if (result < 0) {
        LOGW("Failed to retain decoded frame: %d", result);
        ff.av_frame_free(&owned_frame);
        return 0;
    }
    ff.av_frame_unref(d->frame);
    d->has_frame = 0;
    return (jlong) (intptr_t) owned_frame;
}

JNIEXPORT void JNICALL METHOD(nativeReleaseFrame)(JNIEnv *env, jclass clazz, jlong frame_handle) {
    AVFrame *frame = (AVFrame *) (intptr_t) frame_handle;
    if (frame) ff.av_frame_free(&frame);
}

JNIEXPORT void JNICALL METHOD(nativeDiscardFrame)(JNIEnv *env, jclass clazz, jlong handle) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d || !d->has_frame) return;
    ff.av_frame_unref(d->frame);
    d->has_frame = 0;
}

JNIEXPORT jint JNICALL METHOD(nativeReceiveFrame)(JNIEnv *env, jclass clazz, jlong handle,
                                                   jlongArray info) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d->has_frame) {
        int result = ff.avcodec_receive_frame(d->codec, d->frame);
        if (result == AVERROR(EAGAIN)) return RESULT_AGAIN;
        if (result == AVERROR_EOF) return RESULT_EOF;
        if (result < 0) {
            LOGW("avcodec_receive_frame failed: %d", result);
            return RESULT_ERROR;
        }
        d->has_frame = 1;
    }
    AVFrame *frame = d->frame;
    jlong values[6] = {
        frame->pts,
        frame->best_effort_timestamp,
        frame->width,
        frame->height,
        colorspace_of(frame),
        data_space_of(frame),
    };
    (*env)->SetLongArrayRegion(env, info, 0, 6, values);
    return RESULT_OK;
}

JNIEXPORT jboolean JNICALL METHOD(nativeConvertFrame)(JNIEnv *env, jclass clazz, jlong handle,
                                                       jobject output, jint y_stride, jint uv_stride) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d->has_frame) return JNI_FALSE;
    AVFrame *frame = d->frame;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, output);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, output);
    size_t required_size = 0;
    if (!base || capacity < 0 ||
        !yuv420_buffer_size(frame->width, frame->height, y_stride, uv_stride, &required_size) ||
        (uint64_t) capacity < (uint64_t) required_size) {
        LOGW("Rejected undersized or invalid YUV output buffer");
        return JNI_FALSE;
    }
    d->sws = ff.sws_getCachedContext(d->sws, frame->width, frame->height, frame->format,
                                     frame->width, frame->height, AV_PIX_FMT_YUV420P,
                                     SWS_BILINEAR, NULL, NULL, NULL);
    if (!d->sws) d->sws_colorspace_configured = 0;
    jboolean converted = JNI_FALSE;
    if (base && d->sws) {
        if (!configure_sws_colorspace(d, frame)) {
            LOGW("Failed to configure YUV conversion matrix/range (colorspace=%d full_range=%d)",
                 colorspace_of(frame), frame->color_range == AVCOL_RANGE_JPEG);
        } else {
            int uv_height = (frame->height + 1) / 2;
            uint8_t *planes[4] = {
                base,
                base + (size_t) y_stride * frame->height,
                base + (size_t) y_stride * frame->height + (size_t) uv_stride * uv_height,
                NULL,
            };
            int strides[4] = {y_stride, uv_stride, uv_stride, 0};
            converted = ff.sws_scale(d->sws, (const uint8_t *const *) frame->data, frame->linesize, 0,
                                     frame->height, planes, strides) > 0;
        }
    }
    ff.av_frame_unref(frame);
    d->has_frame = 0;
    return converted;
}

JNIEXPORT jint JNICALL METHOD(nativeRenderPrivateFrame)(JNIEnv *env, jclass clazz,
                                                       jlong handle, jlong frame_handle,
                                                       jobject surface) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    AVFrame *frame = (AVFrame *) (intptr_t) frame_handle;
    if (!d || !frame || !surface) return RESULT_ERROR;
    const int width = frame->width;
    const int height = frame->height;
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) {
        LOGW("YV12 surface output requires positive even dimensions (%dx%d)", width, height);
        return RESULT_ERROR;
    }
    if (!d->surface || !(*env)->IsSameObject(env, d->surface, surface)) {
        release_window(env, d);
        d->window = ANativeWindow_fromSurface(env, surface);
        if (!d->window) return RESULT_ERROR;
        d->surface = (*env)->NewGlobalRef(env, surface);
        if (!d->surface) {
            release_window(env, d);
            return RESULT_ERROR;
        }
    }
    if (d->window_width != width || d->window_height != height) {
        if (ANativeWindow_setBuffersGeometry(d->window, width, height, HAL_PIXEL_FORMAT_YV12)) {
            LOGW("Failed to configure YV12 surface geometry (%dx%d)", width, height);
            return RESULT_ERROR;
        }
        d->window_width = width;
        d->window_height = height;
    }

    const int32_t data_space = data_space_of(frame);
    if (ff.set_data_space && data_space && d->window_data_space != data_space &&
        d->window_data_space_attempted != data_space) {
        int status = ff.set_data_space(d->window, data_space);
        d->window_data_space_attempted = data_space;
        if (status == 0) d->window_data_space = data_space;
        else LOGW("ANativeWindow_setBuffersDataSpace failed: %d (dataspace=0x%08x)",
                  status, (unsigned int) data_space);
    }

    ANativeWindow_Buffer buffer;
    int lock_result = ANativeWindow_lock(d->window, &buffer, NULL);
    if (lock_result == -ENODEV) {
        release_window(env, d);
        return RESULT_OK;
    }
    if (lock_result != 0) {
        LOGW("ANativeWindow_lock failed: %d", lock_result);
        return RESULT_ERROR;
    }
    if (!buffer.bits || buffer.format != HAL_PIXEL_FORMAT_YV12 ||
        buffer.width < width || buffer.height < height ||
        (buffer.width & 1) || (buffer.height & 1) || buffer.stride < width ||
        (buffer.stride % 16) != 0 || buffer.stride > INT32_MAX - 31) {
        LOGW("Rejected native YV12 buffer (%dx%d stride=%d format=0x%x) for frame (%dx%d)",
             buffer.width, buffer.height, buffer.stride, buffer.format, width, height);
        int unlock_result = ANativeWindow_unlockAndPost(d->window);
        if (unlock_result) LOGW("unlock after invalid YV12 buffer failed: %d", unlock_result);
        return RESULT_ERROR;
    }

    const int chroma_stride = ALIGN(buffer.stride / 2, 16);
    const int chroma_columns = (width + 1) / 2;
    if (chroma_stride < chroma_columns) {
        int unlock_result = ANativeWindow_unlockAndPost(d->window);
        if (unlock_result) LOGW("unlock after invalid chroma stride failed: %d", unlock_result);
        return RESULT_ERROR;
    }
    if ((size_t) buffer.height > SIZE_MAX / (size_t) buffer.stride) {
        int unlock_result = ANativeWindow_unlockAndPost(d->window);
        if (unlock_result) LOGW("unlock after invalid Y plane size failed: %d", unlock_result);
        return RESULT_ERROR;
    }
    const size_t y_plane_size = (size_t) buffer.stride * (size_t) buffer.height;
    const size_t chroma_height = (size_t) ((buffer.height + 1) / 2);
    if (chroma_height > SIZE_MAX / (size_t) chroma_stride) {
        int unlock_result = ANativeWindow_unlockAndPost(d->window);
        if (unlock_result) LOGW("unlock after invalid chroma plane size failed: %d", unlock_result);
        return RESULT_ERROR;
    }
    const size_t chroma_plane_size = (size_t) chroma_stride * chroma_height;
    if (chroma_plane_size > (SIZE_MAX - y_plane_size) / 2) {
        int unlock_result = ANativeWindow_unlockAndPost(d->window);
        if (unlock_result) LOGW("unlock after YV12 plane offset overflow failed: %d", unlock_result);
        return RESULT_ERROR;
    }

    d->sws = ff.sws_getCachedContext(d->sws, width, height, frame->format,
                                     width, height, AV_PIX_FMT_YUV420P,
                                     SWS_BILINEAR, NULL, NULL, NULL);
    if (!d->sws) {
        d->sws_colorspace_configured = 0;
        LOGW("Failed to create FFmpeg YUV420 conversion context");
        int unlock_result = ANativeWindow_unlockAndPost(d->window);
        if (unlock_result) LOGW("unlock after swscale setup failure failed: %d", unlock_result);
        return RESULT_ERROR;
    }
    if (!configure_sws_colorspace(d, frame)) {
        LOGW("Failed to configure YUV conversion matrix/range (colorspace=%d full_range=%d)",
             colorspace_of(frame), frame->color_range == AVCOL_RANGE_JPEG);
        int unlock_result = ANativeWindow_unlockAndPost(d->window);
        if (unlock_result) LOGW("unlock after colorspace setup failure failed: %d", unlock_result);
        return RESULT_ERROR;
    }

    // ANativeWindow YV12 layout is Y, V, U; libswscale YUV420P is Y, U, V.
    // Reverse the chroma destinations and honor the actual locked-buffer strides.
    uint8_t *base = (uint8_t *) buffer.bits;
    uint8_t *dst_v = base + y_plane_size;
    uint8_t *dst_u = dst_v + chroma_plane_size;
    uint8_t *planes[4] = {base, dst_u, dst_v, NULL};
    int strides[4] = {buffer.stride, chroma_stride, chroma_stride, 0};
    const int converted_rows = ff.sws_scale(
        d->sws, (const uint8_t *const *) frame->data, frame->linesize,
        0, height, planes, strides);
    int unlock_result = ANativeWindow_unlockAndPost(d->window);
    if (converted_rows != height) {
        LOGW("FFmpeg converted %d/%d rows directly to YV12 surface", converted_rows, height);
        return RESULT_ERROR;
    }
    if (unlock_result != 0) {
        LOGW("ANativeWindow_unlockAndPost failed: %d", unlock_result);
        return RESULT_ERROR;
    }
    return RESULT_OK;
}
