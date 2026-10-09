# sherpa-onnx's native code reads the config classes' fields and calls back into Kotlin by
# name through JNI, so nothing in it may be renamed or removed.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# PdfBox-Android: optional JPEG 2000 support isn't bundled.
-dontwarn com.gemalto.jp2.**

# commons-compress: only bzip2 and tar are used; the optional codecs and OSGi aren't bundled.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn java.lang.invoke.**
