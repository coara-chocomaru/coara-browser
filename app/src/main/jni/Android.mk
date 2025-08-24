LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := openssl_crypto
LOCAL_SRC_FILES := libs/libcrypto.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := png
LOCAL_SRC_FILES := libs/libpng.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := zlib
LOCAL_SRC_FILES := libs/libz.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := browseropt
LOCAL_SRC_FILES := browseropt.cpp
LOCAL_C_INCLUDES += $(LOCAL_PATH)/include
LOCAL_LDLIBS := -llog -landroid
LOCAL_SHARED_LIBRARIES := openssl_crypto png zlib
LOCAL_CFLAGS := -O3 -march=armv8-a+simd -fPIC -Wall -Wextra -Wno-unused-parameter -fno-exceptions -fno-rtti
include $(BUILD_SHARED_LIBRARY)
