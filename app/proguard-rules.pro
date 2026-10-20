# Use proguard-android-optimize.txt and dependency consumer rules as the baseline.
# Keep source locations for retrace; archive mapping.txt with each released APK.
-keepattributes SourceFile,LineNumberTable

# ONNX Runtime's native library looks up Java classes, constructors and fields
# by name. Its Android AAR does not ship consumer rules for this JNI contract.
-keep class ai.onnxruntime.** { *; }

# LiteRT 2.x liblitert_jni.so constructs these objects through FindClass /
# GetMethodID (including enum fields). Its consumer rules only cover the
# legacy org.tensorflow.lite UsedByReflection annotations. Native method names
# themselves are already protected by proguard-android-optimize.txt.
-keep class com.google.ai.edge.litert.TensorType { *; }
-keep class com.google.ai.edge.litert.TensorType$* { *; }
-keep class com.google.ai.edge.litert.TensorBufferRequirements { *; }
-keep class com.google.ai.edge.litert.LiteRtException { *; }

# PDFBox's optional JPEG2000 decoder is not bundled. JPXFilter checks for it
# with Class.forName and throws MissingImageReaderException when absent;
# unsupported PDF images use the existing PdfRenderer fallback.
-dontwarn com.gemalto.jp2.JP2Decoder
