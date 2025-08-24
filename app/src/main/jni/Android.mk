LOCAL_PATH := $(call my-dir)


include $(CLEAR_VARS)
LOCAL_MODULE := crypto
LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libcrypto.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include/openssl
include $(PREBUILT_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := png
LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libpng.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := z
LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libz.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := browseropt
LOCAL_SRC_FILES := browseropt.cpp
LOCAL_SHARED_LIBRARIES := crypto png z
LOCAL_LDLIBS := -llog -landroid
LOCAL_CFLAGS := -std=c++11 -fexceptions -frtti
LOCAL_CPPFLAGS := -std=c++11 -fexceptions -frtti
include $(BUILD_SHARED_LIBRARY)
