// JNI bridge exposing RPiPlay's reverse-engineered FairPlay key decryption to Kotlin.
// Only playfair_decrypt is wrapped; fp-setup/handshake are handled in Kotlin (FairPlay.kt).
#include <jni.h>
#include "playfair/playfair.h"

// playfair_decrypt reads fixed offsets from the 164-byte phase-2 key message and the
// 72-byte FairPlay-wrapped key (ekey), writing a 16-byte AES key. Validate sizes and
// allocation results so malformed input from a peer yields null (a clean Kotlin-side
// failure) instead of an out-of-bounds native read / crash.
#define KEY_MESSAGE_LEN 164
#define CIPHER_LEN 72
#define OUTPUT_LEN 16

JNIEXPORT jbyteArray JNICALL
Java_com_weekd_miracastreceiver_airplay_handshake_FairPlay_nativePlayfairDecrypt(
        JNIEnv* env, jobject thiz, jbyteArray keyMessage, jbyteArray cipher) {
    (void) thiz;
    if (keyMessage == NULL || cipher == NULL) {
        return NULL;
    }
    if ((*env)->GetArrayLength(env, keyMessage) < KEY_MESSAGE_LEN ||
        (*env)->GetArrayLength(env, cipher) < CIPHER_LEN) {
        return NULL;
    }

    jbyte* km = (*env)->GetByteArrayElements(env, keyMessage, NULL);
    jbyte* ct = (*env)->GetByteArrayElements(env, cipher, NULL);
    if (km == NULL || ct == NULL) {
        if (km != NULL) (*env)->ReleaseByteArrayElements(env, keyMessage, km, JNI_ABORT);
        if (ct != NULL) (*env)->ReleaseByteArrayElements(env, cipher, ct, JNI_ABORT);
        return NULL;
    }

    unsigned char out[OUTPUT_LEN];
    playfair_decrypt((unsigned char*) km, (unsigned char*) ct, out);

    (*env)->ReleaseByteArrayElements(env, keyMessage, km, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, cipher, ct, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, OUTPUT_LEN);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, OUTPUT_LEN, (jbyte*) out);
    }
    return result;
}
