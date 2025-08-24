LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := browseropt
LOCAL_SRC_FILES := browseropt.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_LDLIBS := -llog -landroid
LOCAL_CFLAGS := -std=c++11 -fexceptions -frtti
LOCAL_CPPFLAGS := -std=c++11 -fexceptions -frtti
include $(BUILD_SHARED_LIBRARY)
