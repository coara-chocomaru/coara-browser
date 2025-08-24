LOCAL_PATH := $(call my-dir)

CRYPTO_TYPE := none
ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libcrypto.so),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := crypto
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libcrypto.so
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include/openssl
    include $(PREBUILT_SHARED_LIBRARY)
    CRYPTO_TYPE := shared
else ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libcrypto.a),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := crypto
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libcrypto.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include/openssl
    include $(PREBUILT_STATIC_LIBRARY)
    CRYPTO_TYPE := static
else
    $(warning [Android.mk] WARNING: crypto (libcrypto.so / libcrypto.a) not found for $(TARGET_ARCH_ABI))
endif

PNG_TYPE := none
ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libpng.so),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := png
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libpng.so
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include/png
    include $(PREBUILT_SHARED_LIBRARY)
    PNG_TYPE := shared
else ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libpng.a),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := png
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libpng.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include/png
    include $(PREBUILT_STATIC_LIBRARY)
    PNG_TYPE := static
else
    $(warning [Android.mk] WARNING: png (libpng.so / libpng.a) not found for $(TARGET_ARCH_ABI))
endif

include $(CLEAR_VARS)
LOCAL_MODULE := browseropt
LOCAL_SRC_FILES := browseropt.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include \
                    $(LOCAL_PATH)/include/openssl \
                    $(LOCAL_PATH)/include/png

LOCAL_CPPFLAGS := -std=c++11 -fexceptions -frtti -O3
LOCAL_CFLAGS := -std=c11 -fexceptions -frtti -O3

LOCAL_LDLIBS := -llog -landroid -lz


ifeq ($(CRYPTO_TYPE),shared)
    LOCAL_SHARED_LIBRARIES += crypto
else ifeq ($(CRYPTO_TYPE),static)
    LOCAL_STATIC_LIBRARIES += crypto
endif

ifeq ($(PNG_TYPE),shared)
    LOCAL_SHARED_LIBRARIES += png
else ifeq ($(PNG_TYPE),static)
    LOCAL_STATIC_LIBRARIES += png
endif

include $(BUILD_SHARED_LIBRARY)
