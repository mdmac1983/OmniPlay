// Media Hub — software decoding with FFmpeg (LGPL build: decoders only) for what the phone's hardware can't play:
// 10-bit H.264 ("Hi10P") and HEVC, MPEG-2 / MPEG-4 video, and AC-3, E-AC-3, DTS, TrueHD and ALAC audio.
// Video frames are converted to 8-bit YUV 4:2:0 and copied straight into the screen's surface (YV12); audio is
// returned as interleaved 32-bit float. The Kotlin side is FfVideoDecoder / FfAudioDecoder.
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/native_window_jni.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>

#define FN(cls, name) Java_app_orionmd_ffmpeg_##cls##_##name
#define ALIGN(x, a) (((x) + (a) - 1) & ~((a) - 1))
#define HAL_PIXEL_FORMAT_YV12 0x32315659

static AVCodecContext *open_codec(JNIEnv *env, jstring name, jbyteArray extra, int threads) {
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const AVCodec *codec = avcodec_find_decoder_by_name(n);
    (*env)->ReleaseStringUTFChars(env, name, n);
    if (!codec) return NULL;
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) return NULL;
    if (extra) {
        jsize len = (*env)->GetArrayLength(env, extra);
        if (len > 0) {
            ctx->extradata = av_mallocz(len + AV_INPUT_BUFFER_PADDING_SIZE);
            (*env)->GetByteArrayRegion(env, extra, 0, len, (jbyte *) ctx->extradata);
            ctx->extradata_size = len;
        }
    }
    if (threads > 0) { ctx->thread_count = threads; ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE; }
    ctx->pkt_timebase = (AVRational) {1, 1000000};
    return ctx;
}

// ============================================================================ video

typedef struct {
    AVCodecContext *ctx;
    AVPacket *pkt;
    AVFrame *frame;
    int have;                 // a decoded frame is waiting to be copied
    struct SwsContext *sws;
    int sw, sh, sfmt;
    ANativeWindow *win; jobject surf; int ww, wh;
} V;

JNIEXPORT jlong JNICALL FN(FfVideoDecoder, nInit)(JNIEnv *env, jobject thiz, jstring name, jbyteArray extra, jint threads) {
    AVCodecContext *ctx = open_codec(env, name, extra, threads);
    if (!ctx) return 0;
    if (avcodec_open2(ctx, ctx->codec, NULL) < 0) { avcodec_free_context(&ctx); return 0; }
    V *v = calloc(1, sizeof(V));
    v->ctx = ctx; v->pkt = av_packet_alloc(); v->frame = av_frame_alloc();
    return (jlong) (intptr_t) v;
}

/** Sends one access unit; returns 0, or a negative FFmpeg error. Pass size 0 to drain. */
JNIEXPORT jint JNICALL FN(FfVideoDecoder, nSend)(JNIEnv *env, jobject thiz, jlong h, jobject buf, jint size, jlong timeUs) {
    V *v = (V *) (intptr_t) h;
    if (size > 0) {
        v->pkt->data = (*env)->GetDirectBufferAddress(env, buf);
        v->pkt->size = size;
        v->pkt->pts = timeUs;
        int r = avcodec_send_packet(v->ctx, v->pkt);
        v->pkt->data = NULL; v->pkt->size = 0;
        return r == AVERROR(EAGAIN) ? 0 : r;
    }
    return avcodec_send_packet(v->ctx, NULL);
}

/** Pulls a decoded frame. Returns 1 and fills info[] = {width, height, ptsHigh, ptsLow, colorspace}, 0 when none yet, <0 on error. */
JNIEXPORT jint JNICALL FN(FfVideoDecoder, nReceive)(JNIEnv *env, jobject thiz, jlong h, jintArray info) {
    V *v = (V *) (intptr_t) h;
    int r = avcodec_receive_frame(v->ctx, v->frame);
    if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) return 0;
    if (r < 0) return r;
    v->have = 1;
    int64_t pts = v->frame->best_effort_timestamp != AV_NOPTS_VALUE ? v->frame->best_effort_timestamp : v->frame->pts;
    int cs = 0;
    switch (v->frame->colorspace) { case AVCOL_SPC_BT709: cs = 2; break; case AVCOL_SPC_BT2020_NCL: case AVCOL_SPC_BT2020_CL: cs = 3; break; case AVCOL_SPC_BT470BG: case AVCOL_SPC_SMPTE170M: cs = 1; break; default: cs = 0; }
    jint out[5] = { v->frame->width, v->frame->height, (jint) (pts >> 32), (jint) (pts & 0xffffffff), cs };
    (*env)->SetIntArrayRegion(env, info, 0, 5, out);
    return 1;
}

/** Copies the waiting frame as 8-bit YUV 4:2:0 into [dst] (Y plane then U then V, with the given strides). */
JNIEXPORT jint JNICALL FN(FfVideoDecoder, nCopy)(JNIEnv *env, jobject thiz, jlong h, jobject dst, jint yStride, jint uvStride) {
    V *v = (V *) (intptr_t) h;
    if (!v->have) return -1;
    AVFrame *f = v->frame;
    uint8_t *d = (*env)->GetDirectBufferAddress(env, dst);
    int w = f->width, hh = f->height, cw = (w + 1) / 2, ch = (hh + 1) / 2;
    uint8_t *planes[3] = { d, d + (size_t) yStride * hh, d + (size_t) yStride * hh + (size_t) uvStride * ch };
    int strides[3] = { yStride, uvStride, uvStride };
    if (f->format == AV_PIX_FMT_YUV420P || f->format == AV_PIX_FMT_YUVJ420P) {
        for (int y = 0; y < hh; y++) memcpy(planes[0] + (size_t) y * yStride, f->data[0] + (size_t) y * f->linesize[0], w);
        for (int y = 0; y < ch; y++) {
            memcpy(planes[1] + (size_t) y * uvStride, f->data[1] + (size_t) y * f->linesize[1], cw);
            memcpy(planes[2] + (size_t) y * uvStride, f->data[2] + (size_t) y * f->linesize[2], cw);
        }
    } else {
        // 10-bit and other formats → 8-bit 4:2:0
        if (!v->sws || v->sw != w || v->sh != hh || v->sfmt != f->format) {
            sws_freeContext(v->sws);
            v->sws = sws_getContext(w, hh, f->format, w, hh, AV_PIX_FMT_YUV420P, SWS_FAST_BILINEAR, NULL, NULL, NULL);
            v->sw = w; v->sh = hh; v->sfmt = f->format;
            if (!v->sws) return -2;
        }
        sws_scale(v->sws, (const uint8_t *const *) f->data, f->linesize, 0, hh, planes, strides);
    }
    av_frame_unref(f);
    v->have = 0;
    return 0;
}

JNIEXPORT void JNICALL FN(FfVideoDecoder, nFlush)(JNIEnv *env, jobject thiz, jlong h) {
    V *v = (V *) (intptr_t) h;
    avcodec_flush_buffers(v->ctx);
    if (v->have) { av_frame_unref(v->frame); v->have = 0; }
}

JNIEXPORT void JNICALL FN(FfVideoDecoder, nRelease)(JNIEnv *env, jobject thiz, jlong h) {
    V *v = (V *) (intptr_t) h;
    if (!v) return;
    avcodec_free_context(&v->ctx);
    av_packet_free(&v->pkt);
    av_frame_free(&v->frame);
    sws_freeContext(v->sws);
    if (v->win) ANativeWindow_release(v->win);
    if (v->surf) (*env)->DeleteGlobalRef(env, v->surf);
    free(v);
}

/** Draws a decoded 8-bit YUV 4:2:0 frame into [surface] as YV12. Returns 0 on success. */
JNIEXPORT jint JNICALL FN(FfVideoDecoder, nRender)(JNIEnv *env, jobject thiz, jlong h, jobject surface, jobject data,
                                                    jint w, jint hh, jint yStride, jint uvStride) {
    V *v = (V *) (intptr_t) h;
    if (!v->surf || !(*env)->IsSameObject(env, v->surf, surface)) {
        if (v->win) ANativeWindow_release(v->win);
        if (v->surf) (*env)->DeleteGlobalRef(env, v->surf);
        v->surf = (*env)->NewGlobalRef(env, surface);
        v->win = ANativeWindow_fromSurface(env, surface);
        v->ww = 0; v->wh = 0;
    }
    if (!v->win) return -1;
    if (v->ww != w || v->wh != hh) {
        if (ANativeWindow_setBuffersGeometry(v->win, w, hh, HAL_PIXEL_FORMAT_YV12)) return -2;
        v->ww = w; v->wh = hh;
    }
    ANativeWindow_Buffer b;
    if (ANativeWindow_lock(v->win, &b, NULL)) return -3;
    const uint8_t *src = (*env)->GetDirectBufferAddress(env, data);
    int ch = (hh + 1) / 2, cw = (w + 1) / 2;
    uint8_t *dy = b.bits;
    int dyStride = b.stride;
    int dcStride = ALIGN(b.stride / 2, 16);
    uint8_t *dv = dy + (size_t) dyStride * b.height;      // YV12: Y, then V (Cr), then U (Cb)
    uint8_t *du = dv + (size_t) dcStride * (b.height / 2);
    const uint8_t *sy = src, *su = src + (size_t) yStride * hh, *sv = su + (size_t) uvStride * ch;
    int rows = hh < b.height ? hh : b.height, cols = w < b.width ? w : b.width;
    for (int y = 0; y < rows; y++) memcpy(dy + (size_t) y * dyStride, sy + (size_t) y * yStride, cols);
    int crows = (rows + 1) / 2, ccols = (cols + 1) / 2;
    if (ccols > cw) ccols = cw;
    for (int y = 0; y < crows && y < ch; y++) {
        memcpy(du + (size_t) y * dcStride, su + (size_t) y * uvStride, ccols);
        memcpy(dv + (size_t) y * dcStride, sv + (size_t) y * uvStride, ccols);
    }
    ANativeWindow_unlockAndPost(v->win);
    return 0;
}

// ============================================================================ audio

typedef struct {
    AVCodecContext *ctx;
    AVPacket *pkt;
    AVFrame *frame;
    SwrContext *swr;
    int ch, rate, inFmt;
} A;

JNIEXPORT jlong JNICALL FN(FfAudioDecoder, nInit)(JNIEnv *env, jobject thiz, jstring name, jbyteArray extra, jint rate, jint channels) {
    AVCodecContext *ctx = open_codec(env, name, extra, 0);
    if (!ctx) return 0;
    if (rate > 0) ctx->sample_rate = rate;
    if (channels > 0) av_channel_layout_default(&ctx->ch_layout, channels);
    ctx->request_sample_fmt = AV_SAMPLE_FMT_FLT;
    if (avcodec_open2(ctx, ctx->codec, NULL) < 0) { avcodec_free_context(&ctx); return 0; }
    A *a = calloc(1, sizeof(A));
    a->ctx = ctx; a->pkt = av_packet_alloc(); a->frame = av_frame_alloc();
    return (jlong) (intptr_t) a;
}

/** Decodes one packet; writes interleaved float into [out]. Returns bytes written, or <0 on error (-1000 = output too small). */
JNIEXPORT jint JNICALL FN(FfAudioDecoder, nDecode)(JNIEnv *env, jobject thiz, jlong h, jobject in, jint size, jobject out, jint cap) {
    A *a = (A *) (intptr_t) h;
    a->pkt->data = (*env)->GetDirectBufferAddress(env, in);
    a->pkt->size = size;
    int r = avcodec_send_packet(a->ctx, a->pkt);
    a->pkt->data = NULL; a->pkt->size = 0;
    if (r < 0 && r != AVERROR(EAGAIN)) return r;
    uint8_t *o = (*env)->GetDirectBufferAddress(env, out);
    int written = 0;
    while ((r = avcodec_receive_frame(a->ctx, a->frame)) == 0) {
        AVFrame *f = a->frame;
        int ch = f->ch_layout.nb_channels;
        if (!a->swr || a->ch != ch || a->rate != f->sample_rate || a->inFmt != f->format) {
            swr_free(&a->swr);
            AVChannelLayout lay; av_channel_layout_copy(&lay, &f->ch_layout);
            if (lay.order == AV_CHANNEL_ORDER_UNSPEC) av_channel_layout_default(&lay, ch);
            swr_alloc_set_opts2(&a->swr, &lay, AV_SAMPLE_FMT_FLT, f->sample_rate, &lay, f->format, f->sample_rate, 0, NULL);
            av_channel_layout_uninit(&lay);
            if (!a->swr || swr_init(a->swr) < 0) { av_frame_unref(f); return -2; }
            a->ch = ch; a->rate = f->sample_rate; a->inFmt = f->format;
        }
        int bytes = f->nb_samples * ch * 4;
        if (written + bytes > cap) { av_frame_unref(f); return -1000; }
        uint8_t *dst = o + written;
        int n = swr_convert(a->swr, &dst, f->nb_samples, (const uint8_t **) f->extended_data, f->nb_samples);
        av_frame_unref(f);
        if (n < 0) return n;
        written += n * ch * 4;
    }
    if (r != AVERROR(EAGAIN) && r != AVERROR_EOF && r < 0) return r;
    return written;
}

/** {channels, sampleRate} of what's being decoded (known after the first frame; falls back to the codec's). */
JNIEXPORT void JNICALL FN(FfAudioDecoder, nInfo)(JNIEnv *env, jobject thiz, jlong h, jintArray info) {
    A *a = (A *) (intptr_t) h;
    jint v[2] = { a->ch ? a->ch : a->ctx->ch_layout.nb_channels, a->rate ? a->rate : a->ctx->sample_rate };
    (*env)->SetIntArrayRegion(env, info, 0, 2, v);
}

JNIEXPORT void JNICALL FN(FfAudioDecoder, nFlush)(JNIEnv *env, jobject thiz, jlong h) { avcodec_flush_buffers(((A *) (intptr_t) h)->ctx); }

JNIEXPORT void JNICALL FN(FfAudioDecoder, nRelease)(JNIEnv *env, jobject thiz, jlong h) {
    A *a = (A *) (intptr_t) h;
    if (!a) return;
    avcodec_free_context(&a->ctx); av_packet_free(&a->pkt); av_frame_free(&a->frame); swr_free(&a->swr);
    free(a);
}

JNIEXPORT jstring JNICALL FN(FfLib, nVersion)(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, av_version_info());
}
