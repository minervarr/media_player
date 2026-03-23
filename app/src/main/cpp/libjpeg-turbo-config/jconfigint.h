/* jconfigint.h -- generated manually for Android NDK builds of libjpeg-turbo 2.1.5.1 */

#define BUILD  "20260322"
#undef inline
#define INLINE  __attribute__((always_inline)) inline
#define THREAD_LOCAL  __thread
#define PACKAGE_NAME  "libjpeg-turbo"
#define VERSION  "2.1.5.1"

#if defined(__LP64__) || defined(_LP64)
#define SIZEOF_SIZE_T  8
#else
#define SIZEOF_SIZE_T  4
#endif

#define HAVE_BUILTIN_CTZL

/* #undef HAVE_INTRIN_H */

#if defined(__has_attribute)
#if __has_attribute(fallthrough)
#define FALLTHROUGH  __attribute__((fallthrough));
#else
#define FALLTHROUGH
#endif
#else
#define FALLTHROUGH
#endif
