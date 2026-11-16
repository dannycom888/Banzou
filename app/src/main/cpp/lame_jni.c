/* JNI bridge to libmp3lame for the Banzou app (分轨伴奏).
 * One global encoder instance; the app's processing queue is sequential.
 */
#include <jni.h>
#include <stdlib.h>
#include "lame.h"

static lame_t g_lame = NULL;

JNIEXPORT jboolean JNICALL
Java_com_dannycom888_banzou_audio_LameNative_initEncoder(
        JNIEnv *env, jobject thiz, jint channels, jint sampleRate, jint bitRateKbps) {
    (void) env; (void) thiz;
    if (g_lame != NULL) {
        lame_close(g_lame);
        g_lame = NULL;
    }
    g_lame = lame_init();
    if (g_lame == NULL) return JNI_FALSE;
    lame_set_num_channels(g_lame, channels);
    lame_set_in_samplerate(g_lame, sampleRate);
    lame_set_out_samplerate(g_lame, sampleRate);
    lame_set_brate(g_lame, bitRateKbps);
    lame_set_quality(g_lame, 2);
    lame_set_bWriteVbrTag(g_lame, 0);
    if (lame_init_params(g_lame) != 0) {
        lame_close(g_lame);
        g_lame = NULL;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_dannycom888_banzou_audio_LameNative_encodeInterleaved(
        JNIEnv *env, jobject thiz, jshortArray pcm, jint samplesPerChannel) {
    (void) thiz;
    if (g_lame == NULL || samplesPerChannel <= 0) return NULL;
    /* LAME docs: mp3 buffer must be >= 1.25 * num_samples + 7200 bytes. */
    int outCapacity = (int) (1.25 * samplesPerChannel * 2 + 7200);
    unsigned char *out = (unsigned char *) malloc(outCapacity);
    if (out == NULL) return NULL;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (samples == NULL) {
        free(out);
        return NULL;
    }
    int written = lame_encode_buffer_interleaved(
            g_lame, samples, samplesPerChannel, out, outCapacity);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    if (written < 0) {
        free(out);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, written);
    if (result != NULL && written > 0) {
        (*env)->SetByteArrayRegion(env, result, 0, written, (const jbyte *) out);
    }
    free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_dannycom888_banzou_audio_LameNative_flushEncoder(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    if (g_lame == NULL) return NULL;
    int outCapacity = 7200;
    unsigned char *out = (unsigned char *) malloc(outCapacity);
    if (out == NULL) return NULL;
    int written = lame_encode_flush(g_lame, out, outCapacity);
    if (written < 0) written = 0;
    jbyteArray result = (*env)->NewByteArray(env, written);
    if (result != NULL && written > 0) {
        (*env)->SetByteArrayRegion(env, result, 0, written, (const jbyte *) out);
    }
    free(out);
    return result;
}

JNIEXPORT void JNICALL
Java_com_dannycom888_banzou_audio_LameNative_closeEncoder(
        JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_lame != NULL) {
        lame_close(g_lame);
        g_lame = NULL;
    }
}
