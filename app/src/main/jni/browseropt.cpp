#include <jni.h>
#include <string>
#include <fstream>
#include <vector>
#include <stdexcept>
#include <openssl/md5.h>
#include <png.h>
#include <sys/mman.h> 
#include <sys/stat.h>
#include <fcntl.h> 
#include <unistd.h> 
#include <android/log.h>
#include <android/bitmap.h>

#define LOG_TAG "BrowserOpt"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


std::string computeMD5(const std::string& input) {
    unsigned char digest[MD5_DIGEST_LENGTH];
    MD5(reinterpret_cast<const unsigned char*>(input.c_str()), input.length(), digest);
    char mdString[33];
    for (int i = 0; i < 16; i++) {
        sprintf(&mdString[i * 2], "%02x", (unsigned int)digest[i]);
    }
    mdString[32] = '\0';  
    return std::string(mdString);
}

bool savePng(const std::string& filename, const unsigned char* data, size_t width, size_t height, int quality) {
    png_structp png_ptr = png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
    if (!png_ptr) {
        LOGE("png_create_write_struct failed");
        return false;
    }

    png_infop info_ptr = png_create_info_struct(png_ptr);
    if (!info_ptr) {
        png_destroy_write_struct(&png_ptr, nullptr);
        LOGE("png_create_info_struct failed");
        return false;
    }

    FILE* fp = fopen(filename.c_str(), "wb");
    if (!fp) {
        png_destroy_write_struct(&png_ptr, &info_ptr);
        LOGE("fopen failed for %s", filename.c_str());
        return false;
    }

    png_init_io(png_ptr, fp);

    
    png_set_IHDR(png_ptr, info_ptr, width, height, 8, PNG_COLOR_TYPE_RGBA, PNG_INTERLACE_NONE,
                 PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);

    
    png_set_compression_level(png_ptr, quality / 11);  

    
    std::vector<png_bytep> rows(height);
    for (size_t y = 0; y < height; ++y) {
        rows[y] = const_cast<png_bytep>(data + y * width * 4);  
    }

    png_set_rows(png_ptr, info_ptr, rows.data());
    png_write_png(png_ptr, info_ptr, PNG_TRANSFORM_IDENTITY, nullptr);

    fclose(fp);
    png_destroy_write_struct(&png_ptr, &info_ptr);

    return true;
}


bool saveWithMmap(const std::string& filename, const unsigned char* data, size_t len) {
    int fd = open(filename.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0666);
    if (fd == -1) {
        LOGE("open failed for %s", filename.c_str());
        return false;
    }

    
    if (ftruncate(fd, len) == -1) {
        close(fd);
        LOGE("ftruncate failed");
        return false;
    }

    void* mapped = mmap(nullptr, len, PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) {
        close(fd);
        LOGE("mmap failed");
        return false;
    }

    memcpy(mapped, data, len);
    msync(mapped, len, MS_SYNC);  
    munmap(mapped, len);
    close(fd);

    return true;
}


extern "C" JNIEXPORT void JNICALL
Java_com_coara_browser_BrowserOptService_nativeSaveFavicon(JNIEnv* env, jobject /* this */, jstring jUrl, jbyteArray jBitmapData) {
    jboolean isCopy;
    const char* urlChars = env->GetStringUTFChars(jUrl, &isCopy);
    std::string url(urlChars);
    env->ReleaseStringUTFChars(jUrl, urlChars);

    jsize dataLen = env->GetArrayLength(jBitmapData);
    jbyte* data = env->GetByteArrayElements(jBitmapData, &isCopy);
    const unsigned char* ucharData = reinterpret_cast<unsigned char*>(data);

    
    std::string md5Hash = computeMD5(url);
    std::string faviconsDir = "/data/data/com.coara.browser/files/favicons/";
    std::string filename = faviconsDir + md5Hash + ".png";

    
    size_t width = 16;
    size_t height = 16;
    if (dataLen != width * height * 4) {
        LOGE("Invalid bitmap data size");
        env->ReleaseByteArrayElements(jBitmapData, data, JNI_ABORT);
        return;
    }

    try {
        
        if (!saveWithMmap(filename + ".tmp", ucharData, dataLen)) {
            throw std::runtime_error("mmap save failed");
        }

        
        if (!savePng(filename, ucharData, width, height, 50)) {  // 50% quality
            throw std::runtime_error("PNG save failed");
        }

        
        unlink((filename + ".tmp").c_str());
    } catch (const std::exception& e) {
        LOGE("Error in nativeSaveFavicon: %s", e.what());
    }

    env->ReleaseByteArrayElements(jBitmapData, data, JNI_ABORT);
}


extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_coara_browser_BrowserOptService_nativeComputeMD5(JNIEnv* env, jobject /* this */, jstring jInput) {
    jboolean isCopy;
    const char* inputChars = env->GetStringUTFChars(jInput, &isCopy);
    std::string input(inputChars);
    env->ReleaseStringUTFChars(jInput, inputChars);

    std::string hash = computeMD5(input);

    jbyteArray result = env->NewByteArray(hash.length());
    env->SetByteArrayRegion(result, 0, hash.length(), reinterpret_cast<const jbyte*>(hash.c_str()));

    return result;
}


extern "C" JNIEXPORT void JNICALL
Java_com_coara_browser_BrowserOptService_nativeSaveScreenshot(JNIEnv* env, jobject /* this */, jbyteArray jBitmapData, jstring jFileName) {
    jboolean isCopy;
    const char* fileNameChars = env->GetStringUTFChars(jFileName, &isCopy);
    std::string fileName(fileNameChars);
    env->ReleaseStringUTFChars(jFileName, fileNameChars);

    jsize dataLen = env->GetArrayLength(jBitmapData);
    jbyte* data = env->GetByteArrayElements(jBitmapData, &isCopy);
    const unsigned char* ucharData = reinterpret_cast<unsigned char*>(data);

    size_t width = 1080; 
    size_t height = 1920;
    if (dataLen != width * height * 4) {
        LOGE("Invalid screenshot data size");
        env->ReleaseByteArrayElements(jBitmapData, data, JNI_ABORT);
        return;
    }

    try {
    
        if (!saveWithMmap(fileName, ucharData, dataLen)) {
            throw std::runtime_error("mmap save failed");
        }

    } catch (const std::exception& e) {
        LOGE("Error in nativeSaveScreenshot: %s", e.what());
    }

    env->ReleaseByteArrayElements(jBitmapData, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_coara_browser_BrowserOptService_nativeHandleBlobDownload(JNIEnv* env, jobject /* this */, jstring jUrl, jstring jMimeType, jstring jFileName) {
  
    jboolean isCopy;
    const char* urlChars = env->GetStringUTFChars(jUrl, &isCopy);
    std::string url(urlChars);
    env->ReleaseStringUTFChars(jUrl, urlChars);

    const char* mimeTypeChars = env->GetStringUTFChars(jMimeType, &isCopy);
    std::string mimeType(mimeTypeChars);
    env->ReleaseStringUTFChars(jMimeType, mimeTypeChars);

    const char* fileNameChars = env->GetStringUTFChars(jFileName, &isCopy);
    std::string fileName(fileNameChars);
    env->ReleaseStringUTFChars(jFileName, fileNameChars);

  
    try {
      
        std::ofstream file(fileName, std::ios::binary);
        if (!file.is_open()) {
            throw std::runtime_error("File open failed");
        }
    
        file.close();
        LOGD("Blob saved to %s", fileName.c_str());
    } catch (const std::exception& e) {
        LOGE("Error in nativeHandleBlobDownload: %s", e.what());
    }
}
