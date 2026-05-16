#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "MiniCPMV-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model * g_model = nullptr;
static llama_context * g_lctx = nullptr;
static mtmd_context * g_mtmd_ctx = nullptr;
static llama_sampler * g_smpl = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_manga_translate_LocalVlmClient_initModel(JNIEnv *env, jobject thiz, jstring model_path, jstring mmproj_path, jint num_threads) {
    if (g_model || g_mtmd_ctx) {
        LOGI("Model already initialized");
        return JNI_TRUE;
    }

    const char * c_model_path = env->GetStringUTFChars(model_path, nullptr);
    const char * c_mmproj_path = env->GetStringUTFChars(mmproj_path, nullptr);

    LOGI("Loading text model from %s", c_model_path);
    
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 99; // offload to GPU/Vulkan if possible

    g_model = llama_model_load_from_file(c_model_path, model_params);
    if (!g_model) {
        LOGE("Failed to load text model");
        env->ReleaseStringUTFChars(model_path, c_model_path);
        env->ReleaseStringUTFChars(mmproj_path, c_mmproj_path);
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096; // Adjust based on model
    ctx_params.n_threads = num_threads;
    ctx_params.n_threads_batch = num_threads;
    ctx_params.flash_attn = true;

    g_lctx = llama_init_from_model(g_model, ctx_params);
    if (!g_lctx) {
        LOGE("Failed to initialize llama context");
        return JNI_FALSE;
    }

    LOGI("Loading vision model from %s", c_mmproj_path);
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = true;
    mtmd_params.n_threads = num_threads;

    g_mtmd_ctx = mtmd_init_from_file(c_mmproj_path, g_model, mtmd_params);
    if (!g_mtmd_ctx) {
        LOGE("Failed to initialize mtmd context");
        return JNI_FALSE;
    }

    // Initialize sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(0.7f));

    env->ReleaseStringUTFChars(model_path, c_model_path);
    env->ReleaseStringUTFChars(mmproj_path, c_mmproj_path);
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_manga_translate_LocalVlmClient_freeModel(JNIEnv *env, jobject thiz) {
    if (g_smpl) {
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
    }
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    if (g_lctx) {
        llama_free(g_lctx);
        g_lctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
    LOGI("Model freed successfully");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_manga_translate_LocalVlmClient_processImage(JNIEnv *env, jobject thiz, jbyteArray image_bytes, jstring prompt) {
    if (!g_model || !g_mtmd_ctx || !g_lctx) {
        LOGE("Model not initialized");
        return env->NewStringUTF("");
    }

    const char * c_prompt = env->GetStringUTFChars(prompt, nullptr);
    jsize img_len = env->GetArrayLength(image_bytes);
    jbyte * img_data = env->GetByteArrayElements(image_bytes, nullptr);

    // 1. Parse Image
    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(g_mtmd_ctx, reinterpret_cast<const unsigned char *>(img_data), img_len);
    if (!bitmap) {
        LOGE("Failed to decode image buffer");
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    // 2. Tokenize prompt & image
    mtmd_input_text input_text = { c_prompt, true, true };
    const mtmd_bitmap * bitmaps[] = { bitmap };
    
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int32_t tok_res = mtmd_tokenize(g_mtmd_ctx, chunks, &input_text, bitmaps, 1);
    
    if (tok_res != 0) {
        LOGE("mtmd_tokenize failed with code %d", tok_res);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    // 3. Evaluate chunks (encode image & decode text)
    llama_kv_cache_clear(g_lctx);
    llama_pos n_past = 0;
    
    int32_t eval_res = mtmd_helper_eval_chunks(g_mtmd_ctx, g_lctx, chunks, n_past, 0, 2048, true, &n_past);
    if (eval_res != 0) {
        LOGE("mtmd_helper_eval_chunks failed with code %d", eval_res);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    // 4. Generate Text Loop
    std::string response = "";
    int max_tokens = 1024; // TODO: configurable
    
    for (int i = 0; i < max_tokens; i++) {
        llama_token id = llama_sampler_sample(g_smpl, g_lctx, -1);
        llama_sampler_accept(g_smpl, id);

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id)) {
            break;
        }

        char buf[128];
        int n = llama_vocab_detokenize(llama_model_get_vocab(g_model), id, buf, sizeof(buf), true);
        if (n > 0) {
            response += std::string(buf, n);
        }

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_lctx, batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
    }

    // Cleanup
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
    env->ReleaseStringUTFChars(prompt, c_prompt);

    return env->NewStringUTF(response.c_str());
}
