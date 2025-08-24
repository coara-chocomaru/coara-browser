LOCAL_PATH := $(call my-dir)


ERROR_ON_MISSING := true


CRYPTO_TYPE := none
PNG_TYPE := none
Z_TYPE := none


ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libcrypto.so),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := crypto
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libcrypto.so
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_SHARED_LIBRARY)
    CRYPTO_TYPE := shared
else ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libcrypto.a),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := crypto
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libcrypto.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_STATIC_LIBRARY)
    CRYPTO_TYPE := static
else
    ifeq ($(ERROR_ON_MISSING),true)
        $(error [Android.mk] ERROR: crypto (libcrypto.so / libcrypto.a) not found for $(TARGET_ARCH_ABI))
    else
        $(warning [Android.mk] WARNING: crypto (libcrypto.so / libcrypto.a) not found for $(TARGET_ARCH_ABI))
    endif
endif


ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libssl.a),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := ssl_static
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libssl.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_STATIC_LIBRARY)
    SSL_TYPE := static
else ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libssl.so),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := ssl
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libssl.so
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_SHARED_LIBRARY)
    SSL_TYPE := shared
else
    # it's okay if ssl isn't present depending on your app; optionally error
    $(warning [Android.mk] WARNING: ssl (libssl.so / libssl.a) not found for $(TARGET_ARCH_ABI))
endif


ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libpng.so),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := png
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libpng.so
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_SHARED_LIBRARY)
    PNG_TYPE := shared
else ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libpng.a),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := png
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libpng.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_STATIC_LIBRARY)
    PNG_TYPE := static

else ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libpng16.a),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := png
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libpng16.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_STATIC_LIBRARY)
    PNG_TYPE := static
else
    ifeq ($(ERROR_ON_MISSING),true)
        $(error [Android.mk] ERROR: png (libpng.so / libpng.a / libpng16.a) not found for $(TARGET_ARCH_ABI))
    else
        $(warning [Android.mk] WARNING: png (libpng.so / libpng.a / libpng16.a) not found for $(TARGET_ARCH_ABI))
    endif
endif


ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libz.so),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := z
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libz.so
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_SHARED_LIBRARY)
    Z_TYPE := shared
else ifneq ($(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libz.a),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := z
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libz.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
    include $(PREBUILT_STATIC_LIBRARY)
    Z_TYPE := static
else
    $(warning [Android.mk] WARNING: zlib (libz.so / libz.a) not found for $(TARGET_ARCH_ABI) — will try system -lz if available)
    Z_TYPE := none
endif


include $(CLEAR_VARS)
LOCAL_MODULE := browseropt
LOCAL_SRC_FILES := browseropt.cpp


LOCAL_C_INCLUDES := $(LOCAL_PATH)/include \
                    $(LOCAL_PATH)/include/openssl \
                    $(LOCAL_PATH)/include/png

LOCAL_CPPFLAGS := -std=c++11 -fexceptions -frtti -O3
LOCAL_CFLAGS := -std=c11 -fexceptions -frtti -O3


LOCAL_LDLIBS := -llog -landroid


ifeq ($(CRYPTO_TYPE),shared)
    LOCAL_SHARED_LIBRARIES += crypto
else ifeq ($(CRYPTO_TYPE),static)
    LOCAL_STATIC_LIBRARIES += crypto
endif


ifeq ($(SSL_TYPE),shared)
    LOCAL_SHARED_LIBRARIES += ssl
else ifeq ($(SSL_TYPE),static)
    LOCAL_STATIC_LIBRARIES += ssl_static
endif


ifeq ($(PNG_TYPE),shared)
    LOCAL_SHARED_LIBRARIES += png
else ifeq ($(PNG_TYPE),static)
    LOCAL_STATIC_LIBRARIES += png
endif


ifeq ($(Z_TYPE),shared)
    LOCAL_SHARED_LIBRARIES += z
else ifeq ($(Z_TYPE),static)
    LOCAL_STATIC_LIBRARIES += z
else

    LOCAL_LDLIBS += -lz
endif

include $(BUILD_SHARED_LIBRARY)
