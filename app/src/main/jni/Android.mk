LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := browseropt
LOCAL_SRC_FILES := BrowserOptService.cpp

LOCAL_LDLIBS := -llog -landroid -lz

LOCAL_C_INCLUDES += $(LOCAL_PATH)/../../../../deps_build/install/$(TARGET_ARCH_ABI)/include
LOCAL_LDFLAGS    += -L$(LOCAL_PATH)/../../../../deps_build/install/$(TARGET_ARCH_ABI)/lib

LOCAL_LDLIBS    += -lssl -lcrypto -lpng16

include $(BUILD_SHARED_LIBRARY)
