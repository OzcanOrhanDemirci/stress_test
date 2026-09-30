#pragma once

#include <android/log.h>

#define STRESS_LOG_TAG "stress"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, STRESS_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, STRESS_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, STRESS_LOG_TAG, __VA_ARGS__)
