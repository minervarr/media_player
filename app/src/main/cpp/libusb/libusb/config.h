/* libusb config.h for Android */
#ifndef LIBUSB_ANDROID_CONFIG_H
#define LIBUSB_ANDROID_CONFIG_H

#define ENABLE_LOGGING 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_SYS_TIME_H 1
#define HAVE_OS_TIMER 1
#define HAVE_EVENTFD 1
#define HAVE_TIMERFD 1
#define HAVE_PIPE2 1
#define HAVE_NFDS_T 1

#define DEFAULT_VISIBILITY __attribute__((visibility("default")))
#define PRINTF_FORMAT(a, b) __attribute__((__format__(__printf__, a, b)))

#define PLATFORM_POSIX 1
#define OS_LINUX 1
#define THREADS_POSIX 1

#endif
